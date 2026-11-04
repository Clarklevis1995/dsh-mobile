package tsnetgo

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

func TestStatusIncludesOnlyCurrentAccountMetadata(t *testing.T) {
	st := &ipnstate.Status{
		BackendState: "Running",
		Self:         &ipnstate.PeerStatus{UserID: 1},
		User: map[tailcfg.UserID]tailcfg.UserProfile{
			1: {LoginName: "current@example.com"},
			2: {LoginName: "peer@example.com"},
		},
		CurrentTailnet: &ipnstate.TailnetStatus{Name: "work.example"},
	}
	out := statusObj(st)
	if out["loginName"] != "current@example.com" || out["tailnetName"] != "work.example" {
		t.Fatalf("unexpected account metadata: %v", out)
	}
	if _, ok := out["User"]; ok {
		t.Fatal("status must not expose peer user profiles")
	}
}

func TestStatusWithoutIdentityRemainsCompatible(t *testing.T) {
	out := statusObj(&ipnstate.Status{BackendState: "NeedsLogin", AuthURL: "https://login.tailscale.com/a/test"})
	if out["state"] != "NeedsLogin" || out["ok"] != false {
		t.Fatal("login status changed")
	}
	if _, ok := out["loginName"]; ok {
		t.Fatal("an unauthenticated node must not claim a login identity")
	}
}

func TestAuthURLIgnoresControlServerAndPreservesAuthorizationCode(t *testing.T) {
	n := &Node{authReady: make(chan struct{})}
	n.onLog("control server key from https://controlplane.tailscale.com: public-key")
	if n.AuthURL() != "" {
		t.Fatal("control server address must not be treated as a login URL")
	}
	select {
	case <-n.authReady:
		t.Fatal("control server address must not signal login readiness")
	default:
	}
	want := "https://login.tailscale.com/a/AbC_123-x?next=%2Fadmin"
	n.onLog("control: AuthURL is %s", want)
	if got := n.AuthURL(); got != want {
		t.Fatalf("authorization code was truncated or changed: %q", got)
	}
	select {
	case <-n.authReady:
	default:
		t.Fatal("login URL must signal readiness immediately")
	}
	// 重复日志不得再次关闭 channel。
	n.onLog("To start this server, go to: %s", want)
}

func TestBeginLoginReturnsWhenURLArrivesWithoutWaitingForRunning(t *testing.T) {
	n := &Node{authReady: make(chan struct{})}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	up := make(chan bringUpResult, 1)
	result := make(chan string, 1)
	go func() { result <- n.waitForBringUp(ctx, up, true) }()
	n.onLog("control: AuthURL is https://login.tailscale.com/a/AbC123")
	select {
	case raw := <-result:
		var status map[string]any
		if err := json.Unmarshal([]byte(raw), &status); err != nil {
			t.Fatal(err)
		}
		if status["state"] != "NeedsLogin" || status["authURL"] != n.AuthURL() {
			t.Fatalf("unexpected login result: %v", status)
		}
		if ctx.Err() != nil {
			t.Fatal("login URL returned only after the deadline")
		}
	case <-time.After(time.Second):
		t.Fatal("login URL was ready but bring-up kept waiting for Running")
	}
}

func TestOriginalBringUpKeepsWaitingForRunning(t *testing.T) {
	n := &Node{authReady: make(chan struct{})}
	n.onLog("control: AuthURL is https://login.tailscale.com/a/test")
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	up := make(chan bringUpResult, 1)
	up <- bringUpResult{status: &ipnstate.Status{BackendState: "Running"}}
	var status map[string]any
	if err := json.Unmarshal([]byte(n.waitForBringUp(ctx, up, false)), &status); err != nil {
		t.Fatal(err)
	}
	if status["state"] != "Running" {
		t.Fatalf("BringUp changed its original contract: %v", status)
	}
}

// 用两个独立的连接管道验证候选节点取消不会关闭当前节点的数据流。
func TestCancellingOneForwardKeepsOtherForwardUsable(t *testing.T) {
	makeForward := func() (net.Conn, context.CancelFunc, <-chan struct{}) {
		ctx, cancel := context.WithCancel(context.Background())
		client, local := net.Pipe()
		remote, echo := net.Pipe()
		done := make(chan struct{})
		go func() { pipeForward(ctx, local, remote); close(done) }()
		go func() { _, _ = io.Copy(echo, echo) }()
		t.Cleanup(func() { cancel(); _ = client.Close(); _ = echo.Close() })
		return client, cancel, done
	}
	active, cancelActive, activeDone := makeForward()
	_, cancelPending, pendingDone := makeForward()
	cancelPending()
	select {
	case <-pendingDone:
	case <-time.After(time.Second):
		t.Fatal("candidate forward did not close after cancellation")
	}
	_ = active.SetDeadline(time.Now().Add(time.Second))
	if _, err := active.Write([]byte("ping")); err != nil {
		t.Fatalf("current forward was interrupted: %v", err)
	}
	buf := make([]byte, 4)
	if _, err := io.ReadFull(active, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("current forward did not preserve traffic: %q, %v", buf, err)
	}
	cancelActive()
	select {
	case <-activeDone:
	case <-time.After(time.Second):
		t.Fatal("current forward did not close after its own cancellation")
	}
}

func TestForwardRemoteCloseUnblocksLocalConnection(t *testing.T) {
	client, local := net.Pipe()
	remote, peer := net.Pipe()
	defer client.Close()
	done := make(chan struct{})
	go func() { pipeForward(context.Background(), local, remote); close(done) }()
	_ = peer.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("remote EOF left the forwarder blocked")
	}
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := client.Read(make([]byte, 1)); err != io.EOF {
		t.Fatalf("local peer did not see EOF: %v", err)
	}
}
