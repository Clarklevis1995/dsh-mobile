//go:build darwin

package tsnetgo

import (
	"runtime"

	"tailscale.com/net/netns"
)

func init() {
	if runtime.GOOS == "ios" {
		configureEmbeddedRouting()
	}
}

// 内嵌节点不创建系统 TUN，底层传输应跟随宿主 App 的系统路由。
// SDK 默认绑定物理接口以绕开自己的 VPN；在这里会绕开其他 VPN，导致其
// Fake-IP DNS 返回的 198.18.x.x 地址不可达。必须在任何节点启动前统一配置，
// 当前账号和候选账号保持同一策略。不能只设置 disableBindConnToInterface：
// SDK 每次收到 netmap 都会按服务端 capability 重置那个开关。
func configureEmbeddedRouting() {
	netns.SetEnabled(false)
}
