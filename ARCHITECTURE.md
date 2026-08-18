# DeepSeek Harness Mobile — 架构文档

## 1. 项目概述

`DeepSeekHarnessMobile` 是一个原生 SwiftUI iOS 客户端，用于连接 `dsh-plugin-mobile-gateway`（DeepSeek Harness 的移动端网关插件）。App 通过 WebSocket 协议与运行在开发机/服务器上的 Harness Host 通信，实现工作区管理、会话列表、实时对话流、Agent 执行轨迹（Trajectory）查看以及部署级设置管理。

- 平台：iOS（SwiftUI + UIKit 互操作），要求 iOS 26 的 Liquid Glass 效果有专门的降级路径以兼容旧系统。
- 通信协议：单一 WebSocket 连接（默认 `ws://<host>:3080/ws/mobile`），JSON 帧双向通信。
- 定位：Harness 桌面 Web UI 的移动端"瘦客户端"，功能上是 Web UI 的三栏（工作区 / 对话+轨迹 / 设置）在移动端的原生映射，当前协议版本（v0.1.6+）尚未覆盖全部 Web UI 的写操作能力（见第 7 节）。

## 2. 技术栈

| 层面 | 技术 |
|---|---|
| UI 框架 | SwiftUI（`NavigationStack`、`ScrollView`/`LazyVStack`、`Menu`、`sheet` 等），少量 `UIViewControllerRepresentable` 用于恢复系统手势 |
| 状态管理 | `ObservableObject` + `@Published`（单一 `AppStore` 作为全局状态容器），`@EnvironmentObject` 注入 |
| 网络 | `URLSessionWebSocketTask` 原生 WebSocket，无第三方网络库 |
| 数据模型 | `Codable` + 自定义 `JSONValue`（动态 JSON 枚举）应对协议弱类型字段 |
| 持久化 | `UserDefaults`（端点地址、会话列表缓存、滚动位置锚点等轻量状态） |
| 第三方依赖 | `swift-markdown-ui`（vendored 于 `Vendor/`，用于渲染助手消息 Markdown） |
| 并发 | Swift Structured Concurrency（`Task`、`Task.detached`、`@MainActor`）用于后台 JSON 解码/历史投影，避免阻塞主线程 |
| 测试 | XCTest（`DeepSeekHarnessMobileTests`），当前覆盖网关协议解码 |

## 3. 目录结构

```
dsh-mobile/
├── DeepSeekHarnessMobile/
│   ├── App/
│   │   └── DeepSeekHarnessMobileApp.swift   # @main 入口，注入 AppStore
│   ├── Core/
│   │   ├── AppStore.swift        # 全局状态容器 + 业务逻辑（Store/ViewModel 角色）
│   │   ├── GatewayClient.swift   # WebSocket 连接与帧收发
│   │   ├── GatewayModels.swift   # 协议数据模型、JSONValue、事件归一化
│   │   └── Theme.swift           # 配色、深海背景等主题元素
│   ├── Components/
│   │   └── Glass.swift           # 可复用 UI 组件（玻璃拟态、连接状态点、品牌标识）
│   ├── Views/
│   │   ├── RootView.swift        # 导航根：NavigationStack + 路由
│   │   ├── WorkspaceView.swift   # 首页：工作区选择、会话列表、目录浏览创建工作区
│   │   ├── ConversationView.swift# 对话页：消息流、Composer、Segmented（对话/轨迹）
│   │   ├── TrajectoryView.swift  # Agent 执行轨迹时间线视图
│   │   └── SettingsView.swift    # 设置页：网关连接、默认 Agent 预设/权限、Host 信息
│   └── Resources/
│       ├── Assets.xcassets/
│       └── Info.plist
├── DeepSeekHarnessMobileTests/
│   └── GatewayProtocolTests.swift
├── Vendor/
│   └── swift-markdown-ui/        # vendored 第三方 Markdown 渲染库
├── Design/
│   └── design-spec.md            # 视觉设计规范
├── Docs/
│   └── exploration.md            # Harness/Web UI 功能调研与协议映射记录
└── scripts/                       # 辅助脚本（Ruby）
```

## 4. 分层架构

App 采用轻量的**单向数据流 + 集中式 Store** 模式（近似 MVVM，但不按页面拆分 ViewModel，而是用一个跨页面共享的 `AppStore`）：

```
┌─────────────────────────────────────────────────────────┐
│                        Views 层                          │
│  RootView → WorkspaceView / ConversationView / Settings  │
│             / TrajectoryView（均通过 @EnvironmentObject   │
│             读取 AppStore，不直接持有网络状态）              │
└───────────────────────────┬───────────────────────────────┘
                             │ 读取 @Published 状态 / 调用意图方法
┌───────────────────────────▼───────────────────────────────┐
│                    AppStore（Core 状态层）                  │
│  - 持有全部 UI 可观察状态（会话、工作区、事件、通知…）           │
│  - 暴露意图方法：connect() / open() / send() / loadHistory()│
│  - 处理网关下行帧 → 状态变更（handle(_ frame:)）             │
│  - 历史分页、事件去重合并、对话投影（后台 Task 处理）           │
└───────────────────────────┬───────────────────────────────┘
                             │ 收发 JSON 帧
┌───────────────────────────▼───────────────────────────────┐
│                  GatewayClient（传输层）                    │
│  - URLSessionWebSocketTask 生命周期管理（连接/重连/断开）     │
│  - 发送帧的字典→JSON 编码                                   │
│  - 接收循环 + 后台线程 JSON 解码（GatewayWireDecoder）       │
└───────────────────────────┬───────────────────────────────┘
                             │ WebSocket (ws:// / wss://)
                    dsh-plugin-mobile-gateway（远端）
```

## 5. 核心模块详解

### 5.1 `GatewayClient`（`Core/GatewayClient.swift`）
- 封装 `URLSessionWebSocketTask`，`maximumMessageSize` 放宽至 64MB 以容纳大体量历史分页。
- 提供协议动作方法（`requestWorkspaces`、`sendMessage`、`selectModel`、`setPermission`、`requestHistory` 等），统一走 `send(_:)` 将字典编码为 JSON 字符串发送。
- `receiveLoop` 在后台优先级 `Task.detached` 中解码帧，避免历史大包解码阻塞主线程；解码失败会构造一个 `error` 帧回传而不中断连接。
- 失败自动重连：`handleFailure` 在 2 秒后重试，仅当 `wantsConnection` 仍为真。
- 通过 `onFrame` 闭包把所有下行帧转交给 `AppStore.handle(_:)`。

### 5.2 `GatewayModels`（`Core/GatewayModels.swift`）
- `JSONValue`：手写的动态 JSON 枚举（`string/number/bool/object/array/null`），用于承载协议中类型不固定的字段（如 `projections`、`arguments`），并提供 `decode<T>` 转换到具体 `Codable` 类型。
- `GatewayFrame`：单一大结构体承载所有可能的下行帧字段（因为协议用 `kind` 字段做多态区分，但 Swift 侧选择用一个"超集"结构体简化解码）。
- `GatewayWireDecoder`：修正网关已知的协议缺陷——事件广播帧缺失 `kind: "event"` 判别字段时，依据 `sessionId`/`seq`/`event` 字段特征自动补全。
- `RawSessionEvent.normalized(sessionId:)`：将服务端原始事件（`user/message`、`assistant/chunk`、`assistant/message`、`tool/call`、`tool/result`、`tool/code-dispatch*`、`turn/*`、`step/*`、`session/title` 等）归一化为统一的 `GatewayEvent` 结构，供 UI 层消费，未知类型也会兜底保留 `type` 以保证向前兼容。
- 其余模型：`GatewayWorkspace`、`SessionSummary`、`GatewayModelCatalog`、`GatewaySessionPermissions`、`GatewayContextSnapshot`、`GatewaySessionStatsSnapshot` 等，均为服务端投影数据的本地镜像。

### 5.3 `AppStore`（`Core/AppStore.swift`，`@MainActor` `ObservableObject`）
这是全局唯一的状态与业务逻辑中心，职责包括：

- **连接与初始化**：`connect()`、`refreshRemoteState()`（拉取 workspaces/sessions/host）、`refreshDefaultConfiguration()`（agent-presets/defaults/default-model）。
- **会话管理**：`open(_:)`、`startNewSession()`、`send(_:)`、`upsertSession`、未读/运行中状态维护、按 `lastActivity` 排序。
- **历史加载与分页**（较复杂的一块逻辑）：
  - 按 `beforeSeq` 游标向旧分页拉取，带字节预算（4MB/页）与超时兜底（20s）。
  - 用 `historyRequestTokens` 做请求令牌校验，防止竞态覆盖。
  - 后台批量归一化事件、与既有缓存按 `seq` 合并去重排序，分批（800/批）写入并让出主线程（`Task.sleep(10ms)`）避免长历史卡顿。
  - 首次渲染时增量展开可见区（`visibleItemStart` 逐步前移），减少 Markdown 布局一次性铺开的成本。
  - 循环游标检测，避免网关返回重复 `nextBeforeSeq` 导致死循环。
- **实时事件合并**：`merge(_:)` 将 `event` 帧并入 `events[sessionId]`，触发 `applyEvent` 更新会话标题/运行状态/权限/模型，并 debounce（40ms）触发 `scheduleConversationProjection` 重新投影为 `ConversationItem` 供 UI 渲染。
- **会话控制面**：模型选择、权限预设、上下文用量、会话统计，均有独立的 loading token + 12s 超时兜底（`sessionControlRequestTokens`/`defaultConfigurationRequestTokens`）。
- **工作区与目录**：`browseDirectories`、`createWorkspace`，用于在远端文件系统上新建工作区。
- **持久化**：会话列表、对话滚动锚点、手动定位标记通过 `UserDefaults` 落地，重启后恢复。
- **下行帧分发**：`handle(_ frame:)` 是一个大 `switch`，按 `frame.kind` 分派到各状态更新逻辑，并生成 `GatewayNotice` 用于开发期协议可视化提示。

### 5.4 Views 层
- `RootView`：`NavigationStack` 承载路由（`.conversation` / `.settings`），并通过自定义 `UIViewControllerRepresentable` 恢复系统边缘返回手势（因隐藏了系统导航栏）。
- `WorkspaceView`：首页，工作区切换菜单、会话搜索、会话列表、目录浏览创建工作区的 Sheet。
- `ConversationView`：对话与轨迹的分段视图（Picker 切换），包含流式消息渲染、历史加载横幅、"加载更早记录"按钮、Composer、滚动位置精细控制（区分自动滚动/用户手动定位并持久化锚点）。
- `TrajectoryView`：将会话事件投影为 Agent 执行轨迹（Turn/Step/Tool 时间线），带缩略时间线画布、详情 Sheet。
- `SettingsView`：网关端点配置、连接控制、默认 Agent 预设/权限选择、Host 元信息展示。

### 5.5 Components / Theme
- `Glass.swift`：`glassSurface` ViewModifier，iOS 26+ 使用系统 Liquid Glass（`glassEffect`），更低版本降级为 `ultraThinMaterial` + 描边 + 阴影模拟；`ConnectionDot`（连接状态指示）、`HarnessMark`（品牌 Logo）。
- `Theme.swift`：`DSHColor` 配色集合，`DeepOceanBackground`（程序化绘制的深海网格背景，用作首页视觉背景）。

## 6. 数据流示例：发送一条消息

1. `ConversationView` 调用 `store.send(text)`。
2. `AppStore.send` 校验连接状态与文本非空，设置 `waitingForNewSession`，调用 `gateway.sendMessage(text:sessionId:workspaceId:)`。
3. `GatewayClient.send` 将字典编码为 JSON 并通过 WebSocket 异步发出。
4. 网关返回 `sent` 帧 → `AppStore.handleSent` 建立/确认 `sessionId`，触发 `gateway.subscribe(sessionId:)` 订阅该会话事件，并刷新会话控制面（模型/权限/上下文）。
5. 后续 Agent 执行产生的 `event` 帧持续到达 → `handleLiveEvent` → `merge` → 更新 `events`/`renderedConversationItems` → SwiftUI 视图自动重渲染。

## 7. 已知边界（来自 `Docs/exploration.md`）

当前网关协议（v0.1.6 起）已支持：`workspaces`、`sessions`、分页 `history`、`search`、`host`、`directories`、`workspace-create`，以及模型/权限/上下文用量/会话统计等查询接口。

尚未覆盖 Web UI 的写操作能力（后续演进方向）：
- 会话取消、Approval 审批应答、Plan 审阅
- 队列编辑（queue/steer 模式的精细控制）、附件上传
- 会话重命名/Fork/归档
- 鉴权的远程访问（当前为局域网直连 `ws://`，无认证层）

## 8. 测试

`DeepSeekHarnessMobileTests/GatewayProtocolTests.swift` 覆盖网关协议帧的解码正确性（包括 `GatewayWireDecoder` 对缺失 `kind` 字段的事件帧的修正逻辑）。UI 层暂无自动化测试。

## 9. 安全与配置说明

- 连接地址（`ws://` / `wss://`）保存在 `UserDefaults`，未做加密存储；当前协议本身不带鉴权字段，仅适用于开发者本机/局域网场景，若需暴露到公网应在网关侧补充 TLS（`wss://`）与身份鉴权，避免未授权访问执行 Agent 操作。
- App 不持有任何密钥/凭据，Provider/Model 凭据完全由 Harness Host 侧管理。
