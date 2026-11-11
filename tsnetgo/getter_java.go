// 变体 C：接口列表由 app 侧（Kotlin）枚举后经 JSON 传进来。
// 这是官方 Android app 与社区实现（expo-tailcat 等）采用的形态：
// 不依赖 netlink，也不依赖未合并的上游 PR；数据来自 Bionic/Java 允许的路径。
//
// 必须在 bringUp 之前调用 setInterfacesJSON，否则 netmon 仍会走 Go 标准库那条
// 被 Android 拒绝的 netlink 路径。
//
// JSON 形态（由 Kotlin 侧生成）：
//
//	[{"name":"wlan0","index":10,"mtu":1500,
//	  "flags":["up","broadcast","multicast","running"],
//	  "addrs":["192.168.1.5/24","fe80::12:34/64"]}]

//go:build android

package tsnetgo

import (
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"sync"

	"tailscale.com/net/netmon"
)

type reportedInterface struct {
	Name  string   `json:"name"`
	Index int      `json:"index"`
	MTU   int      `json:"mtu"`
	Flags []string `json:"flags"`
	Addrs []string `json:"addrs"`
}

var (
	reportedMu       sync.Mutex
	reportedIfaces   []netmon.Interface
	reportedGetterOn bool
)

// SetInterfacesJSON 注册 app 提供的接口枚举。
func SetInterfacesJSON(raw string) error {
	var incoming []reportedInterface
	if err := json.Unmarshal([]byte(raw), &incoming); err != nil {
		return fmt.Errorf("interfaces-json: %w", err)
	}
	if len(incoming) == 0 {
		return fmt.Errorf("interfaces-json: empty list")
	}
	list := make([]netmon.Interface, 0, len(incoming))
	for _, item := range incoming {
		iface := &net.Interface{
			Index: item.Index,
			MTU:   item.MTU,
			Name:  item.Name,
			Flags: parseInterfaceFlags(item.Flags),
		}
		var addrs []net.Addr
		for _, text := range item.Addrs {
			prefix, err := netip.ParsePrefix(text)
			if err != nil {
				continue
			}
			addr := prefix.Addr().Unmap()
			addrs = append(addrs, &net.IPNet{
				IP:   addr.AsSlice(),
				Mask: net.CIDRMask(prefix.Bits(), addr.BitLen()),
			})
		}
		list = append(list, netmon.Interface{Interface: iface, AltAddrs: addrs})
	}

	reportedMu.Lock()
	reportedIfaces = list
	first := !reportedGetterOn
	reportedGetterOn = true
	reportedMu.Unlock()

	if first {
		netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
			reportedMu.Lock()
			defer reportedMu.Unlock()
			if len(reportedIfaces) == 0 {
				return nil, fmt.Errorf("interfaces-json: no interfaces supplied")
			}
			return append([]netmon.Interface(nil), reportedIfaces...), nil
		})
	}
	return nil
}

// ReportedInterfaceCount 只用于诊断，不暴露接口内容。
func ReportedInterfaceCount() int {
	reportedMu.Lock()
	defer reportedMu.Unlock()
	return len(reportedIfaces)
}

func parseInterfaceFlags(names []string) net.Flags {
	var flags net.Flags
	for _, name := range names {
		switch name {
		case "up":
			flags |= net.FlagUp
		case "broadcast":
			flags |= net.FlagBroadcast
		case "loopback":
			flags |= net.FlagLoopback
		case "pointtopoint":
			flags |= net.FlagPointToPoint
		case "multicast":
			flags |= net.FlagMulticast
		}
	}
	return flags
}
