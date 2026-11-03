package tsnetgo

import (
	"context"
	"net"
	"testing"
	"time"
)

func TestFailedForwardListenerIsEvictedAndRecreated(t *testing.T) {
	n := &Node{}
	port, err := n.ForwardTo("100.100.1.19", 3081)
	if err != nil || port == 0 {
		t.Fatalf("initial forward: port=%d error=%v", port, err)
	}
	defer n.ResetLocalListeners()
	target := "100.100.1.19:3081"
	n.mu.Lock()
	old := n.fw[target]
	n.mu.Unlock()
	_ = old.ln.Close()
	deadline := time.After(time.Second)
	for old.alive.Load() {
		select {
		case <-deadline:
			t.Fatal("failed listener remained live")
		case <-time.After(time.Millisecond):
		}
	}
	port, err = n.ForwardTo("100.100.1.19", 3081)
	if err != nil || port == 0 {
		t.Fatalf("replacement forward: port=%d error=%v", port, err)
	}
	n.mu.Lock()
	replacement := n.fw[target]
	n.mu.Unlock()
	if replacement == old || !replacement.alive.Load() {
		t.Fatal("ForwardTo reused the failed listener")
	}
}

func TestRetiredForwardCannotRemoveItsReplacement(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	old := &forwarder{ln: ln, target: "target", cancel: cancel}
	old.alive.Store(true)
	replacement := &forwarder{}
	n := &Node{fw: map[string]*forwarder{"target": replacement}}
	_ = ln.Close()
	old.run(ctx, n)
	if n.fw["target"] != replacement {
		t.Fatal("late listener exit removed a newer forward")
	}
}

func TestResumeResetClosesForwardAndProxyWithoutForgettingIdentity(t *testing.T) {
	n := &Node{authURL: "retained-login-metadata"}
	if _, err := n.ForwardTo("100.100.1.19", 3081); err != nil {
		t.Fatal(err)
	}
	n.mu.Lock()
	f := n.fw["100.100.1.19:3081"]
	n.mu.Unlock()
	n.StartProxy()
	n.mu.Lock()
	proxy := n.proxyLn
	n.mu.Unlock()
	n.ResetLocalListeners()
	if f.alive.Load() || n.ForwardsJSON() != "[]" || n.AuthURL() != "retained-login-metadata" {
		t.Fatal("reset must retire listeners while keeping node identity")
	}
	if _, err := proxy.Accept(); err == nil {
		t.Fatal("old proxy listener was not closed")
	}
	if n.NeedsRecovery() {
		t.Fatal("resetting local listeners alone must not mark the engine broken")
	}
	n.mu.Lock()
	n.closed = true
	n.mu.Unlock()
	if _, err := n.ForwardTo("100.100.1.19", 3081); err == nil {
		t.Fatal("closed node created a new forward")
	}
}

func TestSocketDamageIsStickyEvenIfBackendStillReportsRunning(t *testing.T) {
	for _, line := range []string{"magicsock: udp receive: bad file descriptor", "netmon: socket is not connected"} {
		n := &Node{}
		n.onLog("%s", line)
		n.onLog("state=Running peers=5")
		if !n.NeedsRecovery() {
			t.Fatalf("socket damage was hidden by backend status: %s", line)
		}
	}
	n := &Node{}
	n.onLog("dial: connection refused")
	if n.NeedsRecovery() {
		t.Fatal("a remote gateway refusal is not evidence of damaged local sockets")
	}
}
