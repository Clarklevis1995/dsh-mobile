package tsnetgo

// 把 127.0.0.1 上的一个本地端口转发到隧道内的目标。
// app 的 HTTP/WebSocket 客户端只需要连本机；鉴权与路由都留在 Go 侧。
import (
	"context"
	"fmt"
	"io"
	"net"
	"sync"
)

type forwarder struct {
	ln     net.Listener
	target string
	cancel context.CancelFunc
}

func (n *Node) ensureForwardLocked(target string) (*forwarder, error) {
	if n.fw == nil {
		n.fw = map[string]*forwarder{}
	}
	if f, ok := n.fw[target]; ok {
		return f, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listen: %w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	f := &forwarder{ln: ln, target: target, cancel: cancel}
	n.fw[target] = f
	go f.run(ctx, n)
	return f, nil
}

func (f *forwarder) run(ctx context.Context, n *Node) {
	for {
		c, err := f.ln.Accept()
		if err != nil {
			return
		}
		go f.pipe(ctx, n, c)
	}
}

func (f *forwarder) pipe(ctx context.Context, n *Node, local net.Conn) {
	remote, err := n.srv.Dial(ctx, "tcp", f.target)
	if err != nil {
		_ = local.Close()
		return
	}
	pipeForward(ctx, local, remote)
}

// 只关闭本转发器的连接。取消或任意一端退出时唤醒另一条复制协程，避免切换后旧 socket 挂住。
func pipeForward(ctx context.Context, local, remote net.Conn) {
	closeBoth := func() { _ = local.Close(); _ = remote.Close() }
	stop := context.AfterFunc(ctx, closeBoth)
	defer stop()
	defer closeBoth()
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(remote, local)
		closeBoth()
	}()
	_, _ = io.Copy(local, remote)
	closeBoth()
	wg.Wait()
}

// ForwardTo 返回本地端口；同一 target 复用同一个监听器。
func (n *Node) ForwardTo(host string, port int) (int, error) {
	target := fmt.Sprintf("%s:%d", host, port)
	n.mu.Lock()
	defer n.mu.Unlock()
	f, err := n.ensureForwardLocked(target)
	if err != nil {
		return 0, err
	}
	ta, ok := f.ln.Addr().(*net.TCPAddr)
	if !ok {
		return 0, fmt.Errorf("unexpected listen address")
	}
	return ta.Port, nil
}

// ForwardsJSON 给 app 的调试界面用：当前有哪些转发器、端口是多少。
func (n *Node) ForwardsJSON() string {
	n.mu.Lock()
	defer n.mu.Unlock()
	out := "["
	for target, f := range n.fw {
		if out != "[" {
			out += ","
		}
		out += fmt.Sprintf("{\"target\":%q,\"local\":%q}", target, f.ln.Addr().String())
	}
	return out + "]"
}
