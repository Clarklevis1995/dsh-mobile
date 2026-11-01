package tsnetgo

// 窄 API：字节流与连接都留在 Go 侧，只有控制面（string/int）跨 JNI。
import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tsnet"
)

type Node struct {
	srv *tsnet.Server

	mu      sync.Mutex
	fw      map[string]*forwarder
	logTail []string
	authURL string
	verbose bool

	proxyLn   net.Listener
	proxyPort int
}

// Start 只建对象、不联网；鉴权回调在创建时就接好，避免漏掉登录 URL。
func Start(hostname, dir string) (*Node, error) {
	if dir == "" {
		return nil, fmt.Errorf("dir required")
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	// Android 的应用进程里没有 HOME、也没有 TMPDIR，logpolicy 会退到
	// os.MkdirTemp 再 panic("no safe place found to store log state")。
	// TS_LOGS_DIR 是它自己的显式覆盖入口（logpolicy.go:211）。
	logsDir := filepath.Join(dir, "logs")
	if err := os.MkdirAll(logsDir, 0o700); err == nil {
		_ = os.Setenv("TS_LOGS_DIR", logsDir)
	}
	n := &Node{}
	n.srv = &tsnet.Server{
		Hostname: hostname,
		Dir:      dir,
		UserLogf: n.onLog,
		// Logf 是完整内部日志（magicsock/netcheck/prefs 都在里面）；UserLogf 只有用户级消息。
		// 之前我只挂 UserLogf，所以"节点到底安没安装子网路由"这类话从来没见过。
		Logf: n.onLog,
	}
	return n, nil
}

// SetAuthKey / SetClientSecret 必须在 BringUp 之前调用；
// 都为空则走 tsnet 文档里的第 6 条：把交互式登录 URL 打到日志，由 app 打开。
func (n *Node) SetAuthKey(key string)         { n.srv.AuthKey = key }
func (n *Node) SetClientSecret(secret string) { n.srv.ClientSecret = secret }
func (n *Node) SetEphemeral(on bool)          { n.srv.Ephemeral = on }

// BringUp 启动节点并等一次状态；timeoutSeconds 内拿不到就返回失败 + 登录 URL。
func (n *Node) BringUp(timeoutSeconds int) string {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()
	// srv.Start() 不吃 context：真机上它曾长时间不返回，把整条链路吊住。
	// 所以放到 goroutine 里由我们自己设死线——失败要能解释，不能只是不动。
	startDone := make(chan error, 1)
	go func() { startDone <- n.srv.Start() }()
	select {
	case err := <-startDone:
		if err != nil {
			return jsonOut(map[string]any{"ok": false, "stage": "start", "error": err.Error(), "log": n.tail(40)})
		}
	case <-time.After(time.Duration(timeoutSeconds) * time.Second):
		return jsonOut(map[string]any{"ok": false, "stage": "start-timeout",
			"error": fmt.Sprintf("srv.Start did not return within %d seconds", timeoutSeconds), "log": n.tail(40)})
	}
	st, err := n.srv.Up(ctx)
	if st != nil { // 部分状态也带 BackendState 与 AuthURL，比只回错误有用
		out := statusObj(st)
		out["error"] = errString(err)
		return jsonOut(out)
	}
	return jsonOut(map[string]any{"ok": false, "stage": "up", "error": errString(err),
		"authURL": n.AuthURL(), "log": n.tail(12)})
}

func (n *Node) StatusNow() string {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	// LocalClient() 内部会再调 s.Start()，而且不吃 context：半启动的节点能把这个调用
	// 无限吊住（真机实测：Start 超时后 statusJson 就再没返回过）。所以整段自己设死线。
	// 结果直接以 JSON 传回来：不引用来路类型（NetworkStatus 在新版本已改名），
	// 也让这段只做一件事——要么在死线内给出答案，要么给出能读的原因。
	ch := make(chan string, 1)
	go func() {
		lc, localErr := n.srv.LocalClient()
		if localErr != nil {
			ch <- jsonOut(map[string]any{"ok": false, "stage": "localapi", "error": localErr.Error()})
			return
		}
		s, statusErr := lc.Status(ctx)
		if s == nil {
			ch <- jsonOut(map[string]any{"ok": false, "stage": "status", "error": errString(statusErr)})
			return
		}
		out := statusObj(s)
		out["error"] = errString(statusErr)
		ch <- jsonOut(out)
	}()
	select {
	case result := <-ch:
		return result
	case <-time.After(10 * time.Second):
		return jsonOut(map[string]any{"ok": false, "stage": "localapi-timeout",
			"error": "LocalClient()/Status did not return within 10 seconds", "log": n.tail(40)})
	}
}

// AcceptRoutes 打开「接受别人广播的子网路由」（等价于 tailscale set --accept-routes）。
// 官方客户端默认开，tsnet 默认不开：实测过——tailnet 里有机器广播自己的 /24 子网路由时
// （status 里的 Self.PrimaryRoutes），装系统客户端的设备能到那些局域网地址，
// 我们的节点不开 accept-routes 就到不了。
// 必须在引擎已经连上之后再改 pref——放在 Start/Up 之前会被忽略（实测无效）。
func (n *Node) AcceptRoutes() string {
	done := make(chan string, 1)
	go func() {
		lc, err := n.srv.LocalClient()
		if err != nil {
			done <- jsonOut(map[string]any{"ok": false, "stage": "localapi", "error": err.Error()})
			return
		}
		pctx, pcancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer pcancel()
		if _, err := lc.EditPrefs(pctx, &ipn.MaskedPrefs{
			Prefs:       ipn.Prefs{RouteAll: true},
			RouteAllSet: true,
		}); err != nil {
			done <- jsonOut(map[string]any{"ok": false, "stage": "edit-prefs", "error": err.Error()})
			return
		}
		done <- jsonOut(map[string]any{"ok": true})
	}()
	select {
	case out := <-done:
		return out
	case <-time.After(15 * time.Second):
		return jsonOut(map[string]any{"ok": false, "stage": "edit-prefs-timeout",
			"error": "EditPrefs did not return within 15 seconds"})
	}
}

// StartProxy 在 127.0.0.1 上起一个 HTTP CONNECT 代理，连接经 tailnet 转发。
//
// 为什么不用 tsnet.Loopback()：那个端口是节点自己的 LocalAPI（要求
// Sec-Tailscale: localapi 头，见 tsnet.go:546），把它当 HTTP 代理用会得到它的
// 403，请求根本没到对端——真机上就是这个现象。而 OkHttp 也不支持带认证的 SOCKS5。
// CONNECT 代理保持原 URL、Host、SNI 与证书校验不变，凭据不跨 JNI。
func (n *Node) StartProxy() string {
	n.mu.Lock()
	if n.proxyLn != nil {
		port := n.proxyPort
		n.mu.Unlock()
		return jsonOut(map[string]any{"ok": true, "host": "127.0.0.1", "port": port, "reused": true})
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		n.mu.Unlock()
		return jsonOut(map[string]any{"ok": false, "stage": "proxy-listen", "error": err.Error()})
	}
	n.proxyLn = ln
	_, portStr, _ := net.SplitHostPort(ln.Addr().String())
	port, _ := strconv.Atoi(portStr)
	n.proxyPort = port
	n.mu.Unlock()
	go n.serveProxy(ln)
	return jsonOut(map[string]any{"ok": true, "host": "127.0.0.1", "port": port})
}

func (n *Node) serveProxy(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return // 监听器被 Close() 关掉时正常退出
		}
		go n.handleProxyConn(conn)
	}
}

func (n *Node) handleProxyConn(conn net.Conn) {
	defer conn.Close()
	br := bufio.NewReader(conn)
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	target := req.Host
	if req.URL != nil && req.URL.Host != "" {
		target = req.URL.Host
	}
	upstream, err := n.srv.Dial(context.Background(), "tcp", target)
	if err != nil {
		n.onLog("tsnetgo: proxy dial %s failed: %v", target, err)
		io.WriteString(conn, "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
		return
	}
	defer upstream.Close()

	if strings.EqualFold(req.Method, http.MethodConnect) {
		// 隧道模式：OkHttp 对 wss 会走 CONNECT。
		io.WriteString(conn, "HTTP/1.1 200 Connection Established\r\n\r\n")
	} else {
		// 绝对形式转发：明文 ws 时 OkHttp 发的是 `GET http://host/path`，不是 CONNECT
		// （真机实测：只支持 CONNECT 会得到代理自己的 405）。改写成 origin-form 再转发，
		// 后面的响应（含 101 升级后的帧）原样管道回客户端。
		if req.URL == nil || req.URL.Host == "" {
			io.WriteString(conn, "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
			return
		}
		req.RequestURI = ""
		req.Host = target
		if err := req.Write(upstream); err != nil {
			return
		}
	}
	done := make(chan struct{})
	go func() { io.Copy(upstream, br); close(done) }()
	io.Copy(conn, upstream)
	<-done
}

// Logs 返回节点内部日志尾部（排障用；不含凭据）。
// 真机上 logcat 会被厂商丢弃，所以这条必须走 API 把话带出来。
func (n *Node) Logs() string {
	return jsonOut(map[string]any{"ok": true, "log": n.tail(60)})
}

// AuthURL 返回日志里出现过的登录链接（无则为空串）。
func (n *Node) AuthURL() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.authURL
}

// Proxy 返回内置 loopback SOCKS5 地址与凭据。注意：OkHttp 不支持带凭据的
// SOCKS5，所以 app 侧要么用 Dial/HTTPClient，要么自建无鉴权的转发器。
func (n *Node) Proxy() string {
	type outcome struct {
		addr, cred, api string
		err             error
	}
	ch := make(chan outcome, 1)
	go func() {
		addr, proxyCred, apiCred, err := n.srv.Loopback()
		ch <- outcome{addr, proxyCred, apiCred, err}
	}()
	select {
	case got := <-ch:
		if got.err != nil {
			return jsonOut(map[string]any{"ok": false, "error": got.err.Error()})
		}
		return jsonOut(map[string]any{"ok": true, "addr": got.addr, "proxyCred": got.cred, "localAPICred": got.api})
	case <-time.After(10 * time.Second):
		// Loopback() 会拉起 localAPI/SOCKS，同样不吃 context：给它期限。
		return jsonOut(map[string]any{"ok": false, "stage": "loopback-timeout",
			"error": "Loopback() did not return within 10 seconds"})
	}
}

// ProbeHTTP 经隧道 GET 一个 URL，只回状态与字节数——spike 的验收动作。
func (n *Node) ProbeHTTP(rawURL string, maxBytes int) string {
	// 实测：这里的 ctx 在真机上没有拦住调用（20 秒的 ctx，实际跑了近 300 秒）——
	// gVisor netstack 那条路不理取消。所以和外层一样自己设死线。
	ch := make(chan string, 1)
	go func() { ch <- n.probeHTTPNow(rawURL, maxBytes) }()
	select {
	case out := <-ch:
		return out
	case <-time.After(20 * time.Second):
		return jsonOut(map[string]any{"ok": false, "stage": "probe-timeout",
			"error": "HTTP request did not return within 20 seconds"})
	}
}

func (n *Node) probeHTTPNow(rawURL string, maxBytes int) string {
	ctx, cancel := context.WithTimeout(context.Background(), 18*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return jsonOut(map[string]any{"ok": false, "error": err.Error()})
	}
	res, err := n.srv.HTTPClient().Do(req)
	if err != nil {
		return jsonOut(map[string]any{"ok": false, "error": err.Error(), "authURL": n.AuthURL()})
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, int64(maxBytes)))
	head := string(body)
	if len(head) > 200 {
		head = head[:200]
	}
	return jsonOut(map[string]any{"ok": res.StatusCode < 400, "status": res.StatusCode,
		"bytes": len(body), "head": strings.TrimSpace(head)})
}

func (n *Node) Close() error {
	n.mu.Lock()
	for _, f := range n.fw {
		f.cancel()
		_ = f.ln.Close()
	}
	n.fw = nil
	if n.proxyLn != nil {
		_ = n.proxyLn.Close()
		n.proxyLn = nil
		n.proxyPort = 0
	}
	n.mu.Unlock()
	// 引擎关闭会断 DERP、刷状态，可能长时间不返回（真机上测试的收尾就卡在这里）。
	// 转发器已经先放掉了，这里给 srv.Close() 一个死线，超时就报错而不是吊住调用方。
	done := make(chan error, 1)
	go func() { done <- n.srv.Close() }()
	select {
	case err := <-done:
		return err
	case <-time.After(10 * time.Second):
		return fmt.Errorf("srv.Close did not return within 10 seconds")
	}
}

// SetVerbose 打开/关闭把节点内部日志转发到进程标准日志（Android 上会进 logcat）。
// 真机上厂商会丢应用日志，所以只在调试时打开；排障需要的是"节点自己说了什么"，
// 比如路由有没有安装（setRoutes）、EditPrefs 是否被拒绝。
func (n *Node) SetVerbose(on bool) {
	n.mu.Lock()
	n.verbose = on
	n.mu.Unlock()
}

func (n *Node) onLog(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	n.mu.Lock()
	verbose := n.verbose
	n.mu.Unlock()
	if verbose {
		log.Printf("tsnet-node %s", line)
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.authURL == "" {
		if m := loginURLRe.FindString(line); m != "" {
			n.authURL = m
		}
	}
	if len(n.logTail) > 200 {
		n.logTail = n.logTail[len(n.logTail)-100:]
	}
	n.logTail = append(n.logTail, line)
}

func (n *Node) tail(k int) []string {
	n.mu.Lock()
	defer n.mu.Unlock()
	if len(n.logTail) <= k {
		return append([]string(nil), n.logTail...)
	}
	return append([]string(nil), n.logTail[len(n.logTail)-k:]...)
}

var loginURLRe = regexp.MustCompile(`https://[a-z0-9.-]*tailscale[a-z0-9./?=&_-]*`)

func statusObj(st *ipnstate.Status) map[string]any {
	var ips []string
	for _, a := range st.TailscaleIPs {
		ips = append(ips, a.String())
	}
	return map[string]any{"ok": st.BackendState == "Running", "state": st.BackendState,
		"tailscaleIPs": ips, "peers": len(st.Peer),
		"authURL": st.AuthURL, "magicDNS": st.MagicDNSSuffix}
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

func jsonOut(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return fmt.Sprintf(`{"ok":false,"error":%q}`, err.Error())
	}
	return string(b)
}
