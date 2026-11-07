# 移动端本地 DSH 实施方案与 Kotlin 重写取舍

日期：2026 年 11 月 6 日。适用项目：Whaleness 的 SwiftUI iOS 客户端、Compose Android 客户端和 KMP 共享层。

建议采用 **KMP 管理客户端状态和运行时入口，嵌入官方 DSH 的 Node 引擎，平台层提供移动端能力**。先交付文件工作区内的本地 Agent，再添加虚拟 Shell 和可选 Linux。当前不建议为了跨平台而完整重写 DSH。

如果产品目标改为独立的轻量手机助手，只需要少量原生工具，不要求 DSH 插件、会话和运行行为兼容，Kotlin 原生 Agent 才更适合作为主线。两种目标应分别估算，不能拿简化聊天循环与完整 DSH 比较。

这里的“本地”是 Agent 调度、工具和会话在手机执行，模型仍使用网络 API；不包含离线大模型推理。本方案交付设计和验收条件，新增接口、目录与能力均待实施。

## 当前基础与版本选择

当前工程已具备以下复用条件：

- `shared/commonMain` 包含 Gateway 协议、请求管理、审批与提问状态、历史同步和会话投影，没有 Provider 请求循环和本地工具执行内核。
- Android 的网络接入使用共享 `GatewayRuntime`；iOS 仍由 `GatewayClient.swift` 管理传输，经过 `GatewayFrameRouter` 和 `KMPSharedAdapter` 更新界面状态。
- `GatewayTransport` 已有连接代次和有序帧合同，可复用；本地化不要求先把 iOS 网络完整迁入 KMP。
- 相邻 `dsh-plugin-mobile-gateway` 当前为 `0.12.0`，适配 DSH `0.2.2-rc.1` 和 Session V4。其宿主适配器依赖 `webServer`、`typertGateway`、`connection` 和 `agentDefaultModel`，不能只启动模型循环就获得现有 App 的协议。
- 当前最低系统版本为 iOS 16.1、Android API 24，Android target API 36。保持现有产品基线，新增执行后端可以按设备单独声明可用性。

DSH 上游基线选 `0.2.2-rc.1`，源码提交 `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159`，要求 Node `>=24.9.0`。NodeMobile `24.22.1-0` full 作为首轮候选，固定源码、归档摘要及原生扩展 ABI。旧调研中的 DSH `0.2.1-rc.2`、Node 22 要求不能用于本轮锁版本。[DSH package.json](https://github.com/deepseek-ai/deepseek-harness/blob/fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159/package.json)

参考的 DSH Go 提交固定为 `745cc73171287e606339969173b3dcd625847962`。该提交的 [iOS compile](https://github.com/ccch1mneyyy/dsh-Go/actions/runs/42185613696) 和 [iOS guest](https://github.com/ccch1mneyyy/dsh-Go/actions/runs/42185613684) 均成功，包含模拟器内引擎和 guest 执行。但它没有验证我们的 Gateway、签名产物和真机资源预算；这几项仍是本项目的准入条件。

## 两条路线怎样比较

| 维度 | 内嵌官方 DSH 与平台适配 | Kotlin Multiplatform 重写执行内核 |
| --- | --- | --- |
| 模型调用与 Agent 行为 | 复用固定版本的实现，维护移动 profile | 自建 Provider、流式协议、工具调用和调度 |
| DSH 升级 | 检查依赖和适配补丁，运行契约回归 | 分析行为差异并逐项移植，长期维护兼容层 |
| 会话与插件 | 有机会保留官方格式及服务，桌面插件仍需筛选 | 格式相同不保证行为相同；JS 插件仍需 JS/Node 或另写实现 |
| 原生构建 | Node、N-API 和平台桥增加复杂度 | 可去掉 Node；Shell、图片、系统权限仍有平台实现 |
| 内存和启动 | 额外承担 Node/V8，必须真机测量 | 有降低开销的机会，改善幅度需同等功能实测 |
| iOS 后台和系统限制 | 受系统控制 | 同样受系统控制，Kotlin 不赋予额外权限 |
| 首版主要风险 | 模块兼容、生命周期、签名、资源预算 | 范围膨胀、语义偏差和持久化恢复缺陷 |
| 适用目标 | 在手机上使用兼容的 DSH | 自主定义、能力受限的移动 Agent |

Kotlin 重写的成本不在 `while (toolCalls.isNotEmpty())`，而在多 Provider 的事件差异、工具执行与取消竞争、审批恢复、上下文压缩、排队与插话、子 Agent，以及崩溃后的事件一致性。当前共享层能复用客户端状态规则，不能直接替代这些宿主服务。

另外，改用 Kotlin/JS 再调用 npm 版 DSH，仍需要 Node 宿主；它不能达到移除 Node 的目的。Kotlin/Native 可以通过 C ABI 接入原生执行器，但不会自动提供 Node API 或 Linux 环境。[Kotlin C 互操作](https://kotlinlang.org/docs/native-c-interop.html)

## 推荐架构

```text
SwiftUI / Compose
        │
现有 KMP Store、协议与历史投影
        │
Host 选择：远程主机 / 本机
        ├─ 远程 → 现有 Gateway
        └─ 本机 → LocalRuntimeController
                         │
               回环 WebSocket 与本地 Gateway
                         │
               NodeMobile + DSH mobile profile
                         ├─ 网络模型 Provider
                         ├─ Session V4 与工具审批
                         ├─ App 内部工作区与原生图片处理
                         └─ 后续：虚拟 Shell / iSH / Android 执行器
```

KMP 负责运行时状态、能力和主机选择；Swift/Objective-C++ 与 Kotlin/JNI 负责运行库、系统生命周期和原生能力。DSH 继续拥有 Agent 运行状态与权威会话事件，KMP 只维护客户端投影，避免出现两份调度状态。

首轮只创建一个 Node runtime，管理多个会话但仅允许一个活跃本地回合。Node 初始化、libuv 和文件安装均离开 UI 线程。多会话并行要在内存、取消和会话隔离验证后另开阶段。

### 接入现有 Gateway

第一版使用 `127.0.0.1` 动态端口和每次启动生成的随机鉴权凭据。引擎就绪后，通过 App 内部桥交付端点；端点不能靠扫描端口或日志发现。稳定的 `localHostId` 用于缓存归属，端口和 runtime generation 不作为数据身份。

从现有 Gateway 提取本地组装入口，保留会话、审批、提问、文件、模型配置和历史协议，移除 LAN 发现、RTC、Tailscale、Cloudflare、桌面 helper 和远程安装入口。若现有插件的服务闭包过大，提取请求分发与宿主适配模块，不能引入整个桌面运行环境。

保持控制与会话通道的现有协商合同，以及 requestId、cursor、订阅代次和临时增量的语义。能力不足返回明确的 unsupported，不伪造成功数据。Android 的 `GatewayTransport`、iOS 的 `GatewayClient` 都应区分本地连接和外网状态：飞行模式可以打开本地历史、浏览文件；模型请求另行报告网络不可用。

回环链路通过后台恢复验收后即可交付。只有实际证明监听回收、复制或延迟影响体验，才替换为进程内消息桥；届时继续使用同一帧合同和背压规则。

### 建议的模块边界

| 位置 | 职责 |
| --- | --- |
| `shared/.../runtime/` | `LocalRuntimeState`、能力快照、主机选择和生命周期接口 |
| `DeepSeekHarnessMobile/Runtime/` | Swift 控制器、Objective-C++ 桥、framework 管理和后台处理 |
| `androidApp/.../runtime/` | Kotlin 控制器、JNI 与运行进程管理 |
| `runtime/mobile/` | JS 启动入口、mobile profile、Gateway 组装和适配补丁 |
| `scripts/runtime/` | 锁版本、构建、归档检查、签名验证和依赖清单 |

公共接口先只覆盖 `prepare/start/stop/reset`、状态订阅与能力查询。状态至少区分未准备、准备中、启动中、可用、停止中、需重启应用和失败；进程启动成功不等于 Host 可连接，必须等完成真实鉴权探测再进入可用状态。

`reset` 只在执行器完全停止且持久化关闭后运行，并要求明确选定工作区和保留范围。内核未能证明退出时不能删除其目录。所有异步结果携带 generation，旧运行的回调不能改变新运行状态。

## 首版范围与系统能力

首版交付一个模型 Provider，保留流式聊天、读写和搜索工作区文件、图片输入、工具审批、用户提问、停止与历史恢复。文件执行范围先限定 App 内部目录，使用导入副本和显式导出；外部目录的 security-scoped URL 或 Android SAF 原目录编辑延后。

优先复用 DSH 的 Agent、会话和持久化；为原生文件锁、搜索和图片处理提供替代实现。构建时生成实际依赖清单，逐项验证 worker、Zstd、所需 SQLite API 和模块加载，不能按旧版本依赖表直接裁剪。只组装所需的 Cordis/Host 服务，不直接使用包含桌面进程依赖的默认 profile。

### iOS

- NodeMobile 使用 jitless 构建，framework 和 addon 随 App 签名交付；运行时归档只包含已审核的 JS 与数据，不向可写目录释放宿主原生库。
- 会话和工作区放 Application Support，临时内容放 Caches/tmp，密钥由 Keychain 管理。可信 Node 代码仍有宿主容器访问能力，不能把 JS 工具白名单宣称为不可信插件隔离。
- 优先验证 Node 单实例、停止、销毁环境和重新运行。DSH Go 的 restart 实现属于额外宿主适配，NodeMobile 文档的单实例约束仍需遵守；失败时显示“需重启应用”，不能创建第二个 runtime 绕过失败。
- 默认按前台任务交付。iOS 16.1 至 25 使用有限后台时间收尾；iOS 26 以后再接 `BGContinuedProcessingTask`。收回执行时间后保存已知状态，不能承诺永久后台运行。[NodeMobile FAQ](https://github.com/fogtape/nodejs-mobile/blob/recipe/docs/FAQ.md)、[Apple 后台任务](https://developer.apple.com/documentation/backgroundtasks/bgcontinuedprocessingtask)

### Android

- 候选采用独立 `:localRuntime` 进程承载 libnode，主进程接收就绪端点和状态。这样可降低原生崩溃影响 UI 的概率，但同 UID 进程不构成插件权限隔离。
- `libnode.so`、JNI 和 addon 随 APK 打包，所有新增原生库验证 API 24 兼容和 16 KB 页面要求。前台服务按真实任务用途配置，首版不以服务常驻保证任务永不终止。
- 先与 iOS 使用相同的文件工具集合。PRoot/原生 Shell 作为后续平台能力，不让 Android 更丰富的工具反过来扩大 iOS 首版范围。

## Shell 应怎样加入

建议采用“宿主 Node 运行 DSH，命令后端按能力选择”。不把整个 Node 与 DSH 放进 iSH 作为默认路线，以免 Agent、网络解析和 JS 执行都承担 guest 指令解释开销。其实际性能差异仍须测量。

1. **文件工具完成后评估 just-bash。** 只有验证其命令语义、路径、退出码、输出限额和取消行为后，才注册虚拟 Shell。向模型声明命令集合，禁止把它描述为完整 Linux。
2. **需要真实 CLI 时添加 guest。** iOS 使用固定版 ARM64 iSH 与 Alpine；Android 评估 PRoot。通过统一的执行请求传递 argv、cwd、环境、stdin、权限和取消，返回有序输出与退出码。
3. **执行前确定路由。** 不支持的脚本明确失败；命令执行后不能因为出错而自动换后端重试，避免重复写文件或网络操作。

iSH 的 guest fork/exec 是模拟 Linux 内部行为，不是 iOS 宿主子进程。只映射本次授权的 workspace 和 tmp，不暴露模型凭据、会话数据库和 runtime 安装目录。guest 自身的网络访问也应纳入能力声明，文件映射限制不能替代网络策略。[DSH Go guest 执行入口](https://github.com/ccch1mneyyy/dsh-Go/blob/745cc73171287e606339969173b3dcd625847962/runtime/ios/guest/provider.mjs)

首版不加入任意 npm/apt 安装、任意 stdio MCP、完整交互终端、跨 App 自动化和子 Agent 并发。后续按目标任务逐项开放，不承诺“安装 Linux 后桌面工具全部可用”。

## 持久化与中断恢复

本地 DSH 的会话文件是权威数据，现有 Room/Swift 缓存只是读取投影。优先保留官方 Session V4 和持久化模块，并验证独占文件锁、完整记录提交和损坏尾部处理。

工具恢复区分以下状态：

| 状态 | 恢复动作 |
| --- | --- |
| 审批中，尚未执行 | 依据运行代次核对审批；内容和权限变化后重新批准 |
| 已批准，尚未开始 | 按可证明的持久状态重新准入，不沿用失效票据 |
| 已开始，结果尚未持久化 | 标记结果未知，让用户检查实际影响，不自动重放 |
| 结果已持久化 | 重放事件恢复 UI，不重复执行工具 |

工具执行前记录意图，结束后提交结果。若官方持久化不能提供所需状态，增加与 toolCallId 关联的最小执行日志，并与会话事件对账。执行日志无法让外部服务与本地磁盘形成原子事务，不能宣称所有工具 exactly-once；对于没有幂等键的外部副作用，保留“结果未知”。

断网、前后台切换、引擎崩溃都不能自动转成远程执行。未来如做主机迁移，需要用户选择目标，并显式传输会话和工作区。

## 分阶段交付与退出条件

以下是单名熟悉现有项目、Node 和原生桥接的工程师的规划估算，按人日计，不是已验证工期。受限首版不含 Shell 和 Linux；每阶段通过后再投入下一阶段。

| 阶段 | 实施内容 | 验收条件 | 估算 |
| --- | --- | --- | --- |
| P0 固定依赖并打通原生宿主 | NodeMobile full、DSH 依赖闭包、签名 addon、Release 宿主 | 无调试器 iPhone 与 Android arm64 真机完成启动、HTTPS 流式调用、取消和重启 | 4–7 人日 |
| P1 运行最小 DSH Host | mobile profile、一个 Provider、文件工具与本地 Gateway | 通过现有客户端完成“读文件、审批修改、写回、查看历史”；协议 fixture 通过 | 6–10 人日 |
| P2 接入产品 | 本地主机、密钥、导入导出、能力显隐与中断处理 | 本地/远程数据隔离，飞行模式读历史，强退后无重复工具执行 | 7–12 人日 |
| P3 真机稳定化 | 内存、耗电、后台、升级和安装恢复 | 两端 Release 压测和发布产物检查通过，远程模式无回归 | 8–16 人日 |

受限首版合计约 **25–45 人日**。just-bash 另估 4–8 人日；iSH 与 Android 执行器另估 12–25 人日，需在 P0/P1 和执行器 PoC 后重估。商店审核等待、完整插件生态和多模型兼容不计入以上范围。

P0 的最低实验集合：

- 真机 Release 下冷启动，模型 HTTPS/SSE、取消、原生文件锁和 Session 持久化实际调用通过。
- 同一 App 进程停止并重新运行至少 20 次；记录存活线程、打开 fd 和物理内存变化，排查持续增长。DSH Go 曾针对 Node fd 跟踪增加兼容处理，不能只验证启动一次。
- 长历史、图片解码和大工具输出组合下测量 App 总物理内存，V8 堆上限不代表进程内存上限。
- 工具写入前、写入后但结果提交前、结果提交后三个时点强退；检查文件、审批、历史与未知结果提示。

建议先设以下产品预算：已准备运行时的启动至 Host ready P95 不超过 5 秒；同任务相对远程模式的 App 增量物理内存峰值不超过 180 MiB。它们是待确认目标，不是 DSH Go 或本项目的实测结果。每个固定真机进行至少 30 次独立启动，以及固定 100 轮回合 fixture，使用相同图片和工作区测量；Android 统计 UI 与 runtime 进程的合计内存，另记录各进程数据。模型延迟单独记录，不能混入引擎启动耗时。

若 P0 连续两轮适配后仍无法满足签名、必需模块和资源条件，暂停扩大 Node 接入范围。先区分可替换的宿主问题与引擎固有成本，再决定裁剪 profile 或转向轻量 Kotlin Agent，不能因某个 addon 失败就直接推导“必须重写 DSH”。

## 如果选择 Kotlin 原生 Agent

可行的限定目标是：一个 Provider、顺序工具调用、文件读写搜索、审批、取消、独立事件日志和最小上下文压缩。KMP 负责调度与存储，平台层负责网络、文件权限和系统工具；通过明确的适配层向现有界面投影。其原生会话格式应有自己的版本，未经兼容测试不能写成官方 DSH Session V4。

按这个范围，初步估算 **30–60 人日**，与 Node 首版相比未必更快，但后续可以完全按移动产品需求演进。若要求接近当前 DSH 的 Provider、上下文管理、队列插话、子 Agent、MCP、插件和会话兼容，规划量级应提升到 **80–160 人日以上**，并持续承担上游兼容投入；范围尚未逐项拆分，不能作为固定交付承诺。

重写立项前先交付兼容清单，并使用确定性的假模型响应作行为对照：工具参数增量、多个 tool call、取消竞争、审批跨重启、压缩后继续对话、工具输出截断、图片和历史重放。比较允许的事件偏序、状态与最终副作用，不比较真实模型输出文字是否完全一致。

选择 Kotlin 的触发条件应是：产品明确不追随官方 DSH 生态，且轻量执行内核带来的资源和开发收益已通过同功能原型验证。即使选择 Kotlin，iSH/PRoot、系统权限和后台中断仍要单独实施。

## 发布与依赖管理

DSH 原生代码、JS profile 和可执行插件先随应用发布，禁止运行时自动升级引擎。每个版本记录 DSH、Node、addon、协议、持久化格式及补丁摘要；安装失败回退到可识别状态，未知新格式不能交给旧引擎继续写入。

iOS 发布配置从构建时移除未纳入发布范围的 guest 安装和任意代码执行功能。下载 Linux 可执行环境、插件市场等能力需要结合实际产品和 Apple 2.5.2 单独确定分发方案；Node 或 Kotlin 都不自动获得审核许可。[Apple 软件要求](https://developer.apple.com/app-store/review/guidelines/#software-requirements)

DSH Go 的代码采用 AGPL-3.0。建议先独立实现宿主与适配边界；若直接采用其代码或 iSH 补丁，记录具体来源和许可证，并在纳入发布前完成分发义务核对。NodeMobile、DSH、iSH 及 addon 分别生成第三方清单，不能以一个仓库的许可证覆盖全部依赖。[DSH Go LICENSE](https://github.com/ccch1mneyyy/dsh-Go/blob/745cc73171287e606339969173b3dcd625847962/LICENSE)

## 首个可执行任务

先做 P0 的 iPhone Release 验证：固定 NodeMobile 与 DSH 版本，创建最小 Objective-C++ 宿主，跑通一轮真实模型调用、文件工具和停止重启，输出启动时间、App 物理内存、线程与 fd 变化。同步建立 Android 宿主作对照。通过后才接产品 UI 和可选 Linux。

这一步直接验证本项目最大的未知项，也能为后续 Kotlin 原型提供同等功能的对照基线。

相关背景：[既有本地运行时调研](local-runtime-research.md)、[OpenMinis Shell 分析](openminis-local-shell-research.md)。这两份保留其历史版本背景，本方案作为 2026 年 11 月的新实施基线。
