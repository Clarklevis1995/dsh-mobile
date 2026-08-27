# AppStore 拆解与 KMP 迁移计划

> 状态：进行中  
> 当前分支：`feature/kmm`  
> 创建日期：2026-08-24  
> 最近更新：2026-08-27
> 当前任务：暂停，等待阶段 9.1～9.4 iOS 人工核验；通过后执行阶段 9.5

## 使用说明

这份文档是跨会话续作的唯一进度台账。开始新会话时：

1. 先读取本文档和 `git status --short`。
2. 从“当前任务”继续，不重复已勾选的工作。
3. 每完成一个可独立验证的任务，立即将 `[ ]` 改为 `[x]`。
4. 同步更新“当前任务”“最近更新”和“变更记录”。
5. 只有代码、测试和验收标准全部通过，才能勾选对应任务。

## 目标

在不改变现有 iOS 行为和原生 UI 的前提下，将 `AppStore` 从集中式巨型对象逐步拆成：

- 可持久化配置与缓存接口；
- 无平台依赖的领域 State 和纯 Reducer；
- 网络、超时、历史同步等 Effect/Coordinator；
- iOS 生命周期、Keychain、UIKit/SwiftUI 等平台适配；
- 最终可迁入 KMP `commonMain` 的协议和业务模块。
- 让 iOS 从 Swift 重复实现逐模块切换到 KMP，并最终删除重复业务逻辑。
- 让 Android 从共享逻辑验证壳升级为能够连接真实 Gateway 的原生客户端。

## 架构约束

- SwiftUI/UIKit UI 不迁入共享层。
- Reducer 必须是可确定测试的纯状态转换，不访问网络、磁盘或 UIKit。
- 持久化、WebSocket、超时和任务调度通过接口或 Coordinator 隔离。
- 不一次性重写 `AppStore`；每一步都保持工程可编译、测试可运行。
- 不把 Swift 特有类型暴露到未来的共享 State。
- 不为拆分而改变协议语义、滚动行为或后台执行策略。
- 先稳定边界和测试，再将纯逻辑逐模块迁入 Kotlin。
- 影子模式只能读取和比对，不能同时让 Swift 与 Kotlin 修改同一份产品状态。
- 每次只切换一个有明确回滚边界的子系统；不得一次性替换整个 iOS `AppStore`。
- SwiftUI 和 Compose 只消费平台友好的 snapshot/intent，不直接依赖 Kotlin 内部 sealed class、协程 Job 或可变集合。

## 目标结构

```text
DeepSeekHarnessMobile/Core/
├── AppStore.swift                    # 最终仅保留 iOS ObservableObject 适配和协调
├── Persistence/
│   ├── AppPreferences.swift
│   └── UserDefaultsAppPreferences.swift
├── Domain/
│   ├── AppState.swift
│   ├── SessionListReducer.swift
│   ├── QuestionReducer.swift
│   └── SessionControlReducer.swift
├── Sync/
│   ├── HistorySyncEngine.swift
│   ├── RequestTracker.swift
│   └── AttachmentLoader.swift
├── Protocol/
│   ├── PairingPayloadParser.swift
│   └── GatewayFrameRouter.swift
└── Platform/
    └── AgentBackgroundExecutionController.swift

shared/src/commonMain/                  # 后续阶段创建
├── protocol/
├── domain/
├── data/
└── usecase/
```

实际文件可以根据拆分过程中暴露的依赖调整，但必须保持上述依赖方向。

## 分阶段任务

### 阶段 0：建立迁移护栏

- [x] 0.1 建立本计划文档，记录范围、顺序和验收标准。
- [x] 0.2 记录改造前基线：当前测试数量、构建命令和工作区状态。
- [x] 0.3 为即将提取但现有测试未覆盖的行为补充 characterization tests。

验收标准：

- 后续会话只读本文档即可判断当前进度。
- 所有重构步骤都有重构前或同步补充的行为测试。

### 阶段 1：持久化解耦

- [x] 1.1 新增 `AppPreferences` 协议，覆盖 endpoint、selectedWorkspaceID 和 sessions。
- [x] 1.2 新增 `UserDefaultsAppPreferences`，保持现有 key 和默认值兼容。
- [x] 1.3 通过构造函数将 preferences 注入 `AppStore`。
- [x] 1.4 删除 `AppStore` 对 `UserDefaults.standard` 的直接访问。
- [x] 1.5 去除 `sessions.didSet` 同步写盘，改为显式且不重复的持久化入口。
- [x] 1.6 添加 preferences round-trip、旧数据兼容和隔离测试。

验收标准：

- 旧安装保留 endpoint、工作区选择和会话列表。
- `AppStore` 不直接引用 `UserDefaults.standard`。
- session 列表的业务行为不变。

### 阶段 2：纯协议解析

- [x] 2.1 提取 `PairingPayloadParser` 和 Base64URL 解码。
- [x] 2.2 将配对校验错误移至 parser 模块。
- [x] 2.3 为所有配对失败分支和成功路径添加测试。
- [x] 2.4 `AppStore.pair` 仅负责调 parser、更新状态和触发连接。

验收标准：parser 不依赖 SwiftUI、UIKit、WebSocket 或持久化。

### 阶段 3：SessionList 领域状态与 Reducer

- [x] 3.1 定义不含 SwiftUI 类型的 `SessionListState`。
- [x] 3.2 定义 `SessionListAction`。
- [x] 3.3 提取远端会话合并、排序、归档过滤逻辑。
- [x] 3.4 提取事件驱动的 title/running/unread/lastActivity 更新逻辑。
- [x] 3.5 提取 select、markRead 和 upsert 逻辑。
- [x] 3.6 添加 reducer 纯函数测试，包括重复事件和非选中会话未读状态。
- [x] 3.7 AppStore 改为转发 action 并发布 reducer 结果。

验收标准：Reducer 不执行 I/O、不启动 Task、不调用 GatewayClient。

### 阶段 4：Question 与 SessionControl Reducer

- [x] 4.1 提取 `QuestionState/Action/Reducer`。
- [x] 4.2 提取问题答案校验和 requested/response/resolved 状态转换。
- [x] 4.3 提取 `SessionControlState/Action/Reducer`。
- [x] 4.4 提取模型、权限、context、stats 和默认配置状态转换。
- [x] 4.5 将 12 秒超时移入独立 `RequestTracker`，Reducer 仅接收 timeout action。
- [x] 4.6 补充纯 Reducer 和 RequestTracker 测试。

### 阶段 5：历史与实时事件同步

- [x] 5.1 定义 `HistoryState` 和 `HistoryResult`。
- [x] 5.2 提取分页 cursor、字节预算、批次数和循环 cursor 检测。
- [x] 5.3 提取 history/live tail 去重合并。
- [x] 5.4 提取 request token、取消和超时控制为 `HistorySyncEngine`。
- [x] 5.5 保持现有 ConversationProjector 增量投影性能路径。
- [x] 5.6 添加分页、重连重放、乱序、重复 seq、取消和超时测试。

验收标准：长会话加载期间实时尾部不暂停、不重复、不回退。

### 阶段 6：Frame Router 与剩余副作用

- [x] 6.1 将 `handle(GatewayFrame)` 拆成 `GatewayFrameRouter`。
- [x] 6.2 Router 将 wire frame 转换成领域 action/effect，不直接更新 UI。
- [x] 6.3 提取附件请求队列为 `AttachmentLoader`。
- [x] 6.4 提取 iOS 后台任务为 `AgentBackgroundExecutionController`。
- [x] 6.5 AppStore 只保留状态发布、用户 intent 和 effect 协调。

### 阶段 7：KMP shared 模块

- [x] 7.1 创建 Gradle/KMP shared 模块和 Android application 骨架。
- [x] 7.2 迁移 JSONValue、Gateway DTO 和 wire decoder 到 `commonMain`。
- [x] 7.3 迁移 SessionList、Question、SessionControl Reducer。
- [x] 7.4 迁移 Conversation/Trajectory 投影纯逻辑。
- [x] 7.5 迁移 HistorySyncEngine 的平台无关部分。
- [x] 7.6 建立 commonTest fixture，与现有 Swift 测试保持协议一致。
- [x] 7.7 为 SwiftUI 建立薄共享模块适配器。
- [x] 7.8 Android Compose UI 接入共享 State 和业务逻辑。

验收结论：KMP 公共层、iOS framework 桥接和 Android 共享逻辑验证页均已建立；Android 已实际消费 KMP State，iOS 尚未切换产品业务来源。

### 阶段 8：生产级共享 Facade 与 iOS 影子验证

- [x] 8.1 整理阶段 0～7 工作区，建立可回滚 Git 检查点，并在继续前同步主干、处理冲突。
- [x] 8.2 逐项盘点 Swift `GatewayFrameRouter/AppStore` 与 `SharedMobileStore` 的 frame、intent、state 和 effect 覆盖差距。
- [x] 8.3 将共享 Facade 补齐为生产接口：输入 wire frame/用户 intent，输出平台友好 route snapshot 和显式 effect，不在 KMP 内直接执行平台 I/O。
- [x] 8.4 为 Swift 建立稳定值类型映射、错误边界和 `@MainActor` 串行提交入口，禁止未捕获 Kotlin 异常越过 Swift 边界。
- [x] 8.5 建立只读影子模式：同一 frame 同时交给现有 Swift Router 与 KMP，逐 frame 比较 route fingerprint；Session、Question、Control、Conversation 和 History 继续由对等领域 fixture 校验，UI 仍只读 Swift 状态。
- [x] 8.6 用 Swift/KMP 对等 fixture 覆盖全部 Gateway frame，并记录允许存在的平台展示差异。

验收标准：影子模式不改变 iOS UI、持久化、网络请求数量和生命周期行为；代表性 fixture 的 Swift/KMP 领域结果一致；KMP facade 不泄漏内部并发和可变实现。

自动化验收结论：30 类已知 frame、unknown 与 malformed 路由对等；坏 JSON/Context 返回结构化错误；KMP、Android、iOS Debug/Release 门禁通过。

人工验收结论：2026-08-26 用户确认真实 Gateway 使用流程未发现异常；DEBUG 影子验证已启用，Human Question 正常展示，Console 未出现 KMP 影子差异。阶段 8 自动化与人工验收全部通过。

### 阶段 9：iOS 基础领域状态切换到 KMP

- [x] 9.1 先将 SessionList 的远端合并、排序、归档、选择、运行和未读状态切换到 KMP。
- [x] 9.2 保持 `UserDefaultsAppPreferences` 为 iOS 持久化适配器，在 Swift snapshot 与既有持久化模型之间做显式映射。
- [x] 9.3 将 Human Question 请求、校验、提交状态、响应和 resolved 流程切换到 KMP。
- [x] 9.4 将模型、权限、Context Usage、Stats、Agent Presets 和默认配置状态切换到 KMP。
- [ ] 9.5 每完成一个子系统，关闭该子系统的 Swift 写路径并保留一轮可回滚开关；人工验收通过后删除开关。

验收标准：iOS UI、旧安装数据和 Gateway 请求语义保持不变；每个已切换子系统只有 KMP 一个业务状态来源；自动化测试与会话、问题、模型、权限人工回归通过。

阶段 9.4 自动化验收结论：两轮真实 Gateway 人工核验暴露了无 token/无 `sessionId` control 响应与严格隔离策略不兼容的问题；现已改为将无身份字段的响应绑定同 kind 唯一 active generation，显式 session 不匹配仍拒绝，超时后允许恢复重试。修复后 KMP `shared` 全量 44 项测试、iPhone 17（iOS 26.5 Simulator）全量 94 项测试和 Android 2 项单元测试均为 0 失败；Android Debug APK 与 iOS 无签名 Release Device 构建成功；`git diff --check` 通过。阶段 9.1～9.4 的人工核验清单见 `Docs/kmp-stage9-manual-verification.md`，当前等待再次人工核验。

性能债务：当前 SessionControl 每次有状态变化仍跨 KMP/Swift 边界编解码全量 JSON snapshot（P3）。必须在阶段 10 开始前完成增量 patch 或结构化桥接方案评估，并在进入高频 Conversation/Trajectory 流式状态切换前落地适当方案，避免每个 token 复制完整状态。

### 阶段 10：iOS Conversation、Trajectory 与 History 切换

- [ ] 10.1 为 Swift 暴露增量 Conversation 投影接口，避免每个 token 跨语言复制完整消息数组。
- [ ] 10.2 将流式 chunk 拼接、最终消息替换、工具调用和图片引用投影切换到 KMP。
- [ ] 10.3 将 Trajectory request/assistant/tool/subtool 和 Token Usage 投影切换到 KMP。
- [ ] 10.4 将历史分页状态、cursor 检测、history/live tail 去重和同步水位切换到 KMP。
- [ ] 10.5 iOS 继续拥有网络 Task、超时触发、UIKit viewport 和后台执行；KMP 只计算状态与下一步 effect。
- [ ] 10.6 对长会话执行性能、内存、滚动位置、重连和实时尾部人工回归。

验收标准：流式过程不重复、不回退、不闪烁，也不会每个 token 全量发布 AppStore；历史加载期间实时尾部不停顿，滚动位置不跳变，性能无明显回退。

### 阶段 11：iOS 重复实现清理与架构收口

- [ ] 11.1 删除已被 KMP 取代的 Swift DTO、Reducer、Projection、History 纯逻辑及其重复 fixture。
- [ ] 11.2 保留 Swift 平台层：ObservableObject/SwiftUI、UserDefaults、Keychain、WebSocket、文件、图片、UIKit 和后台任务。
- [ ] 11.3 将 `AppStore` 收敛为 snapshot 发布、用户 intent 转发和平台 effect 执行器。
- [ ] 11.4 清理迁移开关、影子比较代码和过渡映射，更新架构文档与依赖图。
- [ ] 11.5 执行 iOS 全量自动化、完整人工回归以及 Release Device framework 构建。

验收标准：iOS 产品业务逻辑以 KMP 为唯一来源，Swift 不再维护对应的平行实现。

### 阶段 12：Android 真实 Gateway 与平台服务

- [ ] 12.1 定义共享 Gateway transport、preferences、credential、attachment cache 和 clock 接口。
- [ ] 12.2 在 Android 实现 OkHttp WebSocket、重连、认证/配对、请求关联和网络状态处理。
- [ ] 12.3 使用 Android Keystore/DataStore 实现凭据与配置持久化，敏感信息不得进入日志或普通 preferences。
- [ ] 12.4 实现 Android 图片选择、预处理、附件缓存和上传/下载 effect。
- [ ] 12.5 接入 Android 生命周期、前后台连接策略和必要的前台服务/通知策略。
- [ ] 12.6 建立 fake transport 集成测试和真实 Gateway 开发环境冒烟测试。

验收标准：Android 能完成配对、重连、会话拉取、消息收发、流式事件和历史加载，断网恢复不丢状态。

### 阶段 13：Android 原生产品 UI

- [ ] 13.1 建立 Compose Navigation、应用级 State Holder 和窗口尺寸适配。
- [ ] 13.2 实现配对/连接、Workspace、Session 列表和 Settings 页面。
- [ ] 13.3 实现 Conversation、流式消息、Markdown、图片附件、工具结果和输入区。
- [ ] 13.4 实现 Human Question、模型选择、权限、Context Usage、Stats 和 Agent Preset UI。
- [ ] 13.5 实现 History 分页、Trajectory、未读、归档、搜索和错误恢复交互。
- [ ] 13.6 建立 Compose UI 测试、截图测试和关键无障碍语义。

验收标准：Android 关键产品流程与 iOS 协议语义一致，同时保持 Android 原生交互和生命周期设计。

### 阶段 14：双端一致性、性能与发布准备

- [ ] 14.1 建立同一协议录制数据在 commonTest、Swift adapter test 和 Android integration test 中的回放矩阵。
- [ ] 14.2 验证长会话、密集 chunk、多图、工具/子工具、Human Question 重放、断网和后台恢复。
- [ ] 14.3 分析 Android 启动、内存、Compose 重组、网络、电量和 ANR，并复核 iOS 桥接复制与流式性能。
- [ ] 14.4 配置 ktlint/detekt、Android Lint、KMP tests、iOS tests 和双端构建的 CI 门禁。
- [ ] 14.5 完成 Android release signing、版本策略、混淆规则、隐私/权限说明和发布清单。
- [ ] 14.6 完成双端人工验收并更新 README、架构文档和维护手册。

验收标准：共享逻辑只有一份、双端关键协议一致、CI 全绿，并具备可重复的 Debug/Release 构建流程。

## 每步验证

每个任务至少执行与风险相称的验证：

```bash
xcodebuild test \
  -project DeepSeekHarnessMobile.xcodeproj \
  -scheme DeepSeekHarnessMobile \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

如果本机没有对应模拟器，先用 `xcrun simctl list devices available` 选择可用设备，并在变更记录中写明实际命令。KMP 模块建立后增加：

```bash
./gradlew :shared:allTests
./gradlew :androidApp:testDebugUnitTest
```

阶段 8 起，每个 iOS 子系统切换还必须执行：

- 对等 fixture 的 Swift/KMP 影子差异测试；
- iOS 全量 XCTest；
- `shared:allTests` 与 Android JVM 单测；
- 对应子系统的 iOS 人工回归。

阶段 12 起增加 Android fake transport 集成测试；阶段 13 起增加 Compose UI 测试和真实 Gateway 冒烟测试。

## 当前已知风险

- `sessions` 当前通过 `didSet` 高频同步写入 UserDefaults，拆分时要避免漏存或重复存储。
- `AppStore` 是 `@MainActor`，将纯逻辑移出后要明确状态提交线程。
- history rebase 与 live tail 存在并发窗口，阶段 5 前不顺手重写其算法。
- Conversation timeline 使用 UIKit 直接订阅优化，不能退化为每 token 发布整个 AppStore。
- iOS 后台任务和 Gateway 重连相互影响，必须留在平台/effect 层。
- Swift 与 Kotlin Flow/异常的互操作需要粗粒度 facade，避免直接暴露复杂内部 API。
- Swift/Kotlin 双实现并存期间容易产生静默漂移，必须用只读影子比较缩短并存时间。
- Kotlin/Native 未声明并捕获的异常跨越 Swift 边界可能导致进程终止，facade 必须返回显式错误结果。
- Conversation 流式投影若跨桥接层频繁复制完整列表，会引入卡顿和内存峰值，必须保留增量接口。
- Android 真实 Gateway 接入涉及凭据、后台连接和附件文件，必须分别使用 Keystore、生命周期策略和受控缓存。

## 变更记录

### 2026-08-27

- 第三轮人工核验确认模型/权限控制已进入真实请求链路，但切换权限被宿主拒绝：`commands/execute` 的新版参数 descriptor 要求必填 `images`。问题位于同级 `dsh-plugin-mobile-gateway`，并非 KMP Reducer/effect；Gateway 已在 `/permission` 调用中补充 `images: []`，其 53 个 dispatch 用例及鉴权、配对、LAN 全套测试通过。需重启实际 Gateway 后继续人工核验。
- 第二轮真实 Gateway 核验继续出现 `models` 超时和 `response-correlation-quarantined: permission-options`。对照 `dsh-plugin-mobile-gateway` 实现确认：`models`、`permission-options` 成功帧不会回显客户端 request token 或 `sessionId`，因此永久 quarantine/跨 generation 强制 session 关联虽然能拒绝理论上的迟到帧，却会拒绝正常产品响应并让一次超时扩大为重连前持续不可用。
- SessionControl 关联策略改为协议可实现的边界：每个 kind 仍只有一个 active generation；缺少身份字段的响应绑定该 active，请求明确携带错误 session 时拒绝；超时/失败原子结束当前 generation并允许 latest queued 或后续人工刷新继续，不再永久 quarantine。无 token 协议无法数学上区分极晚旧响应，依赖 Gateway“每请求单终态响应”约束，这是恢复功能与避免永久不可用之间的明确取舍。
- 更新 Kotlin/Swift 回归用例，覆盖跨 session 切换后的 legacy nil-session 响应、显式旧 session 拒绝、timeout 后无需重连即可重试，以及无 token 错误立即结束 active 并展示真实原因。最终 KMP 44 项、iOS Simulator 94 项均为 0 失败，Android 单测/APK 与 iPhoneOS Release 构建成功；仍等待人工复验，不进入 9.5。
- 阶段 9.4 首轮真实 Gateway 人工核验发现回归：重复进入同一会话后，模型与权限响应可能省略 `sessionId`，KMP 将同 target 的第二代请求错误标记为必须显式关联并忽略响应；12 秒超时隔离时又残留 `explicitSessionRequiredKinds`，Swift 因快照中出现“无 active target 的显式关联 kind”而永久 fail-closed。模型和权限配置因此无法继续加载。
- 修复同 target 正常刷新规则：仅当前后 target 不同时要求显式 session 关联；同 target 继续兼容 Gateway 省略 `sessionId` 的单终态响应。超时/quarantine 分支现在原子清理显式关联标记，避免生成无效快照。
- 新增 Kotlin 与 Swift 回归测试，覆盖连续两次模型/权限刷新均省略 `sessionId`，以及跨 session generation 超时后快照仍满足 Swift/KMP 不变量。修复后 `:shared:allTests --rerun-tasks` 共 44 项、iPhone 17 / iOS 26.5 Simulator 全量 XCTest 共 94 项，均为 0 失败；Android 单测和 Debug APK、iPhoneOS Release 构建成功。当前继续等待阶段 9.1～9.4 人工复验，不进入 9.5。

### 2026-08-26

- 完成阶段 9.4：新增有状态 `SharedSessionControlStore`，模型、权限、Context Usage、Stats、Agent Presets、默认模型与默认配置统一由 KMP 单写；Swift `AppStore` 只发布 KMP snapshot 并执行经过语义校验的显式 effect，旧 Swift SessionControl 写路径已关闭。
- SessionControl 对同 kind 请求采用 `active + queued(latest-wins)` 串行模型，KMP 在同一事务内维护 request target、generation token、完成信号与下一 effect；Swift 桥接校验 `applied`、`committed`、`completedKind/completedRequestToken`、snapshot 和 effect 不变量，任何坏结果均在状态提交和平台 I/O 前 fail-closed。序列号统一使用 `Long/KotlinLong`，覆盖超过 Int32 的 Gateway sequence。
- Gateway 响应不回显 request token，`models`/`permission-options` 也不回显 `sessionId`，因此正常成功依赖“每个请求只有一个终态响应”的协议边界。每个 kind 只维护一个 active generation；缺少身份字段的响应绑定 active，显式 session/target 不匹配时拒绝；超时或失败后可直接重试。断线/新 `hello` 仍会清除上一连接代际的 active、queued 与 token 状态。
- 阶段 9.4 自动化门禁通过：KMP `:shared:allTests --rerun-tasks` 共 42 项测试、0 失败；iPhone 17 Pro（iOS 26.2 Simulator）`xcodebuild test` 共 92 项测试、0 失败；Android 单测 2 项、0 失败且 Debug APK 构建成功（约 12 MB）；iOS 无签名 Release Device 构建成功；`git diff --check` 通过。当前暂停，等待阶段 9.1～9.4 iOS 人工核验，通过后执行阶段 9.5。
- 记录 P3 性能债务：SessionControl 当前仍跨桥接层传递全量 JSON snapshot。阶段 10 前必须评估增量 patch/结构化桥接，并在高频流式迁移前采用合适方案，禁止把全量 snapshot 模式直接扩展到逐 token 路径。
- 完成阶段 9.3：新增有状态 `SharedQuestionStore`，iOS Human Question 的请求、答案校验、提交/取消状态、Gateway 响应、resolved 与失败恢复统一改为 KMP 单写；Swift `pendingQuestionRequests` 和 `questionRequestStatuses` 仅发布 KMP 快照，旧 Swift Reducer 写路径已关闭。
- Question effect 采用 fail-closed：仅在 KMP mutation 成功、快照可解析且 effect 与原始 intent 在 `rpcId`、`sessionId`、action 和 answers 上语义一致时才执行网络副作用；任何结构化错误、坏快照或 effect 不匹配都会关闭后续 mutation，且不会误发 Gateway 请求。后台执行额度改为按 session 分别计数，Human Question 临时额度按 `rpcId` 追踪；无 `rpcId` 的连接/会话失败会原子清理匹配请求及额度，避免跨 session 串扰和后台任务泄漏。
- 阶段 9.3 自动化门禁通过：`./gradlew :shared:iosSimulatorArm64Test --rerun-tasks` 共 30 项测试、0 失败；iPhone 17 Pro 模拟器全量 `xcodebuild test` 共 80 项测试、0 失败；`./gradlew :androidApp:testDebugUnitTest` 构建成功；Android CLI 正确识别 debug/release variants 且 Debug APK 存在；`git diff --check` 通过。下一步为阶段 9.4。
- 完成阶段 9.1～9.2：新增有状态 `SharedSessionListStore`，iOS `AppStore` 的远端会话合并、排序、归档、选择、运行和未读转换均经 KMP Reducer 提交；Swift 属性仅作为 UI/持久化快照，`UserDefaultsAppPreferences` 继续承担 iOS I/O，并通过稳定 JSON 值显式映射旧 `SessionSummary`，旧安装数据格式不变。
- SessionList 桥接采用 fail-closed：初始化恢复失败时保留已持久化的 Swift 快照且不再调用 KMP mutation；若 KMP mutation 成功后返回 Swift 无法解析的快照，则永久关闭该实例的后续 mutation，防止两端状态分叉和错误持久化。KMP mutation 自身先完成快照序列化再原子提交，结构化失败不会污染原状态；Swift/Kotlin 对应边界测试及阶段 9.1～9.2 前置自动化门禁均已通过。下一步为阶段 9.3。
- 用户完成阶段 8 iOS 真实 Gateway 人工核验：DEBUG Console 确认 KMP 只读影子已启用且未报告差异，Human Question 和实际产品流程正常；一次 WebSocket `NSURLErrorDomain -1011` 握手失败未影响后续连接，按独立网络日志记录。阶段 8 全部验收通过，下一步为阶段 9.1。
- 完成阶段 8.3～8.6：新增无状态 `SharedShadowFacade`，统一接收 wire frame/用户 intent，输出 JSON route fingerprint、平台 effect descriptor 和显式错误；Swift 建立 Codable 值映射及 `@MainActor KMPShadowValidator`，`AppStore` 仅在 DEBUG 下逐 frame 只读比较，仍只执行原 Swift route，Release 不创建验证器。
- 新增 Swift/KMP 对等路由 fixture，覆盖 30 类已知 Gateway frame、unknown、6 类 malformed 结果以及坏 JSON/Context；iOS 全量 66 项测试全部通过，新增影子对等测试无差异。
- 阶段 8 最终门禁通过：Gradle 共 81 个 task 成功（KMP commonTest、Android 单测/APK/Lint、iOS Debug/Release framework）；Android CLI 正确识别 `:androidApp` debug/release variants；iPhoneOS Release App 构建成功。真实 Gateway 人工核验清单见 `Docs/kmp-stage8-manual-verification.md`。
- 完成阶段 8.2：新增 `Docs/kmp-ios-shadow-gap-analysis.md`，确认 Swift Router 覆盖 30 类响应，而阶段 7 `SharedMobileStore` 只处理 6 类；明确阶段 8 以纯 route fingerprint、显式平台 effect、结构化错误和 DEBUG 只读比较为边界，不在影子模式执行 I/O，也不在流式路径跨桥接复制完整 Conversation。
- 完成阶段 8.1：将 Android Studio 生成的 `.kotlin/` 元数据加入忽略规则，以提交 `454be78` 建立阶段 0～7 可回滚检查点；获取最新 `origin/main` 后主干仍停留在 `e53b372`，当前分支仅领先 1 个提交，无冲突、无 stash。下一步为阶段 8.2 能力差距盘点。
- 扩展阶段 8～14 路线图：先生产化共享 facade 并以只读影子模式验证 Swift/KMP 一致性，再分批切换 iOS 基础领域、Conversation/Trajectory/History 并删除 Swift 重复实现；随后完成 Android 真实 Gateway、平台服务、原生产品 UI 以及双端发布门禁。下一步从阶段 8.1 建立可回滚检查点和能力差距盘点开始。
- 用户已完成阶段 7 Android 人工测试：共享 fixture 能生成运行中 Session、Conversation 投影和 Human Question；最终 `event` 正确替换流式临时消息；无效 JSON 能显示错误且不破坏既有状态；重置后 frame、Question、Session、Conversation 和错误全部清空，应用无崩溃。阶段 7 的自动化与人工验收全部通过。
- 完成阶段 7.6：建立 Kotlin `GatewayProtocolFixtures` 与 Swift `GatewayProtocolParityFixtures` 对等样本，覆盖网关遗漏 `kind: event`、Human Question、图片附件和历史图片归一化；fixture 对齐过程修复 Kotlin `JsonValue` 数字经 Double 表示后无法反序列化为图片 Int 元数据的问题。
- 完成阶段 7.7：新增粗粒度 `SharedMobileFacade/SharedMobileStore/SharedMobileSnapshot` 和 Swift `KMPSharedAdapter`；Xcode 在 Sources 前按配置、平台和架构构建静态 `DeepSeekHarnessShared.framework`，SwiftUI 不直接依赖内部 Reducer。
- 完成阶段 7.8：Android Compose 已接入共享 Session State、Question State、Gateway decoder 与 Conversation 投影，支持加载人工 fixture、选择会话、注入任意 wire JSON、观察错误和重置状态；补充 Android state holder 单测。
- `ANDROID_HOME=/Users/lichaofan/Library/Android/sdk ./gradlew :shared:allTests :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug --stacktrace` 验证通过，共 78 个任务成功；Debug APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。
- iPhone 17 Pro（iOS 26.2 Simulator）完整回归通过，共 65 项 iOS 测试、0 失败、0 跳过；测试覆盖 KMP framework 链接、Swift adapter 和同一缺省 kind fixture。现有 iOS 行为未受影响，下一步由用户执行 Android 人工测试。
- 完成阶段 7.4/7.5：将 Conversation 增量投影、流式消息替换、history/live tail rebase、Trajectory request/assistant/tool/subtool 投影以及 Token 用量统计迁入 `commonMain`；同时迁入历史分页纯 Reducer、事件有序去重合并和请求代际/超时协调器。
- 新增 KMP 投影与历史同步测试，覆盖流式拼接与最终消息替换、实时尾部追加、Trajectory 节点和 usage、重复 cursor、乱序/重复 seq、取消旧超时和当前超时触发；`ANDROID_HOME=/Users/lichaofan/Library/Android/sdk ./gradlew :shared:allTests --stacktrace` 验证通过。
- `HistorySyncEngine` 仅关闭自身创建的 CoroutineScope；测试或平台层注入外部 scope 时不再错误取消调用方生命周期。下一步为阶段 7.6。
- 用户已完成人工回归，阶段 6 拆解以及同步后的语言切换、目录创建、多图叠放和图片比例功能验收通过。
- 完成阶段 7.1：新增 Gradle 9.1.0 Wrapper，采用 AGP 9.0.1 与 Kotlin 2.3.20；工程拆分为纯 KMP `shared` library 和独立原生 Compose `androidApp` application，避免 AGP 9 下 application 与 multiplatform 插件混用。
- `shared` 已配置 Android、iOS x64、iOS arm64 和 iOS Simulator arm64 targets，输出静态 `DeepSeekHarnessShared` framework；新增公共模块稳定入口和 commonTest。
- `androidApp` 已配置 API 36、minSdk 24、Java 17、Compose Material 3 和 INTERNET 权限，能够调用 `SharedModuleInfo`，并包含 Android JVM smoke test 与可运行的 KMP 状态页。
- 验证命令 `ANDROID_HOME=/Users/lichaofan/Library/Android/sdk ./gradlew :shared:allTests :androidApp:testDebugUnitTest :androidApp:assembleDebug --stacktrace` 通过；62 个任务执行成功。
- 验证命令 `ANDROID_HOME=/Users/lichaofan/Library/Android/sdk ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 :androidApp:lintDebug --stacktrace` 通过；iOS Simulator framework 与 Android Lint 均成功。
- Android CLI 已识别 `:androidApp` debug/release variants，Debug APK 位于 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`；下一步为阶段 7.2。
- KMP 创建后的完整 iOS 回归暴露主干 `history.cursor.loop` 英文翻译使用 `%@`、中文使用 `%lld` 的占位符类型不一致；英文环境格式化整数时会终止进程，现已统一为 `%lld`，并将相关测试改为验证领域失败语义而非硬编码语言。
- 使用 `xcodebuild test -quiet -project DeepSeekHarnessMobile.xcodeproj -scheme DeepSeekHarnessMobile -destination 'platform=iOS Simulator,id=8FC21323-CA69-49F7-B015-D0B761B1C40F' -derivedDataPath /tmp/dsh-mobile-kmm-stage7-derived SYMROOT=/tmp/dsh-mobile-kmm-stage7-products OBJROOT=/tmp/dsh-mobile-kmm-stage7-intermediates CODE_SIGNING_ALLOWED=NO` 完成 64 项 iOS 测试，全部通过。
- 获取并同步最新 `origin/main`，将 `feature/kmm` 从 `bbd9d1c` 快进到 `e53b372`，纳入图片长宽比修复、图片尺寸元数据与对应协议测试。
- 主干仅与当前改造共同修改 `GatewayProtocolTests.swift`，Git 自动合并成功，无文本冲突；阶段 0～6 的 AppPreferences、Reducer、Router、History、附件加载与后台执行拆解均完整保留。
- 使用全新临时产物目录执行 64 项完整 iOS Simulator 测试，全部通过；命令为 `xcodebuild test -quiet -project DeepSeekHarnessMobile.xcodeproj -scheme DeepSeekHarnessMobile -destination 'platform=iOS Simulator,id=8FC21323-CA69-49F7-B015-D0B761B1C40F' -derivedDataPath /tmp/dsh-mobile-kmm-main-sync-20260826-derived SYMROOT=/tmp/dsh-mobile-kmm-main-sync-20260826-products OBJROOT=/tmp/dsh-mobile-kmm-main-sync-20260826-intermediates CODE_SIGNING_ALLOWED=NO`。
- 当前仍只有主干既有的两条 `L10n.swift` 本地化插值警告和一条 `ConversationViewport.swift` iOS 17 弃用警告，无新增警告或错误；下一步仍为阶段 7.1。

### 2026-08-25

- 再次获取并同步最新 `origin/main`，将 `feature/kmm` 从 `6879064` 快进到 `bbd9d1c`，纳入应用内语言切换、Host 端目录创建和对话多图叠放能力。
- `AppStore.swift` 与 `GatewayProtocolTests.swift` 发生文本冲突；已保留阶段 0～6 的 Reducer/Router/RequestTracker 架构，并将主干 `directory-create` 响应接入 `GatewayFrameRouter → workspace.directoryCreated`，目录创建成功、父目录刷新和失败清理逻辑均保留。
- 更新动态 String Catalog 格式参数断言，并扩大 HistorySyncEngine 毫秒级超时测试的调度裕量，避免全量测试负载下偶发抖动。
- 使用全新临时产物目录执行 63 项完整 iOS Simulator 测试，全部通过；命令为 `xcodebuild test -quiet -project DeepSeekHarnessMobile.xcodeproj -scheme DeepSeekHarnessMobile -destination 'platform=iOS Simulator,id=8FC21323-CA69-49F7-B015-D0B761B1C40F' -derivedDataPath /tmp/dsh-mobile-kmm-main-sync-derived SYMROOT=/tmp/dsh-mobile-kmm-main-sync-products OBJROOT=/tmp/dsh-mobile-kmm-main-sync-intermediates CODE_SIGNING_ALLOWED=NO`。
- 获取最新 `origin/main`，将 `feature/kmm` 从 `eb84f79` 快进到 `15c63e2`，同步主干 3 个提交。
- 主干与当前重构仅共同修改 `AppStore.swift`；Git 自动合并成功，无文本冲突，主干新增的 `connectOnColdLaunchIfPaired` 冷启动配对恢复逻辑已确认保留。
- 恢复全部未提交重构文件后执行 52 项完整 iOS Simulator 测试，全部通过；仅保留既有 `traitCollectionDidChange` 弃用警告。
- 再次同步主干至 `6879064`，纳入 String Catalog 全量本地化、搜索键盘修复和 LICENSE；`AppStore.swift` 的本地化冲突按“保留 Reducer/History 架构并采用主干 L10n API”解决。
- 新增纯 `GatewayFrameRouter`，将 wire frame 转换为 connection/content/control/workspace/question/failure 路由以及 `QuestionAction`、`SessionControlAction`；`AppStore` 不再包含 `switch frame.kind` 或原始 frame 字段解析。
- 新增 `AttachmentLoader`，接管附件去重、排队、最多 3 路并发、完成后补位和重连 reset；Gateway 请求与磁盘 cache 仍由 effect 层执行。
- 新增 iOS 平台 `AgentBackgroundExecutionController`，接管 `UIApplication` background task、turn 计数、session 关联、过期和取消；`AppStore` 已移除 UIKit import 和后台任务字段。
- 新增 5 项 Router/Attachment/Background 测试；结合主干 3 项本地化目录测试，当前完整测试共 60 项，全部通过。
- 阶段 6 完成；下一步为阶段 7.1，建立 KMP shared 模块与 Android application 骨架。

### 2026-08-24

- 创建迁移计划和跨会话续作规则。
- 完成阶段 0.1。
- 记录改造前基线：`GatewayProtocolTests` 共 23 项；工作区在 `feature/kmm`，开始改造前无未提交变更。
- 新增 `AppPreferences` 和 `UserDefaultsAppPreferences`，保持既有 UserDefaults keys 和默认 endpoint。
- `AppStore` 改为构造注入 preferences，并移除对 `UserDefaults.standard` 的直接访问。
- 移除 `sessions.didSet` 隐式写盘，以 `updateSessions` 对每次实际业务变更最多保存一次。
- 新增 3 项持久化与注入测试，当前共 26 项测试。
- 验证命令：`xcodebuild test -quiet -project DeepSeekHarnessMobile.xcodeproj -scheme DeepSeekHarnessMobile -destination 'platform=iOS Simulator,id=8FC21323-CA69-49F7-B015-D0B761B1C40F' -derivedDataPath /tmp/dsh-mobile-kmm-test-derived CODE_SIGNING_ALLOWED=NO`，通过。
- 完成阶段 0 和阶段 1；下一步为阶段 2.1，提取纯 `PairingPayloadParser`。
- 提取纯 `PairingPayloadParser`，覆盖成功、Base64URL、JSON、版本、endpoint、pairingCode 和过期边界。
- 提取 `SessionListState/Action/Reducer`，接管远端合并、归档过滤、排序、标题、运行/未读、选择与发送后会话状态。
- AppStore 通过单一 `reduceSessionList` 入口提交领域 action；只有状态实际变化时才发布和持久化，避免 token event 引发无效 UI 更新。
- 新增 9 项 parser/reducer 测试，当前共 35 项测试；完整测试通过。
- 手动测试前验证：35 项 XCTest 全部通过；独立 Debug iOS Simulator 构建成功，产物位于 `/Users/lichaofan/Library/Developer/Xcode/DerivedData/Build/Products/Debug-iphonesimulator/DshMobile.app`（工程当前覆盖了 build products 输出位置；`-derivedDataPath` 仍用于缓存和日志）。
- 用户已完成阶段 1～3 的 iOS 人工回归，持久化恢复、会话列表/状态、消息发送、配对及流式体验未发现回归；人工验收通过。
- 当前仅有一项既有编译警告：`ConversationViewport.swift` 使用了 iOS 17 已弃用的 `traitCollectionDidChange`，与本轮拆分无关。
- 提取纯 `QuestionState/Action/Reducer`，接管问题请求、重放去重、答案校验、提交、服务端响应、完成、请求失败和重连清理状态；网络发送与 iOS 后台任务仍保留在 `AppStore` effect 层。
- 新增 5 项 Question Reducer 测试，覆盖断线、答案顺序、非法/重复选项、单选限制、accepted/rejected/not-pending、resolved 和 reset；当前共 40 项测试。
- 完整 iOS Simulator 测试再次通过；仅保留既有 `traitCollectionDidChange` 弃用警告，无新增编译警告。
- 提取纯 `SessionControlState/Action/Reducer`，接管 session/default loading kinds 和 models、select-model、permission-options 的请求目标关联；UUID token、12 秒超时和网关调用仍保留在 `AppStore` effect 层。
- 新增 2 项 SessionControl Reducer 生命周期测试，当前共 42 项测试；完整 iOS Simulator 测试通过。
- 将模型目录、权限、Context Usage、Session Stats、Agent Presets 和全局默认配置纳入 `SessionControlState`，并接管 frame、history projection 和实时 event 的增量状态转换。
- 新增 3 项状态转换测试，覆盖模型/默认值、权限过滤、Context 与 Stats 局部响应合并；当前共 45 项测试，完整 iOS Simulator 测试通过。
- 提取独立 `RequestTracker`，支持同 key 代际替换、完成取消和批量取消；SessionControl Reducer 新增 session/default timeout action，`AppStore` 不再持有 12 秒请求 token 字典。
- 新增 `HistoryState/HistoryResult/HistoryReducer`，接管加载状态、进度、分页 cursor、两页批次、累计事件/字节、循环检测和同步水位；领域水位使用 Unix 时间戳，避免共享 State 暴露 Foundation `Date`。
- 新增 `HistorySyncConfiguration`，集中每页 60 条、4 MiB 字节预算和每批 2 页配置；新增 `HistorySyncEngine`，接管 history 请求代际、网络超时、异步处理取消和晚到响应失效。
- 新增 `HistoryEventMerger`，保留严格递增事件 O(1) append 路径，并对乱序/重复 seq 二分插入或替换；既有 `ConversationHistoryRebase` 继续负责 history 与实时尾部去重重基。
- 保持 `ConversationProjector`、projection epoch、后台 rebase 和提交前 live tail catch-up 性能路径不变。
- 新增 7 项 RequestTracker/History 测试；结合既有 history rebase 重连测试，当前共 52 项 XCTest，覆盖分页、缺失/循环 cursor、乱序、重复 seq、取消、超时与 late generation。
- 阶段 4 和阶段 5 完成。完整命令 `xcodebuild test -quiet -project DeepSeekHarnessMobile.xcodeproj -scheme DeepSeekHarnessMobile -destination 'platform=iOS Simulator,id=8FC21323-CA69-49F7-B015-D0B761B1C40F' -derivedDataPath /tmp/dsh-mobile-kmm-test-derived CODE_SIGNING_ALLOWED=NO` 通过。
- 下一步：阶段 6.1，提取 `GatewayFrameRouter`。
