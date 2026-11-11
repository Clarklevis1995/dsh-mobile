package tsnetgo

// 把 127.0.0.1 上的一个本地端口转发到隧道内的目标。
// app 的 HTTP/WebSocket 客户端只需要连本机；鉴权与路由都留在 Go 侧。
import (
	"context"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const forwardDialTimeout = 30 * time.Second

type forwarder struct {
	ln     net.Listener
	target string
	cancel context.CancelFunc
	alive  atomic.Bool
}

func (f *forwarder) stop() {
	f.alive.Store(false)
	f.cancel()
	_ = f.ln.Close()
}

func (n *Node) ensureForwardLocked(target string) (*forwarder, error) {
	if n.fw == nil {
		n.fw = map[string]*forwarder{}
	}
	if f, ok := n.fw[target]; ok && f.alive.Load() {
		return f, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listen: %w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	f := &forwarder{ln: ln, target: target, cancel: cancel}
	f.alive.Store(true)
	n.fw[target] = f
	go f.run(ctx, n)
	return f, nil
}

func (f *forwarder) run(ctx context.Context, n *Node) {
	defer func() {
		f.stop()
		n.mu.Lock()
		if n.fw[f.target] == f {
			delete(n.fw, f.target)
		}
		n.mu.Unlock()
	}()
	f.acceptConnections(ctx, n.srv.Dial)
}

func (f *forwarder) acceptConnections(ctx context.Context, dial func(context.Context, string, string) (net.Conn, error)) {
	for {
		c, err := f.ln.Accept()
		if err != nil {
			f.alive.Store(false)
			return
		}
		go dialAndPipeForward(ctx, c, f.target, forwardDialTimeout, dial)
	}
}

// 每条连接独立取消；监听器的 context 只负责节点关闭或整体重置。
func dialAndPipeForward(ctx context.Context, local net.Conn, target string, dialTimeout time.Duration,
	dial func(context.Context, string, string) (net.Conn, error)) {
	connectionCtx, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(connectionCtx, func() { _ = local.Close() })
	defer stop()
	stream := newForwardLocalConn(local, cancel)
	go stream.receive(connectionCtx)
	defer func() {
		cancel()
		_ = local.Close()
		<-stream.done
	}()

	dialCtx, cancelDial := context.WithTimeout(connectionCtx, dialTimeout)
	remote, err := dial(dialCtx, "tcp", target)
	stream.dialing.Store(false)
	dialError := dialCtx.Err()
	cancelDial()
	if err != nil || dialError != nil || connectionCtx.Err() != nil {
		if remote != nil {
			_ = remote.Close()
		}
		return
	}
	pipeForward(connectionCtx, stream, remote)
}

// 拨号时继续读取本地连接才能观察到客户端关闭。只缓存三个 16KiB 块，
// 缓存满后施加背压，拨号期限兜底；建立连接后复用这些块，保持字节顺序。
type forwardLocalConn struct {
	net.Conn
	chunks  chan []byte
	free    chan []byte
	done    chan struct{}
	pending []byte
	offset  int
	dialing atomic.Bool
	cancel  context.CancelFunc
}

func newForwardLocalConn(local net.Conn, cancel context.CancelFunc) *forwardLocalConn {
	stream := &forwardLocalConn{
		Conn: local, chunks: make(chan []byte, 2), free: make(chan []byte, 3),
		done: make(chan struct{}), cancel: cancel,
	}
	stream.dialing.Store(true)
	for range cap(stream.free) {
		stream.free <- make([]byte, 16*1024)
	}
	return stream
}

func (c *forwardLocalConn) receive(ctx context.Context) {
	defer close(c.done)
	defer close(c.chunks)
	for {
		var buffer []byte
		select {
		case buffer = <-c.free:
		case <-ctx.Done():
			return
		}
		n, err := c.Conn.Read(buffer)
		if n > 0 {
			select {
			case c.chunks <- buffer[:n]:
			case <-ctx.Done():
				return
			}
		} else {
			c.free <- buffer
		}
		if err != nil {
			if c.dialing.Load() {
				c.cancel()
			}
			return
		}
	}
}

func (c *forwardLocalConn) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	if len(c.pending) == 0 {
		buffer, ok := <-c.chunks
		if !ok {
			return 0, io.EOF
		}
		c.pending = buffer
	}
	n := copy(p, c.pending[c.offset:])
	c.offset += n
	if c.offset == len(c.pending) {
		c.free <- c.pending[:cap(c.pending)]
		c.pending = nil
		c.offset = 0
	}
	return n, nil
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
	target := net.JoinHostPort(host, fmt.Sprint(port))
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.closed {
		return 0, net.ErrClosed
	}
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
