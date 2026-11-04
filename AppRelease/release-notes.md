## 功能更新

- 双端集成 Tailscale，支持在应用内登录、管理多个账号，并通过隧道连接私有网络中的网关。感谢共同作者 [@TraceRecursion](https://github.com/TraceRecursion) 的贡献。
- 双端新增 WebRTC 外网直连支持，在兼容网关上尝试直连，失败后自动回退 WebSocket；可查看实际连接方式与通道路径。
- 新增网络检测页面，展示往返延迟、探测丢包率、抖动及流量变化，便于排查连接问题。
- 新增日志导出与分享，支持按预设或自定义时间范围筛选、保存和分享本机应用日志。
- 改善冷启动和后台返回后的连接恢复，修复 Android 隧道就绪后仍需手动连接、iOS 冻结返回后连接失败或恢复缓慢的问题；修复切换主机及重新配对后自动连接设置被误关闭的问题。
- 优化执行耗时显示及会话订阅恢复，减少休眠、切换网络和重连造成的计时偏差与历史加载中断。
- 改善 Android 后台通知与前台服务衔接，优化设置页、日志导出页及主机和工作区图标的显示效果。
- Android 应用内更新可按设备架构选择对应安装包，减少下载体积，并保留通用安装包兼容旧版本。

## 必需插件

- 必须安装 [dsh-plugin-mobile-gateway](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) `v0.11.2` 或更高版本。
- 安装或升级后，需要完整退出并重新启动 `dsh web`。

```bash
dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
```
