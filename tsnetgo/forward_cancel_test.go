package tsnetgo

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

func waitForwardDone(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("forward operation did not finish")
	}
}

// 使用同一真实监听器：首条连接发出握手后取消，后续两条连接仍独立可用。
func TestPendingDialCancellationPreservesSharedListenerAndOtherChannels(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	f := &forwarder{ln: ln, target: "100.100.1.19:3081", cancel: cancel}
	f.alive.Store(true)
	defer f.stop()
	started := make(chan context.Context, 1)
	firstCancelled := make(chan struct{})
	var calls atomic.Int32
	serveDone := make(chan struct{})
	go func() {
		defer close(serveDone)
		f.acceptConnections(ctx, func(dialCtx context.Context, network, target string) (net.Conn, error) {
			if calls.Add(1) == 1 {
				started <- dialCtx
				<-dialCtx.Done()
				close(firstCancelled)
				return nil, dialCtx.Err()
			}
			remote, echo := net.Pipe()
			go func() { defer echo.Close(); _, _ = io.Copy(echo, echo) }()
			return remote, nil
		})
	}()
	t.Cleanup(func() { f.stop(); waitForwardDone(t, serveDone) })

	connect := func() net.Conn {
		t.Helper()
		client, err := net.DialTimeout("tcp", ln.Addr().String(), time.Second)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = client.Close() })
		_ = client.SetDeadline(time.Now().Add(2 * time.Second))
		return client
	}
	old := connect()
	select {
	case dialCtx := <-started:
		deadline, ok := dialCtx.Deadline()
		if !ok || time.Until(deadline) > forwardDialTimeout {
			t.Fatal("pending dial has no bounded deadline")
		}
	case <-time.After(time.Second):
		t.Fatal("initial dial did not start")
	}
	if _, err := old.Write([]byte("GET /ws/mobile HTTP/1.1\r\nHost: gateway\r\n\r\n")); err != nil {
		t.Fatal(err)
	}
	_ = old.Close()
	waitForwardDone(t, firstCancelled)
	if ctx.Err() != nil || !f.alive.Load() {
		t.Fatal("cancelling one dial retired the shared forwarder")
	}

	control, conversation := connect(), connect()
	echo := func(client net.Conn, payload string) {
		t.Helper()
		if _, err := client.Write([]byte(payload)); err != nil {
			t.Fatal(err)
		}
		got := make([]byte, len(payload))
		if _, err := io.ReadFull(client, got); err != nil || string(got) != payload {
			t.Fatalf("independent channel failed: got=%q error=%v", got, err)
		}
	}
	echo(control, "control")
	echo(conversation, "conversation")
	_ = control.Close()
	echo(conversation, "still-connected")
}

func TestForwardPreservesBufferedRequestAndLargeBidirectionalTraffic(t *testing.T) {
	client, local := net.Pipe()
	remote, peer := net.Pipe()
	defer client.Close()
	defer peer.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	release := make(chan struct{})
	dialReturned := make(chan context.Context, 1)
	done := make(chan struct{})
	go func() {
		defer close(done)
		dialAndPipeForward(ctx, local, "gateway:3081", forwardDialTimeout, func(dialCtx context.Context, _, _ string) (net.Conn, error) {
			select {
			case <-release:
				dialReturned <- dialCtx
				return remote, nil
			case <-dialCtx.Done():
				return nil, dialCtx.Err()
			}
		})
	}()
	t.Cleanup(func() { cancel(); _ = client.Close(); _ = peer.Close(); waitForwardDone(t, done) })
	_ = client.SetDeadline(time.Now().Add(2 * time.Second))
	_ = peer.SetDeadline(time.Now().Add(2 * time.Second))
	request := bytes.Repeat([]byte("request-with-fragments-"), 32*1024)
	response := bytes.Repeat([]byte("response-"), 32*1024)
	written := make(chan error, 1)
	go func() { _, err := client.Write(request); written <- err }()
	close(release)
	// 远端用不同的小块读取，确保本地预读缓存分段消费和复用后不丢字节。
	got := make([]byte, len(request))
	for offset := 0; offset < len(got); {
		end := min(offset+97, len(got))
		n, err := peer.Read(got[offset:end])
		if err != nil {
			t.Fatal(err)
		}
		offset += n
	}
	if !bytes.Equal(got, request) {
		t.Fatal("buffered request was lost, duplicated or reordered")
	}
	if err := <-written; err != nil {
		t.Fatal(err)
	}
	go func() { _, err := peer.Write(response); written <- err }()
	got = make([]byte, len(response))
	if _, err := io.ReadFull(client, got); err != nil || !bytes.Equal(got, response) {
		t.Fatalf("response forwarding changed: %v", err)
	}
	if err := <-written; err != nil {
		t.Fatal(err)
	}
	if dialCtx := <-dialReturned; !errors.Is(dialCtx.Err(), context.Canceled) {
		t.Fatal("dial context was not released after success")
	}
	// 释放拨号 context 后连接依然可以继续传输，而非被拨号期限关闭。
	go func() { _, err := client.Write([]byte("ping")); written <- err }()
	if _, err := io.ReadFull(peer, make([]byte, 4)); err != nil {
		t.Fatalf("successful connection was coupled to dial cancellation: %v", err)
	}
	if err := <-written; err != nil {
		t.Fatal(err)
	}
}

func TestPendingForwardClosesLocalOnDialFailureOrNodeCancellation(t *testing.T) {
	for _, mode := range []string{"dial-error", "node-reset", "deadline"} {
		t.Run(mode, func(t *testing.T) {
			client, local := net.Pipe()
			defer client.Close()
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			dialTimeout := forwardDialTimeout
			if mode == "deadline" {
				dialTimeout = 20 * time.Millisecond
			}
			started := make(chan struct{})
			done := make(chan struct{})
			go func() {
				defer close(done)
				dialAndPipeForward(ctx, local, "gateway:3081", dialTimeout, func(dialCtx context.Context, _, _ string) (net.Conn, error) {
					close(started)
					if mode == "dial-error" {
						return nil, errors.New("gateway unreachable")
					}
					<-dialCtx.Done()
					return nil, dialCtx.Err()
				})
			}()
			t.Cleanup(func() { cancel(); _ = client.Close(); waitForwardDone(t, done) })
			waitForwardDone(t, started)
			if mode == "node-reset" {
				cancel()
			}
			waitForwardDone(t, done)
			if _, err := client.Read(make([]byte, 1)); err == nil {
				t.Fatal("failed or cancelled dial left its local connection open")
			}
		})
	}
}

func TestCancelledForwardClosesLateDialSuccessWithoutSendingBufferedRequest(t *testing.T) {
	client, local := net.Pipe()
	remote, peer := net.Pipe()
	defer client.Close()
	defer peer.Close()
	done := make(chan struct{})
	go func() {
		defer close(done)
		dialAndPipeForward(context.Background(), local, "gateway:3081", forwardDialTimeout,
			func(dialCtx context.Context, _, _ string) (net.Conn, error) {
				<-dialCtx.Done()
				return remote, nil // 模拟取消和拨号成功同时发生。
			})
	}()
	t.Cleanup(func() { _ = client.Close(); _ = peer.Close(); waitForwardDone(t, done) })
	if _, err := client.Write([]byte("abandoned-request")); err != nil {
		t.Fatal(err)
	}
	_ = client.Close()
	waitForwardDone(t, done)
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	if n, err := peer.Read(make([]byte, 64)); n != 0 || err != io.EOF {
		t.Fatalf("cancelled attempt sent bytes or left the late remote open: n=%d error=%v", n, err)
	}
}

func TestTimedOutForwardClosesLateDialSuccess(t *testing.T) {
	client, local := net.Pipe()
	remote, peer := net.Pipe()
	defer client.Close()
	defer peer.Close()
	done := make(chan struct{})
	go func() {
		defer close(done)
		dialAndPipeForward(context.Background(), local, "gateway:3081", 20*time.Millisecond,
			func(dialCtx context.Context, _, _ string) (net.Conn, error) {
				<-dialCtx.Done()
				return remote, nil
			})
	}()
	t.Cleanup(func() { _ = client.Close(); _ = peer.Close(); waitForwardDone(t, done) })
	waitForwardDone(t, done)
	_ = peer.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := peer.Read(make([]byte, 1)); err != io.EOF {
		t.Fatalf("timed-out dial installed its late connection: %v", err)
	}
}

func TestForwardLocalReaderRecyclesBuffersAfterPartialReads(t *testing.T) {
	client, local := net.Pipe()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stream := newForwardLocalConn(local, cancel)
	stream.dialing.Store(false)
	go stream.receive(ctx)
	t.Cleanup(func() { cancel(); _ = local.Close(); _ = client.Close(); waitForwardDone(t, stream.done) })
	want := bytes.Repeat([]byte("partial-read"), 16*1024)
	go func() { _, _ = client.Write(want); _ = client.Close() }()
	var got []byte
	buffer := make([]byte, 7)
	for {
		n, err := stream.Read(buffer)
		got = append(got, buffer[:n]...)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	if !bytes.Equal(got, want) {
		t.Fatal("partial reads corrupted or truncated buffered traffic")
	}
}

func TestForwardAllowsServerFirstTrafficAndRemoteCloseReleasesIdleReader(t *testing.T) {
	client, local := net.Pipe()
	remote, peer := net.Pipe()
	defer client.Close()
	defer peer.Close()
	done := make(chan struct{})
	go func() {
		defer close(done)
		dialAndPipeForward(context.Background(), local, "gateway:3081", forwardDialTimeout,
			func(context.Context, string, string) (net.Conn, error) { return remote, nil })
	}()
	t.Cleanup(func() { _ = client.Close(); _ = peer.Close(); waitForwardDone(t, done) })
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	go func() { _, _ = peer.Write([]byte("hello")) }()
	greeting := make([]byte, 5)
	if _, err := io.ReadFull(client, greeting); err != nil || string(greeting) != "hello" {
		t.Fatalf("forward waited for a client request before delivering server traffic: %q %v", greeting, err)
	}
	_ = peer.Close()
	waitForwardDone(t, done)
	if _, err := client.Read(make([]byte, 1)); err != io.EOF {
		t.Fatalf("remote close did not close the idle local connection: %v", err)
	}
}

type observedForwardConn struct {
	net.Conn
	reads atomic.Int32
	full  chan struct{}
}

func (c *observedForwardConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if n > 0 && c.reads.Add(1) == 3 {
		close(c.full)
	}
	return n, err
}

func TestNodeResetReleasesBackpressuredLocalRequest(t *testing.T) {
	client, local := net.Pipe()
	defer client.Close()
	observed := &observedForwardConn{Conn: local, full: make(chan struct{})}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		dialAndPipeForward(ctx, observed, "gateway:3081", forwardDialTimeout,
			func(dialCtx context.Context, _, _ string) (net.Conn, error) {
				<-dialCtx.Done()
				return nil, dialCtx.Err()
			})
	}()
	t.Cleanup(func() { cancel(); _ = client.Close(); waitForwardDone(t, done) })
	written := make(chan struct{})
	go func() {
		defer close(written)
		_, _ = client.Write(make([]byte, 512*1024))
	}()
	waitForwardDone(t, observed.full)
	cancel()
	waitForwardDone(t, done)
	waitForwardDone(t, written)
}
