//go:build darwin

package tsnetgo

import (
	"context"
	"errors"
	"net"
	"syscall"
	"testing"

	"golang.org/x/sys/unix"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
)

// 在真正的 Darwin socket 上检查 IP_BOUND_IF，Control 返回后立即中断，
// 不向 Fake-IP 发包，也不要求测试机安装 VPN。
func TestEmbeddedRoutingLeavesTCPAndUDPUnboundToPhysicalInterface(t *testing.T) {
	configureEmbeddedRouting()
	t.Cleanup(func() { netns.SetEnabled(true) })
	// 模拟 SDK 收到 netmap 后重新启用物理接口绑定，系统路由策略仍应生效。
	netns.SetDisableBindConnToInterface(t.Logf, false)
	monitor := netmon.NewStatic()
	for _, family := range []struct {
		suffix, target, listen string
		protocol, option       int
	}{
		{"4", "198.18.0.32:443", "0.0.0.0:0", unix.IPPROTO_IP, unix.IP_BOUND_IF},
		{"6", "[2001:db8::32]:443", "[::]:0", unix.IPPROTO_IPV6, unix.IPV6_BOUND_IF},
	} {
		for _, transport := range []string{"tcp", "udp"} {
			t.Run(transport+family.suffix, func(t *testing.T) {
				stop := errors.New("stop before sending any packets")
				checked := false
				raw := &net.Dialer{}
				dialer := netns.FromDialer(t.Logf, monitor, raw)
				listener := netns.Listener(t.Logf, monitor)
				control := raw.Control
				if transport == "udp" {
					control = listener.Control
				}
				inspect := func(network, address string, conn syscall.RawConn) error {
					if control != nil {
						if err := control(network, address, conn); err != nil {
							return err
						}
					}
					if err := conn.Control(func(fd uintptr) {
						index, err := unix.GetsockoptInt(int(fd), family.protocol, family.option)
						if err != nil {
							t.Errorf("read bound interface: %v", err)
						} else if index != 0 {
							t.Errorf("socket bound to interface %d; must follow system VPN routing", index)
						}
						checked = true
					}); err != nil {
						return err
					}
					return stop
				}
				var err error
				if transport == "tcp" {
					raw.Control = inspect
					_, err = dialer.DialContext(context.Background(), transport+family.suffix, family.target)
				} else {
					listener.Control = inspect
					_, err = listener.ListenPacket(context.Background(), transport+family.suffix, family.listen)
				}
				if !checked || !errors.Is(err, stop) {
					t.Fatalf("socket inspection did not stop connection: checked=%v error=%v", checked, err)
				}
			})
		}
	}
}
