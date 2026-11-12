# 双端移除 WebRTC（2026-11-12）

Android 和 iOS 当前统一使用 WebSocket。保留控制/会话双通道、旧网关单通道兼容、长期凭据配对、订阅队列、前后台健康探测与断线重连。

## 移除范围

- iOS：WebRTC SwiftPM 包、锁定记录、Xcode 链接及源文件引用、归档 dSYM 收集阶段、原生 PeerConnection/DataChannel 实现。
- Android：`io.github.webrtc-sdk:android`、AndroidRtcPeer、RTC 包装传输。应用直接组合两个 OkHttpGatewayTransport，交给既有 SplitGatewayTransport 管理双通道，保留节点代理出口策略。
- shared：RTC 升级状态机、平台 RTC 接口、连接策略、ICE 路径分析、固定 RTC 诊断、MGW 二进制分片编解码及配对后升级重连分支。
- 两端：RTC 路径界面、对应翻译、RTC 专用测试、测试网关和符号下载脚本。保留历史崩溃审计记录。
- 增补/调整 WebSocket 回归：配对不重新打开连接；忽略旧网关的升级能力；重新订阅和连接替换后旧帧不得进入新连接。

## 已通过的验证

| 验证 | 结果 |
| --- | --- |
| shared iOS Simulator 完整测试 | 620 项，0 失败、0 跳过 |
| Android 网关、传输与有界队列专项 | 36 项，0 失败 |
| iOS 控制通道、前后台恢复、网络恢复、连接替换及诊断生命周期 | 37 项，0 失败 |
| Android Debug APK | arm64-v8a、armeabi-v7a、x86、x86_64、universal 全部构建成功 |
| iOS 真机 Release | 全新 DerivedData 中构建成功；没有改变版本号或最低系统配置 |
| APK 内容扫描 | 5 个 APK 均无 WebRTC/jingle/peerconnection 原生库，DEX 均无 `org.webrtc` 类 |
| iOS 产物扫描 | 无 WebRTC.framework；主程序 `otool -L` 无 WebRTC 依赖，`nm -u` 无 RTC Objective-C 类引用 |
| Xcode/Gradle 配置及生产源码检查 | 无 WebRTC 依赖、导入或协商路径；`git diff --check` 通过 |

iOS 真机构建未签名，仅用于编译和依赖检查，没有上传 TestFlight，也不能直接作为正常设备的安装包。

## 全局检查的后续修复与限制

- Android 原先 298 项中有 1 项旧断言失败。经核对提交 `2029918d`，创建前选择具体工作区的限制已被新的会话工作区选择功能明确移除。已将测试更新为验证“全部会话不隐式选择具体工作区、无网关时不创建会话且不显示过时的限制提示、原会话保持不变”，未恢复旧的业务限制。
- 本地化缺口已修复：连接器、权限、地图默认名称、附件提示及分享目录文字均走本地化入口；补齐 27 个 iOS 目录项的中英日译文与 3 个 Android 英文目录项；删除没有调用方的共享硬编码标题目录。没有修改或放宽守卫，也没有给展示文字添加忽略标记。
- 完整复验已通过：Android 全量 299 项、shared 620 项，均无失败或跳过；本地化守卫随完整测试执行，没有使用排除参数；守卫自身的 12 项 Python 测试也通过。iOS 编译及本地化目录、连接器注册、连接器权限和地图相关 29 项测试通过，包含编译后资源的中英文解析及错误详情插值验证。
- iOS 既有测试使用 iOS 17 API，验证命令临时传入 `IPHONEOS_DEPLOYMENT_TARGET=17.0`；生产 Release 构建沿用工程最低版本设置。
- 未验证真实设备的前后台网络切换或 TestFlight 下发安装。新产物移除了旧签名崩溃所指向的 WebRTC 框架，但不声称已解释旧分发包签名异常的根因。

## 本地产物

- iOS：`build/websocket-only-ios/Build/Products/Release-iphoneos/DshMobile.app`
- Android：`androidApp/build/outputs/apk/debug/`
- 测试数量与产物逐项扫描记录：`build/verification/websocket-only-2026-11-12.json`

旧 Archive、旧 APK 以及系统 SwiftPM/Gradle 下载缓存属于历史制品，未清理或修改；它们不再被新构建引用。
