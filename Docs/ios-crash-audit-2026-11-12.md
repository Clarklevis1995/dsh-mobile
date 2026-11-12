# Xcode 崩溃逐项排查（2026-11-12）

后续处置：根据用户要求，两端已移除 WebRTC 依赖及传输实现，新构建不再包含该框架/原生库。验证范围与剩余问题见 [双端 WebSocket 验证记录](websocket-only-transport.md)。下面关于 WebRTC 的签名分析保留为旧版历史记录。

## 范围与结论

首轮审计读取本机 Xcode Organizer 缓存中的全部 8 个分组，检查原始报告、异常线程、Last Exception Backtrace、Binary Images，并与当前工作区代码核对。以下各项保留首次审计结论，后续修复进展见文末。未将 Organizer 分组标记为已解决。

数据目录：`~/Library/Developer/Xcode/Products/com.lichaofan.dshmobile/Crashes/Points/`。

去除重复过滤目录及同一 Incident Identifier 的报告副本后，共 35 份独立报告（macOS 格式报告使用报告文件名区分）。这些是本地已下载的样本，包含两周以外的旧报告，不等于线上总次数或受影响设备数，也不能据此计算崩溃率是否增长。

## 1. Room / ExclusiveLock（UZsd7tsXXukfeblpxE8rF）

- 样本：9 次；版本 1.7.10 (31)、1.7.11 (32)、1.7.14 (36)、1.8.0 (39)。最新样本为 11 月 9 日。
- 确认事实：数据库连接打开失败，经过 `DriverWrapper.openLocked`、`ConnectionPoolImpl`，最后由 `Kotlin_ObjCExport_ExceptionAsNSError` / `processUnhandledException` 终止进程。
- 当前代码仍有异常边界缺口：`SharedChatHistoryCache.statistics()`、`clear()`、`trimToBytes()` 等方法直接调用 DAO；没有声明 `@Throws(Exception::class)`。生成的 Objective-C 头文件明确写明只转换 CancellationException，其他未捕获 Kotlin 异常会致命终止。Swift 的 `try? await` 无法补救 Kotlin 导出层不允许传播的异常。
- 工厂 `create()` 有 `@Throws`，但 Room 实际打开连接发生在后续 DAO 使用时，因此只保护工厂不够。已有 `recover()` 只保护部分操作。
- 8 份样本是 Non UI 启动后数秒内发生；应验证锁屏后台启动、受保护文件不可访问、磁盘不足及迁移失败。报告没有保存底层异常消息，不能认定一定是文件保护问题，也不能因为标题叫 ExclusiveLock 就认定是锁竞争。
- 优先级：P1。补齐导出异常契约及缓存失败降级；注入数据库打开失败来验证 Swift 调用不会终止进程。
- 定位：`shared/src/commonMain/kotlin/com/clarklevis/dsh/shared/cache/SharedChatHistoryCache.kt:575`；`DeepSeekHarnessMobile/Core/ChatHistoryCache.swift:185`。

## 2. AVFAudio InstallTapOnNode（BvRMJ0q3BRqPSjrqWS62o）

- 样本：10 次，均为 1.7.9 (30)，日期 10 月 28 日至 11 月 9 日。
- 确认事实：`IOSVoiceRecorder.start` 在 `installTap` 调用中触发 Objective-C 异常，沿 AVAudioEngineGraph / AVAudioEngineImpl 抛出，最终 SIGABRT。
- 当前代码仍直接调用 `input.installTap`，并且调用位于 `engine.start()` 的 do/catch 外。即使移动到 Swift do/catch 内，Objective-C NSException 也不会因此变成 Swift Error。
- 现有格式检查只有采样率和通道数大于零，不能保证读格式到安装 tap 期间音频路由没有变化。路由/中断通知目前仅把 sessionPrepared 设为 false，没有直接串行重建音频图。
- 尚不确定：重复安装 tap、格式与硬件不一致、路由切换分别贡献多少；原报告没有异常 reason 文本，不能把其中之一写成已证实根因。
- 优先级：P1。核对 tap 生命周期和路由变化；在可恢复的音频 API 边界捕获异常并完整清理失败状态，验证断开蓝牙、接听电话和连续取消/重录。
- 当前定位：`DeepSeekHarnessMobile/Core/VoiceInputAdapter.swift:344`（旧报告行号为 323）。

## 3. SwiftUICore EnvironmentObject.error（D054CMu4iiTLZAo5W_TbZE）

- 样本：7 次，为 macOS 上运行的应用；版本 1.7.8 (29)、1.7.10 (31)、1.7.11 (32)、1.7.13 (34)。最新样本为 11 月 2 日。
- 确认事实：`GatewaySwitcherSheet.displayedProfiles` 读取缺失的 EnvironmentObject，触发 SwiftUI assertion。
- 当前实现已改为 `@ObservedObject var hosts: MultiGatewayStore`，创建 sheet 时显式传入 `GatewaySwitcherSheet(hosts: hosts)`；原先依赖 sheet 继承环境对象的路径已经改变。
- 状态：当前代码已有对应修正，但不能据此宣称线上全部解决。需要在 macOS 再次打开/关闭网关切换 sheet，并确认修正已进入实际分发版本。
- 优先级：P2（验证已有修正）。
- 定位：`DeepSeekHarnessMobile/Views/GatewaySwitcherView.swift:27`。

## 4. AVFAudio InstallTapOnNode 另一系统签名（ClZOZqxZRgwPrtXfcUdPnQ）

- 样本：1 次，1.7.15 (38)，11 月 7 日。
- 确认事实：同样是 `IOSVoiceRecorder.start → installTapOnBus → InstallTapOnNode → NSException`；此系统的函数签名没有 NSError**，因此被 Organizer 分为另一组。
- 状态：与第 2 项属于相同的录音启动故障类别，可以统一排查和验证；尚不能证明每次触发条件完全相同。
- 优先级：P1，随第 2 项处理。

## 5. UIFoundation（CEVDQfXx2_8pgBJ8oSqYU）

- 样本：2 个独立 incident（3 个不同文件名，其中两个文件对应同一 incident），均为 1.8.0 (39)、iOS 17.0 (21A329)，11 月 9 日和 10 日。
- 确认事实：主线程 Objective-C 异常，调用链经过 Foundation、UIFoundation、UIKitCore，最终回到系统事件循环；异常回溯中没有业务方法作为直接调用者。
- 限制：大部分系统帧未符号化，也没有异常 reason，当前证据不足以定位到某个 UITextView、NSRange、富文本属性或具体用户内容。不能仅因 UIFoundation 标题就认定是 Markdown 解析器问题。
- 下一步：取得此 OS build 对应的系统符号/完整异常原因，核对输入框、用户消息文本视图及文本选择/布局操作；构造长文本、组合字符及编辑期间更新的回归场景。
- 优先级：P1（先补诊断，再定位修复）。

## 6. WebRTC + 2069686488（BGDhAm-r7V7V8FUD8udZz0）

- 样本：1 次，1.7.15 (38)，11 月 8 日。
- 确认事实：主线程抛出异常。报告把系统帧全部错误归属为 WebRTC，偏移量甚至超过 2 GB。
- 反证：Binary Images 中 WebRTC 实际位于 `0x109064000…0x109b8ffff`；首个所谓 WebRTC 帧地址为 `0x1846328d8`，实际落在 CoreFoundation 镜像范围。后续大量地址落在 UIKitCore。
- 结论：标题和部分解析出的函数名不可信，不能据此判断 WebRTC C++ 内存错误或网络传输问题。原始异常栈更接近系统 UI 路径，但具体触发点尚不确定；也不能直接与第 5 项合并。
- 优先级：P1（校正符号归属并恢复异常原因）。保留原始报告，符号处理写入新副本，不能修改原件。

## 7. NO_CRASH_STACK（CyXFPzQVJOKcRRgcRbcpOn）

该组包含两个不同问题，必须拆开跟踪。

### 7a. 旧版代码预览正则递归

- 样本：2 次，1.7.3 (21)，10 月 14 日和 16 日。
- 证据：崩溃线程超过 4,000 帧，反复进入 Kotlin 正则匹配，最终经过 `WorkspaceCodePreviewSupport.tokenize → prepare → WorkspaceCodePreview.loadDocument`；EXC_BAD_ACCESS / SIGBUS。
- 当前代码已包含字符串/注释扫描器及数字边界扫描；提交 `79b31f35`（10 月 15 日）专门调整了高亮分词及压力测试。新报告发生在旧构建，不足以认定修正无效。
- 优先级：P2，确认旧递归路径已消除，并复跑大文件分词验证。

### 7b. 1.8.0 启动时框架签名无效

- 样本：2 次，1.8.0 (39)，11 月 11 日 09:55:51 和 09:55:58，iOS 16.1，同一硬件型号。
- 明确终止原因：`DYLD 9`，`WebRTC.framework/WebRTC` 的 `code signature invalid`；报告注明 terminated at launch。应用入口和业务恢复逻辑尚未获得运行机会。
- 不能认定是 TestFlight 分发损坏：这两份报告没有其他 TestFlight 报告中的 Distributor ID 字段，实际安装来源尚未确认。
- 优先级：P1（若仍向 iOS 16 分发该制品，则作为发布阻断项）。核对实际出问题的安装包、框架签名、签名后的二进制修改及 iOS 16.1 真机启动；仅验证本地另一份 Archive 不足以证明用户安装包有效。

## 8. WebRTC + 2198810728（D5GXGwsg0nsFEqmaQzX4Ot）

- 样本：1 次，1.8.0 (39)，11 月 11 日 14:05:37。
- 确认事实：仍然存在错误的符号归属。WebRTC 镜像范围为 `0x1071f8000…0x107d23fff`，异常首帧 `0x18a2eb068` 实际属于 CoreFoundation。
- 业务调用链仍可辨认：`IOSVoiceRecorder.start`（344 行）及 VoiceInputAdapter 启动闭包；上游地址 `0x1c993fee0`、`0x1c994a4a8`、`0x1c99bdd4c` 均落在 AVFAudio 镜像内。
- 结论：属于录音启动异常类别，应与第 2、4 项共同跟踪，不能当作已证实的 WebRTC 网络崩溃。
- 优先级：P1。

## 建议处理顺序与验收

1. 框架签名：确认安装来源和制品；在受影响系统启动，签名检查覆盖最终分发包。
2. Room：补全 Kotlin/Swift 异常边界，数据库打开失败不再结束进程；缓存不可用时仍能进入在线界面。
3. 录音：把三组报告作为同一故障类别排查，覆盖路由变化、中断和快速重录；失败后能再次录音。
4. 系统文本排版：补齐系统符号/异常原因再修复，避免根据无效栈猜测改动。
5. 已有修正：验证 macOS 网关切换与大文件代码预览，并核对实际发布构建。

首轮审计只进行了报告与代码审查，没有重现所有崩溃，也没有执行代码测试；该阶段不应将任何分组标记为“已修复”。

## 第一批修复与验证

- **Room 异常边界**：所有 SharedChatHistoryCache 对外 suspend 接口声明 `@Throws(Exception::class)`，生成的 Objective-C 头文件已确认改为转换 Exception。保留协程取消语义，Swift 现有降级处理现在能接到数据库异常；后台维护也增加错误恢复，避免维护协程未处理异常终止进程。
- **原生录音异常**：新增仅用于音频调用的 Objective-C NSException 边界，安装 tap、准备/启动和停止引擎的异常转换为 Swift Error。校验输入/输出硬件格式；失败后清理 capture、observer、session 并重建引擎；路由变化、媒体服务重置通知使当前录音失败，而不是继续使用旧图。
- **真实边界回归**：Swift 测试将 SQLite 路径指向目录，让 Room 在首次查询时真正打开失败；statistics 和 clear 均可捕获错误，平台缓存包装器返回 nil，测试进程继续运行。录音测试实际抛出 NSException 并验证后续操作与 Swift Error 传播。
- **已有修正验证**：独立呈现 GatewaySwitcherSheet、不注入 EnvironmentObject 的测试通过。
- **测试结果**：648 项共享 Kotlin 测试通过；首轮 iOS 选择集执行 49 项，缓存 28 项与网关切换 1 项全部通过，录音 20 项中的 19 项通过。另一项输入框布局测试有 6 个高度/位置断言失败，需要单独跟踪，不能把这轮原生测试写成全绿。本次未修改输入框布局实现。
- **最终回归**：最终代码再次运行缓存 28 项、录音 19 项和网关切换 1 项，共 48 项通过；明确排除了上面已记录失败的输入框布局用例。Android 共享代码编译通过。未进行真机蓝牙/电话中断或 TestFlight 下发包验证，模拟异常捕获通过不等于所有硬件触发条件均已消除。
- **测试构建限制**：仓库既有测试使用 iOS 17 API 却未全部声明 availability；执行测试时以命令行临时设置 `IPHONEOS_DEPLOYMENT_TARGET=17.0`，产品最低系统配置没有改变。
- **TestFlight 签名问题尚未修复**：用户确认来源为 TestFlight。已找到 11 月 7 日的 1.8.0 (39) 原始 xcarchive；在能够读取系统证书信任信息的环境中执行 `codesign --verify --deep --strict --verbose=2`，应用及嵌套代码验证通过。WebRTC 的 LC_BUILD_VERSION 最低系统为 iOS 12.0。因此不能归因为本地漏签或框架最低版本高于 16.1；仍需检查 TestFlight 实际下发制品并在 iOS 16.1 真机复验，未盲目更换 SDK。
- **UIFoundation / 主线程未知异常**：没有足够符号及异常原因，仍待定位；本批不声称已解决这些分组。
