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
- Android JVM tests / APK build：KMP Android target 与 Compose 工程可编译。
- iOS XCTest：真实 KMP framework 桥接、坏 Event fail-closed、平台 effect、产品源码架构门禁。
- iPhoneOS Release 无签名构建：Device Kotlin/Native framework 和 Swift 产品编译。
- 真实 Gateway 人工回归：连接/重连、消息收发、History、Trajectory、Question、模型/权限和前后台。

## 9. 当前边界

- iOS WebSocket 仍在 Swift；这是有意保留的平台 transport，不是 Swift 业务逻辑回退。
- Swift UI 镜像为了 SwiftUI/UIKit 观察必须存在，但只能由 KMP Event 发布路径写入。
- Android 真实 transport、安全持久化和完整产品 UI 尚未完成，分别属于阶段 12 和 13。
