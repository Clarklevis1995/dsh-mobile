# AppStore 拆解与 KMP 迁移计划

> 状态：进行中  
> 当前分支：`feature/kmm`  
> 创建日期：2026-08-24  
> 最近更新：2026-08-28
> 当前任务：阶段 12 remediation 自动化门禁已通过；等待真实 Android 设备与 Mobile Gateway 人工冒烟验收

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
- [x] 9.5 每完成一个子系统，关闭该子系统的 Swift 写路径并保留一轮可回滚开关；人工验收通过后删除开关。

验收标准：iOS UI、旧安装数据和 Gateway 请求语义保持不变；每个已切换子系统只有 KMP 一个业务状态来源；自动化测试与会话、问题、模型、权限人工回归通过。

阶段 9 最终验收结论：2026-08-27 用户在 Android Studio 启动的 iPhone 17（iOS 26.5 Simulator）上连接真实 Mobile Gateway 完成人工清单；Gateway 使用已补齐 `commands/execute.images` 的本地修复版本。会话列表/持久化、Human Question、模型、权限、Context Usage、Stats、Agent Presets、默认配置、跨会话切换和断线恢复均通过。产品代码审计确认 SessionList、Human Question 和 SessionControl 只通过 KMP store 写入业务状态，没有运行时 Swift 回滚开关；Swift Reducer 仅供对等 XCTest 使用，按阶段 11.1 再统一删除。静态架构门禁会扫描三个旧 Reducer 类型符号在其定义文件以外的全部产品 Swift 引用，并以 `private(set)` 与源码审计标记约束已迁移 snapshot 的直接/复合赋值、常见容器原地变更和 `inout` 写边界；回滚开关检查属于基于 KMP/Swift 与切换语义组合词的启发式标识符审计，不宣称覆盖任意命名、反射或动态构造。收口后强制重跑 KMP 44 项与 Android 2 项测试，iPhone 17 / iOS 26.5 Simulator 全量 XCTest 96 项，均为 0 失败、0 跳过；`git diff --check` 通过。阶段 9 完成，阶段 10 尚未开始。

性能债务（已收口范围）：SessionControl 原先每次状态变化都跨 KMP/Swift 边界编解码全量 JSON snapshot（P3）；现已在阶段 10 前改为 schema 化增量 patch，清偿的是跨边界全量序列化和 `MainActor` 全量解码/比较成本。Kotlin 不可变 Map 更新及 Swift Dictionary 的低频 CoW 仍是平台内局部成本，不在本次协议优化范围；Conversation/Trajectory 的高频 token 流必须使用专用增量投影，严禁复用 SessionControl snapshot/patch 桥接模式。

#### 阶段 9 性能债务收口（阶段 10 前置）

- [x] P3.1 将 SessionControl 的已提交结果改为 schema 化增量 patch；全量 snapshot 仅用于初始化/显式诊断。
- [x] P3.2 session map 仅携带受影响 session 的 upsert/remove 分片，全局状态仅在发生变化时携带，请求关联信息作为小型原子 control 分片提交。
- [x] P3.3 Swift 在 `MainActor` 上先解码 patch 到临时快照并完成结构/effect/代际语义校验，全部通过后才原子发布；坏 patch 不更新 UI、不执行 I/O 并永久 fail-closed。
- [x] P3.4 补齐多会话分片、删除/清理、无变化零跨桥 payload 与坏 patch 原子失败的 Kotlin/Swift 回归测试。
- [x] P3.5 通过 KMP 相关测试、iOS 定向 XCTest 与 `git diff --check`，并明确阶段 10 的 token 流式路径禁止复用全量 snapshot 模式。

验收标准：KMP 仍是 SessionControl 唯一状态源；同一事务的 patch、generation 完成信号与 effect 保持原子语义；修改一个 session 不编码其他 session 的目录/Context/Stats/权限；无状态变化不传快照或 patch；删除和断线清理可正确收敛；任何缺字段、未知 schema、重复 upsert/remove 或语义不一致的 patch 都必须在 Swift 发布及平台 I/O 前 fail-closed。

Schema 演进与信任边界：schema 2 的 patch 顶层及所有业务 DTO 均采用字段白名单；本次为 drain/tombstone 控制语义新增 `drainingRequestKinds`，已按契约由 schema 1 提升到 schema 2。继续增加语义字段必须再次提升 schema，旧 Swift 客户端遇到未知 schema 或未知字段会在发布/I/O 前 fail-closed。`trust` 与 `pendingCalls` 是显式声明的透传 JSON 子树，不套用业务 DTO 白名单。Swift 的 `JSONSerialization` 会在结构预检前规范化对象，因此不宣称独立识别原始 JSON 重复 key；该 patch 仅由同进程、受信任的 KMP producer 生成，外部 Gateway 数据必须先经过现有协议解码/校验，不能直接作为 patch 注入。桥接 ABI 暂保留 `SharedSessionControlResult.snapshotJson` 名称：`committed == true` 时其 payload 是 `SharedSessionControlPatch`，初始化或显式诊断时才是完整 `SessionControlState`；patch 错误使用独立 `invalidPatch` 类别，后续 ABI 大版本可再统一重命名为 `payloadJson`。

性能债务验收结论：`SharedSessionControlStore` 仅在初始化/显式 `snapshot()` 返回全量快照；legacy/no-token/resolved no-op 返回空 payload，不调用 `snapshot()`。已提交事务返回 schema 2 patch，四类 session map 采用 upsert/remove，未变化 map 通过引用相等快速跳过 diff 扫描，全局字段按 changed 标记携带，generation/target/token 作为小型 control 分片。批量删除/归档采用单事务 `clearSessionsData`：被清 active 保留 generation 并进入 drain，真实 nil-session 单终态只消费不投影，再启动仍存活 queued；timeout/transport failure 清理 generation 并 quarantine 到断线；同批 queued 不会启动。Swift 对 clear patch 强制校验旧数据 removal 完整性，drain 终态只允许 control 变化，坏 bridge 不能遗漏删除或重新注入已清 session。100 与 1000 个无关 session 的单 session 更新 payload 大小保持一致。最终 `./gradlew :shared:allTests --rerun-tasks` 通过（iOS Simulator 52 项，0 失败；iOS x64 在 Apple Silicon 上按 Gradle 目标配置跳过），Android 单测 2 项通过；iPhone 17 / iOS 26.5 Simulator `GatewayProtocolTests` 105 项、全量 XCTest 110 项通过，均为 0 失败、0 跳过；无签名 iPhoneOS Release 构建与 `git diff --check` 通过。阶段 10 尚未启动。

#### 阶段 9.6～9.9：推送式 MVI 收口（阶段 10 前置）

- [x] 9.6 在 `commonMain` 定义稳定的 `Intent → Store → Event` 契约：Intent 只向 KMP 派发，KMP 以带递增序号和事务 ID 的 Event envelope 主动推送 state patch 与一次性 effect；dispatch 只返回接收/结构化错误，不再把业务状态作为返回值交给 Swift 拉取。
- [x] 9.7 先将 SessionControl 产品路径切换到订阅式事件：KMP 提交状态后广播 patch/effect，Swift `@MainActor` adapter 只订阅、校验顺序并发布 UI 投影；覆盖订阅建立、取消、重连、重复/乱序 event、观察者释放和 effect 恰好一次。
- [x] 9.8 将 SessionList 与 Human Question 切换到同一推送契约，删除产品路径对同步 mutation snapshot/result 的依赖；持久化和 Gateway I/O 继续由 Swift effect/persistence adapter 执行。
- [x] 9.9 增加静态架构门禁：产品 Swift 只能 `dispatch Intent` 和订阅 `Event`，不得从 mutation 返回值发布业务状态；执行 KMP/Android/iOS 全量自动化与真实 Gateway 人工回归后，再进入阶段 10。

推送式 MVI 验收标准：KMP 是唯一业务状态源；Swift 只持有可重建、只读的 UI 投影，不包含业务 Reducer；同一 KMP 事务的 state patch 与 effect 使用同一 envelope 保序，订阅取消后不得回调；初始化可发送一次 snapshot event，后续只发送增量 event；任何丢序、重复、未知 schema 或语义不一致 event 必须在 Swift UI 发布和平台 I/O 前 fail-closed。Kotlin `StateFlow/SharedFlow` 可以作为内部实现，但 Swift 桥接使用粗粒度可取消订阅接口，避免直接暴露复杂 Flow ABI。Conversation token 流在阶段 10 使用专用高频增量 event，不复用 SessionControl 的低频字典 patch。

9.6 验收结论：新增 `SharedMviEvent`、`SharedMviEventObserver`、可幂等取消的 `SharedMviSubscription` 与不携带业务状态的 `SharedMviDispatchResult`。内部 emitter 对初始化 snapshot 建立 sequence 基线，transition/error 使用单调序号和显式 transactionId；重入发布排队到当前事件完成后再投递，平台 observer 异常不会跨 Kotlin/Native 边界或阻断其他 observer。4 项契约测试覆盖 snapshot→transition 顺序、取消、重入保序和 observer 异常隔离；KMP iOS Simulator 测试集共 56 项通过，Debug Simulator 与 Release Device framework 均成功导出 observer/subscription ABI。9.7 之前产品 Store 尚未切换，现有同步路径保持不变。

9.7～9.8 实现结论：三个 KMP Store 均提供可取消订阅并主动发布 schema 2 Event；SessionControl 使用同一 envelope 携带增量 patch、事务 metadata 和 effect，Question 同一 envelope 携带状态与一次性 effect，SessionList 仅在实际变化时推送快照。Swift Adapter 在 `MainActor` 上校验 domain/schema/transactionId/连续 sequence 及既有领域语义不变量，任何重复、丢序或坏 payload 均在 UI 发布与平台 I/O 前永久 fail-closed。产品 AppStore 通过回调订阅发布 UI 投影，mutation 返回值只保留给无订阅测试 bridge 的兼容路径。

9.9 验收结论：新增产品源码门禁，要求三个 KMP Event 订阅入口持续存在，并禁止 AppStore 恢复“mutation 返回值 → 状态发布/effect”的产品路径；同步 result 仅允许携带无业务 payload 的 dispatch ack 或服务无订阅测试 bridge。强制重跑 KMP iOS Simulator 59 项、Android JVM 2 项，均 0 失败；Android Debug APK 构建成功（12,257,751 bytes）；iPhone 17 / iOS 26.5 Simulator 全量 XCTest 115 项通过，0 失败、0 跳过；iPhoneOS Release 无签名构建和 `git diff --check` 通过。用户随后确认真实 Gateway 人工清单全部通过，阶段 9 正式完成，可以进入阶段 10。

### 阶段 10：iOS Conversation、Trajectory 与 History 切换

- [x] 10.1 为 Swift 暴露增量 Conversation 投影接口，避免每个 token 跨语言复制完整消息数组。
- [x] 10.2 将流式 chunk 拼接、最终消息替换、工具调用和图片引用投影切换到 KMP。
- [x] 10.3 将 Trajectory request/assistant/tool/subtool 和 Token Usage 投影切换到 KMP。
- [x] 10.4 将历史分页状态、cursor 检测、history/live tail 去重和同步水位切换到 KMP。
- [x] 10.5 iOS 继续拥有网络 Task、超时触发、UIKit viewport 和后台执行；KMP 只计算状态与下一步 effect。
- [x] 10.6 对长会话执行性能、内存、滚动位置、重连和实时尾部人工回归。
  - [x] 10.6.1 消除 KMP Event/UI Intent 同步重入 SwiftUI view update 时产生的 `Publishing changes from within view updates is not allowed`，保持 Event 顺序和一次性 effect 语义。
  - [x] 10.6.2 修复 Trajectory 详情页 page-style `TabView` 在 sheet detent/安全区变化期间产生的 `UICollectionViewFlowLayoutBreakForInvalidSizes`。
  - [x] 10.6.3 调整 Agent 后台保活生命周期：前台不提前占用 `UIBackgroundTask`，进入后台时按活动 turn/question 申请，完成、回前台或过期后及时结束。
  - [x] 10.6.4 修复 `CFBundleDevelopmentRegion` 类型与可复现的越界颜色分量；区分并忽略 CandidateGeneration、无可见 context menu 等系统噪声。

10.6 Console 验收门禁：真实 Gateway 长回答过程中不得出现 KMP schema/sequence/fail-closed 错误、SwiftUI view-update 发布警告、分页布局非法尺寸或后台任务超过 30 秒警告；自动化测试、iPhoneOS Release 构建及 `git diff --check` 通过后，重新执行人工清单。

验收标准：流式过程不重复、不回退、不闪烁，也不会每个 token 全量发布 AppStore；历史加载期间实时尾部不停顿，滚动位置不跳变，性能无明显回退。

10.1～10.2 实现结论：新增高频专用 `SharedConversationStore`，KMP 按 session 持有唯一 projector；实时事件仅推送有序 `insert`、`append-text(delta)`、`remove` operation，历史/乱序修正才推送显式 replace baseline。Swift Adapter 在 `MainActor` 校验 schema、连续 event sequence、Intent/session/record sequence、operation shape 与目标 item，坏 patch 在 UI 发布前永久 fail-closed。AppStore 不再持有或调用 Swift `ConversationProjector`/`ConversationHistoryRebase`；WebSocket 原始事件作为 Intent 进入 KMP，KMP patch 镜像仍由 display link 一帧最多发布一次给 UIKit timeline。KMP 测试覆盖 delta-only payload、累计 4096 字符后 payload 恒长、最终消息替换、历史 replace、乱序拒绝、clear 与订阅取消；iOS 测试覆盖真实 KMP 订阅、坏 patch 零发布、图片/上下文/final/tool 投影对等和产品源码门禁。

10.3 实现结论：新增 `SharedTrajectoryStore`，KMP 按 session 持有原始事件与 Trajectory 唯一投影，request/assistant/tool/subtool、Token Usage 以及工具结果均由 KMP 生成。跨桥协议使用 `insert/remove/move/replace/update` 增量 operation；流式 update 只携带 subtitle delta 与新增 records，不重复复制累计文本。Trajectory 页面可见时，Swift 将实时事件按 display link 合并成一批 Intent，每帧最多触发一次 KMP 投影；页面不可见时停止高频投影，再次进入时以显式 baseline 恢复。Swift `TrajectoryTimeline` 只发布经严格 schema、sequence、Intent 和 operation 校验后的 UI 镜像，坏 patch 在发布前永久 fail-closed。KMP iOS Simulator 全量 67 项通过；iPhone 17 / iOS 26.5 Simulator 的 Trajectory 投影、坏 patch、按页面激活和产品源码门禁定向测试通过，`git diff --check` 通过。

10.4～10.5 实现结论：新增推送式 `SharedHistoryStore`，KMP 按 session 持有分页状态和有序 raw event 集合；`start/processing/page/live/timeout/cancel/clear` Intent 统一进入 KMP，下一页 `request-page` effect 与对应 state/event patch 在同一 MVI transaction 中发布。History page 与实时尾部按 sequence 去重，重复 seq 由 live lane 胜出；实时事件使用 `append/upsert`，分页基线使用显式 `replace`，同步水位只在 KMP 中推进。Swift `HistorySyncEngine` 继续拥有 20 秒 timeout 与 processing generation，`GatewayClient` 继续执行 WebSocket 请求，UIKit timeline/viewport、图片加载和后台任务均未下放。Adapter 严格校验 schema、sequence、Intent/session、event 顺序、outcome 与 effect，坏 patch 在 raw/UI 发布和网络 effect 前 fail-closed；四个 History UI 属性收紧为 `private(set)`，源码门禁只允许 KMP change 发布块写入。自动化结果：KMP 71 项、Android JVM 2 项、iPhone 17 / iOS 26.5 Simulator XCTest 124 项均 0 失败；等待 10.6 人工回归。

10.6.1～10.6.4 实现结论：KMP Event 继续同步进入 Swift Adapter 完成原子校验，但 AppStore 的 UI 镜像与同事务 effect 改由下一次 MainActor FIFO drain 发布，避免 UI Intent 与 SwiftUI view update 同栈重入；测试等待同一生产队列，不提供同步旁路。Trajectory 详情页三个 page-style `TabView` 改为原生条件页签，移除 UIKit `PagingLayout` 非法尺寸来源；`TrajectoryTimeline` 以 backing `Published` 初始化基线，不在 View body 首次获取时发布。Agent 前台仅维护 turn/question 活动计数，真正进入后台时才申请 `UIBackgroundTask`，回前台、完成或过期即释放。Info.plist 显式输出字符串 `CFBundleDevelopmentRegion=en`，动画粒子颜色/alpha 在构造前钳制到 `0...1`。强制重跑 KMP 71 项、Android 2 项、iPhone 17 / iOS 26.5 Simulator XCTest 125 项，均 0 失败、0 跳过；iPhoneOS Release 无签名构建、产物 Info.plist 类型检查与 `git diff --check` 通过。用户随后确认真实 Gateway 业务操作与 Console 复验均未发现其他问题，阶段 10 正式完成。

### 阶段 11：iOS 重复实现清理与架构收口

- [x] 11.1 删除已被 KMP 取代的 Swift DTO、Reducer、Projection、History 纯逻辑及其重复 fixture。
- [x] 11.2 保留 Swift 平台层：ObservableObject/SwiftUI、UserDefaults、Keychain、WebSocket、文件、图片、UIKit 和后台任务。
- [x] 11.3 将 `AppStore` 收敛为 snapshot 发布、用户 intent 转发和平台 effect 执行器。
- [x] 11.4 清理迁移开关、影子比较代码和过渡映射，更新架构文档与依赖图。
- [x] 11.5 执行 iOS 全量自动化、完整人工回归以及 Release Device framework 构建。

验收标准：iOS 产品业务逻辑以 KMP 为唯一来源，Swift 不再维护对应的平行实现。

11.1～11.5 实现结论：删除 Swift `SessionListReducer`/`QuestionReducer`/`SessionControlReducer`/`HistoryReducer`、Conversation/Trajectory 领域投影和重复对等 fixture；删除 `SharedShadowFacade`/`KMPShadowValidator` 及 AppStore 的 `usesEventStream` 产品分支。Swift 仅保留 UI DTO/Timeline、Intent 值、Event 校验/发布和平台 I/O；不支持 Event 订阅的 bridge 仅作为 Adapter 故障注入测试 seam，不进入 AppStore 产品分支。`ARCHITECTURE.md` 和 `Docs/kmp-development.md` 已更新为 Intent→KMP→Event/effect 单向 MVI。阶段 11 人工回归发现并最终修复延迟 Session Event 与同步导航竞态：导航等待 UI 镜像提交后再 push，viewport 以 Session ID 硬隔离，冷加载层保持不透明并在对应 diffable snapshot 完成后撤下。最终门禁：KMP 67 项、Android JVM 2 项、iPhone 17 / iOS 26.5 Simulator XCTest 106 项均为 0 失败；Android Debug APK 与 iPhoneOS Release 无签名构建成功，`git diff --check` 通过。用户确认真实 Gateway 人工验收通过，阶段 11 正式完成。

### 阶段 12：Android 真实 Gateway 与平台服务

- [x] 12.1 定义共享 Gateway transport、preferences、credential、attachment cache 和 clock 接口。
- [x] 12.2 在 Android 实现 OkHttp WebSocket、重连、认证/配对、请求关联和网络状态处理。
- [x] 12.3 使用 Android Keystore/DataStore 实现凭据与配置持久化，敏感信息不得进入日志或普通 preferences。
- [x] 12.4 实现 Android 图片选择、预处理、附件缓存和上传/下载 effect。
- [x] 12.5 接入 Android 生命周期、前后台连接策略和必要的前台服务/通知策略。
- [ ] 12.6 建立 fake transport 集成测试和真实 Gateway 开发环境冒烟测试。
  - [x] 12.6.1 commonTest 与 Android JVM fake transport 集成测试。
  - [ ] 12.6.2 按 `Docs/kmp-stage12-android-gateway-verification.md` 完成真实设备/Gateway 冒烟。

验收标准：Android 能完成配对、重连、会话拉取、消息收发、流式事件和历史加载，断网恢复不丢状态。

12.1～12.6.1 实现结论：`commonMain` 新增 transport、非敏感 preferences、安全 credential、attachment cache、network monitor 和 clock 边界，以及统一串行化的 `GatewayRuntime`。Android OkHttp 复制既有 iOS 已验证的协议字段；frame/failure 共用有序且溢出显式失败的 transport 通道，文本帧用无副本 UTF-8 长度计算并限制为单帧 16 MiB、8 帧、累计 24 MiB，connection/request generation 与 timeout 隔离晚到响应。Runtime 到平台投影改为单消费者真实背压队列，同时限制 8 个事件和 48 MiB 估算保留量；附件落盘后只发布移除 Base64 data 的清洗 frame，避免第二级事件流继续持有大附件。请求 lane 按协议语义分流：message/question 忙时明确拒绝并保留 UI 输入，attachment 使用真正 FIFO deque，history/session 等可幂等读取 active + latest queued；hello 自动恢复会按 response kind 与 deferred 回放去重，替换、超时、取消和请求失败均携带目标并通知共享 Store 结束 loading。发送消息先在 Main 捕获不可变 session/draft/images/input generation，accepted 后仅在 generation、内容和 session 仍一致时清空，因此点击后继续编辑、换图或切 session 不会误发/误清。可恢复断网在有界窗口保留活动 turn/后台保活并自动重连；恢复超时会关闭 transport、废弃 generation，后台最后一个 `turn/end` 或 message timeout 会立即废弃连接并进入 `SUSPENDED`，前台再自动重连；401/4003 等不可恢复失败会阻断自动重连，直到新用户连接意图；`WAITING_FOR_NETWORK` 的同 endpoint stored-connect 保持幂等且不取消恢复期限。普通配置进入 DataStore，token/device id 经 Android Keystore AES-GCM 后进入独立 no-backup DataStore；connection spec 先完成凭据快照再分配 generation，读取失败关闭旧 transport；paired token 在事件发布前剥离，敏感 DTO 的 `toString` 均脱敏。Android 用 `SharedHistoryStore` / `SharedConversationStore` 合并分页 history 与 live 增量；MVI adapter 先严格解码并在临时状态验证 domain/schema/sequence/transaction/patch/operation/effect，另外维护 per-session history/conversation 水位，非显式 clear 的 history replace 也不得回退 tail；全部成功后才一次提交并执行 effect，坏 payload、回退或非法重复均零状态/零 I/O 且永久 fail-closed，transaction 去重窗口有界为 64。attachmentId 未经协议证明全局唯一，缓存、终态和 UI 内部均使用 `sessionId + attachmentId` 复合键；附件成功提交 7 天/32 MiB LRU cache 后才回读。UI 由 LazyColumn 实际可见项驱动请求，仅持有 16 MiB 访问型缩略图 LRU 和最多 256 项状态；淘汰 key 会同步为 `DEFERRED` 且只允许显式重试，避免同屏超预算抖动，采样尺寸使用 `LocalWindowInfo.containerSize` 的实际像素宽与 240dp 显示高度，不保留附件原始 ByteArray。图片输入、数量、总字节与 Base64 均有硬上限，并在 IO dispatcher 完成 bounds/sample decode。Application graph 现在同时持有单例 Runtime、Projection 和 StateHolder，Activity ViewModel 仅作为 UI facade；所有 Frame、select、fixture、reset、history terminal 的 Projection mutation 与对应 Main snapshot publish 都进入同一 `AndroidProjectionActor` 有序提交，旧计算不能晚发布覆盖新 selection。Gateway decode/MVI 使用单线程后台 dispatcher，后台错误只切 Main 发布 Compose state，Activity finish/reopen 不丢 replay 间隙状态，重复 stored-connect 不重开 socket或清 turn；活动 turn 使用 `connectedDevice` 前台服务。release 禁止 cleartext，仅 debug 允许 LAN `ws://` 开发冒烟。fake/JVM/instrumentation 测试与构建结果不替代真实 Gateway 人工验收。

### 阶段 13：Android 原生产品 UI

- [x] 13.1 建立 Compose Navigation、应用级 State Holder 和窗口尺寸适配。
- [x] 13.2 实现配对/连接、Workspace、Session 列表和 Settings 页面。
- [x] 13.3 实现 Conversation、流式消息、Markdown、图片附件、工具结果和输入区。
- [x] 13.4 实现 Human Question、模型选择、权限、Context Usage、Stats 和 Agent Preset UI。
- [x] 13.5 实现 History 分页、Trajectory、未读、归档、搜索和错误恢复交互。
- [x] 13.6 建立 Compose UI 测试、截图测试和关键无障碍语义。

验收标准：Android 关键产品流程与 iOS 协议语义一致，同时保持 Android 原生交互和生命周期设计。

13.1～13.6 实现结论：Android 产品入口已从阶段 12 的 Gateway 开发冒烟页切换为 Compose Navigation 驱动的 Workspace、Conversation 与 Settings 三层页面，复用 Application 级 State Holder/Projection，并按窗口宽度限制内容列。UI 以 iOS `RootView`、`WorkspaceView`、`ConversationView`、`SettingsView`、`HumanQuestionView`、`TrajectoryView`、`Theme`、`Glass` 和 `HarnessAnimatedBackground` 源码为逐项基准：还原品牌头、Workspace 选择与会话列表、会话/轨迹分段、消息类型、Markdown、工具与推理状态、图片、输入器、Human Question、模型/权限/Agent Preset、Context Usage、Stats、分页、搜索、归档和错误恢复。主页背景把 Metal 流体公式翻译为 API 33+ AGSL RuntimeShader，并复用同一鲸鱼 SVG 路径、60×60 粒子采样、漂移/尾迹/闪烁公式、技术网格与底部渐变；API 24～32 使用受控降级，动画遵循生命周期与系统动画缩放。Compose 设备测试覆盖主页关键语义和离线新建会话；动态背景与液态玻璃使用最终 APK 的真实前台连续截图及卡片内部区域哈希验证，避免 instrumentation 测试时钟冻结实时协程后产生假阴性。API 35 模拟器另外人工截图核对主页、工作区面板、会话页和设置页的明暗系统栏、可读性与层级。阶段 13 自动化和截图证据不替代阶段 12.6.2 的真实 Gateway 全业务人工冒烟。

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

阶段 11 起，iOS 领域验证必须执行：

- KMP `commonTest` 与平台 Adapter 协议/fail-closed 测试，不得恢复 Swift 平行实现或影子差异路径；
- iOS 全量 XCTest；
- `shared:allTests` 与 Android JVM 单测；
- 对应子系统的 iOS 人工回归。

阶段 12 起增加 Android fake transport 集成测试；阶段 13 起增加 Compose UI 测试和真实 Gateway 冒烟测试。

## 当前已知风险

- Conversation timeline 使用 UIKit 直接订阅优化，不能退化为每 token 发布整个 AppStore。
- iOS 后台任务和 Gateway 重连相互影响，必须留在平台/effect 层。
- Swift 与 Kotlin Flow/异常的互操作需要粗粒度 facade，避免直接暴露复杂内部 API。
- Kotlin/Native 未声明并捕获的异常跨越 Swift 边界可能导致进程终止，facade 必须返回显式错误结果。
- Conversation 流式投影若跨桥接层频繁复制完整列表，会引入卡顿和内存峰值，必须保留增量接口。
- Android 真实 Gateway 接入涉及凭据、后台连接和附件文件，必须分别使用 Keystore、生命周期策略和受控缓存。

## 变更记录

### 2026-08-28

- 修正并细化阶段 13 Android 鲸鱼点阵：Android `Path`/Compose 同为 Y 轴向下坐标，不再照搬 iOS `CGContext` 像素行翻转；进一步对照 DeepSeek Harness 官网运行代码，把完整 `24×18` SVG 等比放入抗锯齿 `60×60` 位图采样画布，按 0.2 亮度阈值生成粒子并使用奇偶填充保留腹部、鳃部等内部轮廓，不再把外轮廓分别拉满正方形。工作区选择卡、主按钮和圆形控制同步增强 Backdrop 曝光/饱和度、定向高光、折射、内阴影与亮边。新增设备回归验证 24:18 比例和腹部空洞；最终 Android JVM 24 项、API 35 设备 8 项均 0 失败，Lint 0 issue，assemble 与 `git diff --check` 通过，Debug APK 为 14,379,644 bytes。
- 阶段 13 追加 Android 液态玻璃：参考 `Kyant0/AndroidLiquidGlass`，接入与当前 Kotlin/Compose 同代的 Apache-2.0 `Backdrop 1.0.6`，由 `DshLiquidGlassHost` 单独录制主页动画背景；Workspace 卡片、新建按钮、顶栏圆形按钮、搜索框和空状态统一使用真实背景采样、vibrancy、blur、高光/阴影，并在 API 33+ 增加 lens、depth 和轻微色散，API 24～30 保留安全降级。动态背景 30fps 同步生成 20fps 纯 DrawModifier 失效信号，不进入业务 Composition；最终 APK 前台连续截图中玻璃卡片内部两帧哈希不同。强制门禁为 KMP 89 项、Android JVM 24 项、API 35 设备测试 7 项均 0 失败，Lint 0 issue，Debug APK 14,379,644 bytes，`git diff --check` 通过；第三方声明见 `Docs/third-party-notices.md`。
- 完成阶段 13 Android 原生产品 UI：以 iOS SwiftUI、Theme、Glass、鲸鱼 SVG 和 Metal shader 源码为基准，Compose 一比一还原 Workspace、Conversation、Settings、Human Question、Trajectory 及主页动画，产品入口切换为 Navigation 三层结构；补齐 Workspace/Session 搜索归档、History 分页、Markdown/图片/工具、模型/权限/Context/Stats/Agent Preset 和错误恢复交互。主页在 API 33+ 使用 AGSL 复刻流体公式，并保留同源 60×60 鲸鱼粒子、网格、尾迹、闪烁、生命周期/移除动画适配及 API 24～32 降级。Pixel 9 AVD / Android 15 / API 35 最终强制门禁为 KMP 89 项、Android JVM 24 项、设备测试 8 项均 0 失败，Lint 0 issue，Debug APK 14,281,340 bytes，`git diff --check` 通过；人工截图核对主页、工作区面板、会话页、设置页并修复面板明暗可读性。阶段 12.6.2 真实 Gateway 全业务人工冒烟仍独立保持未完成。
- 阶段 12 真实设备验收补充 Debug 安全诊断日志：统一 Tag `DshGateway` 覆盖用户 intent、transport open/send/frame/failure、Runtime state/frame/request 生命周期以及前后台/前台服务事件；只记录状态、协议 kind、请求类型、generation、关闭码、字节数和布尔关联标记，API 不接受 endpoint、凭据、正文或任何 Session/附件 ID，未知协议值统一显示 `unknown`。Release 通过 debuggable 标志关闭诊断器。新增脱敏回归后 Android JVM 共 24 项，Lint 0 issue，当前 Debug APK 13,785,523 bytes；OnePlus 8 / Android 真机已验证 application→bearer opening→open→hello→workspaces→sessions→CONNECTED 日志可见且不含敏感字段。
- 阶段 12 真实 Gateway 首轮人工操作发现点击“连接”后 `AndroidGatewayClock.delay()` 因成员函数与导入的协程函数同名而递归调用，触发 `StackOverflowError`。现已改为完全限定调用 `kotlinx.coroutines.delay`，并新增直接调用真实 Clock 的 JVM 回归测试。Android JVM 23 项、Lint、Debug APK 构建均通过；Pixel 9 AVD 点击连接后进程存活，错误级 Logcat 无输出。宿主 Gateway 仅监听 `127.0.0.1:3080`，为模拟器配置 `adb reverse tcp:3080 tcp:3080` 后已通过保存凭据进入 `CONNECTED`、收到 `sessions` 并展示真实会话列表。首次配对、历史/消息、断网/后台及附件业务验收仍待继续，12.6.2 未勾选。
- 完成阶段 12 第五轮 remediation：Android Projection mutation 与 Main snapshot publish 收口到同一 actor，增加输入 generation 条件清理、后台 message timeout idle suspension、history replace tail 防回退，并以 `LocalWindowInfo.containerSize` 消除 Configuration screen size Lint 警告。统一门禁为 shared 89 项、Android JVM 22 项、API 35 instrumentation 5 项均 0 失败，Lint 0 issue，release merged manifest 禁止 cleartext，Debug APK 13,769,139 bytes；最终安装和冷启动成功。真实 Gateway 12.6.2 仍未执行。
- 完成阶段 12 第四轮 remediation：Runtime 事件队列加入事件数/字节双边界和真实背压，attachment event 清除 Base64；Android Gateway decode/MVI 移至单线程后台 dispatcher，Application graph 持有单例 Projection/StateHolder；补齐后台最后 turn 挂起、WAITING 幂等、凭据失败关闭、per-session MVI 水位、复合附件键、缩略图淘汰状态同步和可注入产品组合测试。统一门禁为 shared 88 项、Android JVM 19 项、API 35 instrumentation 5 项均 0 失败，Lint 0 issue，Debug APK 13,769,139 bytes；最终安装和冷启动成功。真实 Gateway 12.6.2 仍未执行。
- 完成阶段 12 第三轮 remediation 自动化门禁：统一 `--rerun-tasks` 的 `:shared:allTests` iOS Simulator 84 项、Android JVM 17 项均 0 失败；API 35 instrumentation 高分辨率压缩图缩略与 Activity 配置重建测试 2 项通过；Android Lint 0 issue，Debug APK 构建成功（本次 13,736,371 bytes），`git diff --check`、manifest 和敏感信息源码扫描通过。Pixel 9 AVD / Android 15 / API 35 成功安装并冷启动本轮最终 APK；这不是实际 Mobile Gateway 结果。12.6.2 仍须按 `Docs/kmp-stage12-android-gateway-verification.md` 人工执行。

### 2026-08-27

- 完成阶段 11.5：用户确认第二轮历史遮罩与 Session 闪帧修复人工验收通过，阶段 11 清单全部收口。最终强制门禁为 KMP 67 项、Android JVM 2 项、iOS Simulator XCTest 106 项，均 0 失败；Android Debug APK、iPhoneOS Release 无签名构建和 `git diff --check` 通过。阶段 12 尚未开始。
- 阶段 11 首轮历史遮罩与 Session 闪帧修复经人工复验确认无效，原记录撤回。进一步对比阶段 10 checkpoint 后定位到阶段 11 延迟 Event 发布与同步导航之间的竞态：KMP 已选择新 Session，但 Swift UI 镜像尚在下一 MainActor turn 排队，目标页面首帧因而读取旧 Session。第二轮改为导航等待选择 Event 提交成功后再 push；Conversation viewport 以 Session ID 硬隔离 controller，冷加载层保持不透明，且只在对应 Session 的 diffable snapshot 完成后报告内容可见。新增导航提交屏障与 viewport 可见性两项测试；iPhone 17 / iOS 26.5 Simulator 全量 XCTest 106 项通过，iPhoneOS Release 无签名构建与 `git diff --check` 通过。仍等待真实历史 Session 人工复验，不提前标记通过。
- 阶段 11 人工回归发现 Session 切换时，新 Timeline 的 diffable snapshot 提交前会短暂保留上一 Session cell。Viewport 现在在 Session ID 变化的同一调用栈内隐藏旧 snapshot，以 `sessionGeneration` 屏蔽旧异步 completion，仅当当前 Session snapshot 原子提交后再显示。定向 XCTest 覆盖身份屏障与新 snapshot 解锁，等待快速切换人工复验。
- 阶段 11 人工回归发现历史 Session 首批 Timeline 已由 UIKit 直接显示时，外层 SwiftUI 未感知空/非空边界，导致全屏加载遮罩滞留并与内容重叠。`ConversationViewport` 现在只在空↔非空变化时上报 session-scoped 边缘事件，首批内容可见即撤除全屏遮罩，后续 token 不会重绘整页。新增定向 XCTest 通过，等待真实历史 Session 人工复验。
- 完成阶段 11.1～11.4 与 11.5 自动化：删除 iOS 重复 Reducer/Projection/History fixture、KMP Shadow facade/validator 和 AppStore Event 迁移分支；AppStore 只转发 Intent、发布 KMP Event UI 镜像和执行平台 effect。更新架构/开发文档并新增 `Docs/kmp-stage11-manual-verification.md`。KMP 67、Android 2、iOS Simulator 104 项测试全绿，Android APK 和 iPhoneOS Release 构建成功；等待人工验收勾选 11.5，不进入阶段 12。
- 完成阶段 10.6 Console 阻断项代码修复与自动化门禁：KMP→AppStore UI Event/effect 延迟到下一 MainActor FIFO drain；移除 Trajectory 详情 `PagingLayout`；后台任务改为按真实后台生命周期申请；修复 DevelopmentRegion 类型并钳制动画颜色。KMP 71、Android 2、iOS 125 项测试及 iPhoneOS Release 全绿，等待真实 Gateway 再次人工观察 Console 后勾选 10.6。
- 完成阶段 10.4～10.5：History 分页 Reducer、cursor/循环检测、history/live tail 去重和同步水位切换到 KMP 推送式 MVI；Swift 保留 Gateway、超时/代际、UIKit、图片和后台执行。KMP 71 项、Android 2 项、iOS 124 项全绿，进入阶段 10.6 人工回归。
- 完成阶段 10.3：Trajectory request/assistant/tool/subtool 与 Token Usage 已切换到 KMP 唯一投影；跨桥只推送节点增量 operation，实时事件在页面可见时按帧批处理，Swift 只维护可重建 `TrajectoryTimeline` UI 镜像。KMP 67 项及 iOS 四项定向门禁通过；继续阶段 10.4 History。
- 完成阶段 10.1～10.2：Conversation 高频路径采用 KMP `insert/append-text(delta)/remove/replace` 推送协议，AppStore 已移除 Swift projector 状态与产品调用；WebSocket 事件及历史基线均以 Intent 进入 KMP，Swift 只校验 patch 并按 display link 节奏发布 UIKit timeline。自动化覆盖 payload 恒长、最终消息替换、历史/乱序、图片/工具投影对等、坏 patch fail-closed 与源码门禁；继续阶段 10.3。
- 用户确认 9.7～9.9 推送式 MVI 真实 Gateway 人工验收全部通过；SessionList、Human Question、SessionControl 及完整 Intent→Event 链路无重复 I/O、无 sequence/patch/effect fail-closed。9.9 已勾选，阶段 9 正式完成，开始阶段 10.1。
- 完成阶段 9.7～9.9 的推送式 MVI 代码与自动化门禁：SessionList、Question、SessionControl 的产品状态/effect 改由 KMP schema 2 Event 主动推送，Swift 只 dispatch Intent、校验 Event 并发布可重建 UI 投影。覆盖订阅基线、去重、取消、事务 effect、重复 sequence fail-closed 和源码架构门禁；KMP 59 项、Android 2 项、iOS 115 项全绿，Android APK 与 iPhoneOS Release 构建成功。新增 `kmp-stage9-mvi-manual-verification.md`，等待真实 Gateway 人工验收后再勾选 9.9。
- 收口阶段 9 SessionControl 跨桥性能债务：已提交事务改为 schema 2 增量 patch，全量 snapshot 仅初始化/诊断；四类 session map 按 upsert/remove 分片，无变化零 payload，100/1000 无关 session 的单 session payload 保持恒定。批量 clear 使用 drain/tombstone 消费真实 nil-session 终态，严格拒绝遗漏 removal、跨 session/跨 kind 注入以及 drain 业务回写。最终 KMP 52 项、Android 2 项、iOS 全量 XCTest 110 项通过，iPhoneOS Release 构建成功。
- 用户确认后续架构采用推送式 MVI：Swift 只向 KMP dispatch Intent，KMP 以保序 Event envelope 主动推送 state patch/effect，Swift 只保留可重建 UI 投影。新增 9.6～9.9 前置阶段，完成三个基础领域订阅式迁移和架构门禁后再进入阶段 10。
- 完成 9.6 推送事件契约：新增 KMP Event/Observer/Subscription/DispatchResult 与同步保序 emitter，覆盖初始化基线、事务序号、重入队列、幂等取消和平台异常隔离；KMP 56 项测试及 iOS Debug/Release framework 导出通过。下一步将 SessionControl 产品路径从同步 mutation result 切到订阅 Event。
- 用户确认阶段 9 最终人工核验通过：设备为 iPhone 17 / iOS 26.5 Simulator，Android Studio 启动 iOS App，连接真实 Mobile Gateway；Gateway 使用已安装到本机 `web` profile 的本地修复版本。此前模型/权限关联与 `commands/execute.images` 兼容问题均已消除，权限切换、控制配置、跨会话和断线恢复结果正常。
- 完成阶段 9.5 产品写路径审计：`AppStore` 的 SessionList、Human Question、SessionControl mutation 分别只调用 `KMPSessionListStoreAdapter`、`KMPQuestionStoreAdapter`、`KMPSessionControlStoreAdapter`；产品代码在三个旧 Reducer 定义文件以外不引用其类型符号。已迁移的 `@Published` snapshot 属性统一收紧为 `private(set)`，源码审计标记门禁只允许初始化及对应 KMP snapshot 发布块执行直接/复合赋值、常见容器原地变更或 `inout` 写入。DEBUG `KMPShadowValidator` 仅做只读路由对比，不是状态回滚路径。
- 保留三个 Swift Reducer 及其 DTO 作为 Swift/KMP 对等 XCTest 基准，产品路径不调用；根据阶段 11.1 再与重复 fixture 一并删除，避免阶段 9.5 扩大为阶段 11。新增静态架构门禁，覆盖 Reducer 类型别名、换行调用、直接/嵌套 snapshot 属性赋值、常见容器原地变更和 `inout` 写入等回流形式；回滚开关检查采用 KMP/Swift 与 use/enable/flag/fallback 等切换语义组合词的启发式标识符审计，不将其描述为可识别任意重命名。强制重跑 `:shared:allTests :androidApp:testDebugUnitTest --rerun-tasks`，KMP 44 项和 Android 2 项均通过；iPhone 17 / iOS 26.5 Simulator 全量 XCTest 96 项通过，0 失败、0 跳过；`git diff --check` 通过。阶段 9 已完成，等待用户决定是否进入阶段 10。
- 第三轮人工核验确认模型/权限控制已进入真实请求链路，但切换权限被宿主拒绝：`commands/execute` 的新版参数 descriptor 要求必填 `images`。问题位于同级 `dsh-plugin-mobile-gateway`，并非 KMP Reducer/effect；Gateway 已在 `/permission` 调用中补充 `images: []`，其 53 个 dispatch 用例及鉴权、配对、LAN 全套测试通过，并已用本地 `file:` 源安装到本机 `web` profile。需完整重启实际 Gateway 后继续人工核验。
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
