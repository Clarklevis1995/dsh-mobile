# DeepSeek Harness Mobile 架构

## 1. 架构目标

DeepSeek Harness Mobile 是 Kotlin Multiplatform 项目：KMP `shared` 模块是业务状态与纯逻辑的唯一来源，iOS 使用 SwiftUI/UIKit，Android 使用 Jetpack Compose。

当前 iOS 客户端通过 WebSocket 连接 Mobile Gateway，承载 Workspace、Session、Conversation、Trajectory、History、Human Question 和 Session Control 等能力。Android 已有 KMP/Compose 验证工程，真实 Gateway 与产品 UI 将在后续阶段接入。

## 2. 核心原则

- KMP Store 保有 SessionList、Question、SessionControl、Conversation、Trajectory 和 History 的业务状态。
- UI 向 KMP 发送 Intent；KMP 通过可取消订阅向平台发送有序 Event。
- Event 同一事务内携带增量 state patch 与一次性 effect descriptor。
- Swift 不执行平行 Reducer；只校验 Event，更新可重建 UI 镜像，并执行平台 effect。
- Event 丢序、schema 未知、Intent 不匹配或 patch/effect 语义不一致时，Adapter 必须在 UI 发布和 I/O 之前 fail-closed。
- WebSocket、UserDefaults/Keychain、文件与图片、UIKit、应用生命周期和后台任务是平台能力，不进入 `commonMain`。

## 3. 单向数据流

```text
┌───────────────────────────────────────────────────────────┐
│ SwiftUI / UIKit                                           │
│ 读取 AppStore 发布的可重建 UI 镜像，产生用户 Intent          │
└────────────────────────────┬──────────────────────────────┘
                             │ Intent
┌────────────────────────────▼──────────────────────────────┐
│ AppStore + KMPSharedAdapter (@MainActor)                   │
│ Intent 转发、Event schema/sequence/语义校验、UI 镜像发布  │
└────────────────────────────┬──────────────────────────────┘
                             │ 粗粒度 K/N 桥接
┌────────────────────────────▼──────────────────────────────┐
│ KMP shared/commonMain                                      │
│ Store + Reducer + Projection + History Sync               │
│ 唯一业务状态 → 有序增量 Event(state patch + effect)          │
└────────────────────────────┬──────────────────────────────┘
                             │ effect descriptor
┌────────────────────────────▼──────────────────────────────┐
│ Swift 平台 effect 执行器                                  │
│ GatewayClient / Preferences / Files / Images / Background │
└────────────────────────────┬──────────────────────────────┘
                             │ WebSocket / 平台 API
                    Mobile Gateway / iOS runtime
```

Swift 中的 `@Published` 数据是 KMP 状态的平台观察镜像，不是第二份业务状态源。它可以从 KMP 初始 snapshot 和后续 Event 重建，也不得由 Swift 业务逻辑直接改写。

## 4. 模块边界

### 4.1 `shared/commonMain`

- `protocol/`：Gateway DTO、JSON 平台无关表示和 wire decoder。
- `domain/`：SessionList、Question、SessionControl Reducer。
- `projection/`：Conversation 和 Trajectory 唯一投影逻辑。
- `sync/`：History 状态机、分页合并与 live tail 去重。
- `facade/`：粗粒度 Store、Intent 入口、MVI Event 订阅与结构化错误边界。

KMP 不直接执行网络、磁盘、Keychain、UIKit 或 Android framework I/O。

### 4.2 iOS `Core`

- `AppStore.swift`：面向 View 的平台 State Holder；转发 Intent、发布 KMP change，协调平台 effect。
- `KMPSharedAdapter.swift`：Kotlin/Native 粗粒度桥接；管理订阅，校验 Event 原子性并保持 fail-closed。
- `KMPDomainIntents.swift`：Swift UI/Router 到 KMP 的平台 Intent 值。
- `ConversationTimeline.swift`：只保留 UIKit/SwiftUI 显示 DTO 和发布节流，不包含对话投影算法。
- `GatewayClient.swift`：`URLSessionWebSocketTask` 连接、重连与平台发送。
- `GatewayFrameRouter.swift` / `GatewayModels.swift`：平台 wire 入口、弱类型 JSON 与 Intent 路由。
- `AppPreferences.swift`、图片/附件组件、`AgentBackgroundExecutionController.swift`：平台服务。

### 4.3 UI 和 Android

SwiftUI View 仅读取 `AppStore` 状态并调用语义化 Intent 方法。Conversation 和 Trajectory 的高频更新经 display-link/FIFO 批处理，不对每个 token 复制完整列表。

`androidApp` 使用 Compose 验证 KMP Store 和增量协议。真实 OkHttp WebSocket、DataStore/Keystore、附件和生命周期平台实现属于阶段 12。

### 4.4 本地化文本

- 产品文案：iOS `DeepSeekHarnessMobile/Resources/Localizable.xcstrings`，Android `AndroidStrings.english`；中文 key 即源语言，英文为目标语言。
- KMP 桥接诊断：iOS `Resources/Diagnostics.xcstrings`，只由 `KMPSharedAdapter` 的 `table: "Diagnostics"` 读取，与产品文案分表，避免内部不变量诊断污染用户目录。
- 权限说明：`Resources/InfoPlist.xcstrings`，必须覆盖 `Info.plist` 中全部 `*UsageDescription`。
- 共享层不生成用户文案：`ToolActivitySummary` 只给出 `kind` 与计数，iOS/Android 各自选择标签和数量语法，禁止解析中文文本。

## 5. 关键 Use Case

### 5.1 发送消息并接收 Agent 回复

1. SwiftUI 调用 `AppStore.send` 产生用户 Intent。
2. AppStore 让 KMP SessionList/History 状态机处理本地状态，并交由 `GatewayClient` 执行 WebSocket 发送。
3. Gateway 返回 `sent` / `event` 帧；Router 将帧归一化为 KMP Intent。
4. KMP Conversation/Trajectory/History/SessionList Store 从唯一业务状态生成有序增量 Event。
5. Swift Adapter 先验证 schema、sequence、Intent 和 patch，再发布 UI change。
6. Conversation 文本 delta 与 Trajectory operation 在下一次 MainActor FIFO/display-link 批次刷新 UI。
7. 最终 `assistant/message` 由 KMP 替换临时流式消息，Swift 不再重复执行归并算法。

### 5.2 Human Question 与 Session Control

Question request 进入 `SharedQuestionStore`，UI answer/cancel 作为 Intent 返回 KMP。KMP 校验顺序、单/多选和状态，仅在合法转移中产生一次 Gateway effect。Swift 执行 effect，并精确管理 iOS 后台 allowance。

模型、权限、Context Usage、Stats、Agent Presets 和默认配置的 active/queued target、generation token 及迟到响应隔离由 KMP 状态机持有。低频控制面使用分片 patch，只跨桥传输改变的 session/global section。

## 6. 增量协议与性能边界

- Conversation：`insert` / `append-text(delta)` / `remove`；历史基线和乱序修正才 replace。
- Trajectory：`insert` / `remove` / `move` / `replace` / `update`，流式 update 仅携带 delta 和新 records。
- History：live 尾部使用 append/upsert，分页基线使用 replace。
- SessionControl：按 session/global section 分片 upsert/remove，无状态变化时不发 payload。
- Swift `Dictionary` CoW 和受影响整属性发布仍可能是本地 O(n)；当前优化目标是避免跨 Kotlin/Native 边界的无关全量 JSON 复制。

## 7. 错误、线程与安全

- KMP facade 捕获 `Throwable` 并返回结构化错误，禁止未声明异常跨 Kotlin/Native 边界。
- Swift Adapter 与 AppStore 受 `@MainActor` 保护；KMP Event 序列严格单调。
- 运行期坏 Event 会使对应 Adapter 永久 fail-closed，防止 KMP/Swift 状态分叉和错误 I/O。
- Gateway 端点保存在 UserDefaults，当前局域网协议不带鉴权；公网部署必须使用 TLS 和鉴权。
- Provider 凭据由 Harness Host 管理，App 不保存 Provider API Key。

## 8. 测试门禁

- `shared:allTests`：Reducer、Projection、History、MVI Event、增量 payload 和异常原子性。
- `scripts/verify-localizations.py`：校验 `Localizable`（产品文案）、`Diagnostics`（KMP 桥接诊断）、`InfoPlist`（权限说明）三份 String Catalog 与 Android `AndroidStrings` / `values-en` 的译文完整性、占位符一致性和静态调用覆盖；传 `--swift-extractions <DerivedData 中间产物>` 时还会用编译器抽取结果校验插值 key 及所在 table。CI 执行静态部分。
- `scripts/test_verify_localizations.py`：上述校验脚本的单元测试。
- Android JVM tests / APK build：KMP Android target 与 Compose 工程可编译。
- iOS XCTest：真实 KMP framework 桥接、坏 Event fail-closed、平台 effect、产品源码架构门禁。
- iPhoneOS Release 无签名构建：Device Kotlin/Native framework 和 Swift 产品编译。
- 真实 Gateway 人工回归：连接/重连、消息收发、History、Trajectory、Question、模型/权限和前后台。

## 9. 当前边界

- iOS WebSocket 仍在 Swift；这是有意保留的平台 transport，不是 Swift 业务逻辑回退。
- Swift UI 镜像为了 SwiftUI/UIKit 观察必须存在，但只能由 KMP Event 发布路径写入。
- Android 真实 transport、安全持久化和完整产品 UI 尚未完成，分别属于阶段 12 和 13。

## 10. 按住说话与 ASR

`SharedVoiceInputStore` 是 iOS 和 Android 的唯一语音业务状态源。键盘提示、录音阶段、上滑取消、时长/数据上限、音量归一化与平滑、官方插件能力和模型准备、请求 ID、超时、跨会话隔离及草稿冲突策略均在 `shared/commonMain`。`VoiceInputProtocol` 负责官方语音目录解码和 PCM16 WAV 封装。

两端 UI 只判断原生手势、读取 snapshot、绘制由真实音量驱动的底部粒子遮罩并发送 Intent。平台 Adapter 只执行 Store 返回的 effect：系统权限、单声道 16 kHz PCM16 采集、时钟和网络发送。Android 使用 AudioRecord，iOS 使用 AVAudioEngine/AVAudioConverter；取消、退到后台、离开对话或失去连接会停止录音，过期的录音回调不得再次启动 ASR。

松手后通过网关的 `speech-input-v1` 调用 DSH 官方 `speech` Remote API。客户端不内置语音识别模型，模型位于运行官方插件的主机。识别成功只回填草稿，不发送 Agent 消息；识别期间用户修改过草稿时，先显示识别文字，由用户确认插入。官方插件未启用、模型未准备好或网关能力缺失时显示可恢复提示，禁止静默录音。

语音原始帧直接交给公共 Store，避免语音 `selection` 与聊天模型 `selection` 混用；Android Runtime 精确关联 requestId，整段转写使用独立的 185 秒请求上限（共享状态机 180 秒超时并取消）。订阅流继续由 Store 校验 ID，拒绝已退出会话、断线或取消后的迟到结果。

语音遮罩覆盖整个底部输入区域，包括统计条和工具栏。共享 snapshot 保留固定长度的真实音量历史与粒子收束强度；两端只负责蓝色/浅红色渐变、静音漂移和向柱状波形聚拢的图形插值。转写成功或确认插入后，共享 Store 返回 `focusEditor` effect，由平台使原文本控件取得焦点。文本控件在录音和转写期间保持挂载，短按与中文输入法均保留原生编辑流程。

缺少网关能力、官方语音插件或模型初始化时，共享层只生成 `alertMessage`，不会改变草稿或进入录音阶段。iOS 复用原生 Alert，Android 复用 `DshAlertDialog`；提示用户在桌面端升级网关、启用官方语音插件并完成初始化。输入区不再插入升级提示或准备按钮。
