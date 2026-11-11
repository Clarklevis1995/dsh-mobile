# 连接器图标来源

iOS 连接器使用苹果系统 App 原图资源，随 App 静态打包；运行时无需联网取图，不使用私有图标查询 API。

| 资源名 | 连接器 | 来源 |
| --- | --- | --- |
| ConnectorCalendar | 日历 | [Apple Calendar 官方 App Store](https://apps.apple.com/us/app/calendar/id1108185179) artwork，2026-11-10 获取 |
| ConnectorReminders | 提醒事项 | 本机 Xcode iOS 27.2 Simulator Runtime，Reminders.app/AppIcon76x76@2x~ipad.png |
| ConnectorLocation | 位置 | 同一 Runtime 的 Maps.app/AppIcon76x76@2x~ipad.png；仅用作位置入口图标，能力依旧由 CoreLocation 提供 |
| ConnectorPhotos | 照片 | 同一 Runtime 的 Photos.app/AppIcon76x76@2x~ipad.png |
| ConnectorMusic | 音乐 | [Apple Music 官方 App Store](https://apps.apple.com/us/app/apple-music/id1108187390) artwork，2026-11-10 获取 |
| ConnectorHealth | 健康 | 同一 Runtime 的 Health.app/AppIcon76x76@2x~ipad.png |

日历和音乐图标通过 Apple 官方 iTunes Lookup 响应中的 artwork URL 获取。图标名称与图像版权属于 Apple。连接器不包含通讯录图标或权限。

## 侧栏入口

iOS 首页和会话侧栏统一使用用户选定的苹果候选第 05 款 `powercord`（带线插头、线框），采用 Regular 字重，沿用各侧栏的尺寸及前景色。此符号需要 iOS 17+，iOS 16 回退到 `powerplug`，保证最低支持版本仍显示有效插头图标。

Android 同步为独立绘制的带线插头矢量图，沿用与定时任务相同的 1.9 线宽、圆角端点和 24dp 画布，不使用苹果符号导出文件。两个平台各自复用统一的侧栏入口组件。
