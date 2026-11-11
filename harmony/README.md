# Whaleness 鸿蒙客户端

采用 KuiklyBase 编译共享 Kotlin 逻辑，使用 ArkTS + ArkUI 原生 UI。鸿蒙使用独立 Gradle 构建，不改变 Android/iOS 的插件版本。

## 开发任务与进度

后续统一按 [鸿蒙客户端 UI 复刻与日常可用版任务规划书](../Docs/harmonyos-client-plan.md) 执行。每完成并验收一项，在该文档勾选任务并追加证据；真实网关、夹具、视觉与真机结论分开记录。

## 环境与版本

- 已验证环境：Apple Silicon Mac、DevEco Studio 6.0.1.251、HarmonyOS API 21、Mate 70 Pro ARM64 模拟器。
- KuiklyBase Kotlin：`2.0.21-KBA-014`。
- Gradle：独立 Wrapper `8.10.2`，JDK 17。
- 协程：`2.0.21-coroutines-KBA-001`。
- 序列化：`1.7.1-KBA-003`。
- 不要单独升级为最新 KuiklyBase：本轮验证的 `2.4.21-KBA-001` 要求 API 26，与当前 SDK 不匹配。

## 构建

在仓库根目录执行：

```bash
./scripts/build-harmony.sh test    # 共享业务集成测试 + ArkTS 核心测试
./scripts/build-harmony.sh native  # 构建并复制真实 Kotlin/Native 库
./scripts/build-harmony.sh app     # 构建 ArkUI / C++ 并打包 HAP
./scripts/build-harmony.sh all     # 完整构建
./scripts/build-harmony.sh smoke   # 构建隔离应用并运行模拟器端到端回归
```

默认使用 `/Applications/DevEco-Studio.app/Contents`；其他安装位置设置 `DEVECO_STUDIO_PATH`。可通过 `JAVA_HOME` 指定 JDK 17。首次构建会下载腾讯工具链及 LLVM，体积较大。

用 DevEco Studio 打开 `harmonyApp/`，先执行 Native 构建，再在 IDE 中构建运行。签名信息与设备证书保持在本机，禁止提交。当前本机模拟器接受未签名调试 HAP；真机和分发须在 DevEco 中完成签名。

HAP 输出：`harmonyApp/entry/build/default/outputs/default/entry-default-unsigned.hap`（配置签名后以实际签名产物为准）。

## 分层

- `harmony/shared` 直接编译 `shared/commonMain` 的源码；协议解码、GatewayRequests、会话 Reducer、SharedHistoryStore、SharedConversationStore、AssistantStreamState、SharedQuestionStore 和 SharedApprovalStore 继续复用。
- ArkUI 的稳定列表项使用 `@Observed` / `@ObjectLink` 观察视图模型，持续更新消息正文和提交状态，不重建正在填写的表单。
- `HarmonyEngine` 接收粗粒度 JSON 命令，输出视图补丁和网络 effects；流式正文通过 insert / append-text / replace 等补丁传递。
- Native C API 经薄 C++ N-API 包装为 ArkTS `Engine` 对象，实例销毁时释放 Kotlin 引擎。不依赖 Compose，也不需要 JVM。
- ArkTS 负责系统 WebSocket、Asset 安全存储、代次检查、重连、超时和原生 UI。
- Token 与设备 ID 一起保存到系统 Asset；一次性配对码不落盘、不写日志。公网连接要求 WSS，网关身份由共享校验逻辑验证。

## 首版能力

- 手动地址 + 一次性配对码，或粘贴 Base64URL 完整配对内容。
- 单个主机安全凭据保存、重连、主动断开及移除凭据。
- 控制 / 会话双通道，兼容传统单通道。
- 工作区过滤、会话列表、创建与切换会话。
- 订阅历史快照、历史分页、消息发送确认、流式文本、停止生成。
- 复用独立 assistant-stream-v1 状态机，过滤旧订阅和错误流水位。
- 手机界面与宽屏原生分栏。
- 原生问题表单：单选、多选、自定义答案、多题提交和取消回答。
- 原生工具审批：展示工具、原因和调用参数，明确选择「单次允许」或「拒绝」。
- 跨会话待处理列表；提交中、服务端已接收、拒绝和超时状态。
- 按会话隔离内存草稿，迟到确认不会清除其他会话或新编辑的文本。
- 返回前台重新同步当前会话、探测无响应连接；逐请求超时，断线不自动重发消息或审批。

## 当前范围（2026-10-31）

原生 ArkUI 已接入品牌首页、多主机与工作区、Navigation 页面栈及系统返回、聊天与问题／审批、Markdown／代码／长表格、轨迹、会话配置、Agent 默认、目标／任务、团队、插件和计划任务。消息正文使用惰性列表与后台解析，聊天提供用户气泡、助手正文、原生更多菜单和单一状态发送／语音／停止入口。

鸿蒙继续排除未适配的 Room 源码，使用实际 ArkData 加密数据库承接共享稳定历史窗口、文字草稿、目录与离线索引；已通过隔离包无网冷启、搜索与网络接管 F，真实主机身份重连与文字草稿恢复 G。历史／索引有估算磁盘预算，不等于已完成跨主机总内存预算。

已接入系统文件／图片选择、后台文件副本与分块上传、工作区校验下载、原生 Markdown／图片／PDF 预览、系统保存／打开／分享入口、扫码 SDK、录音接口和本地通知。各项验证范围不同：文件选择／上传与预览有 F、工作区下载有 G；ImageKit 规范化、录音初始化、NotificationKit 点击回传有实际 SDK F。真实相册收发、录音识别、相机扫码、产品通知定位、后台／锁屏与真机 D 仍按规划书待验；不得把入口存在或受控适配器通过当成设备验收。

当前尚未完成全部页面的浅深 V、宽屏／读屏与设备矩阵，不能称为已达日常使用最终验收。以 [任务规划书](../Docs/harmonyos-client-plan.md) 的勾选、证据类型及限定范围为准。

## 本机端到端测试

启动 ARM64 模拟器后执行：

```bash
./scripts/build-harmony.sh smoke
```

测试会复制当前工程到临时目录，改用独立应用 ID `com.clarklevis.whaleness.harmony.smoke`，构建安装后通过 HDC 操作真实 ArkUI。真实应用的 Asset 凭据不受影响。脚本只连接本机 `127.0.0.1:18783` 的测试网关，测试完成后终止网关、删除本次建立的反向转发并卸载测试应用；截图、布局和日志路径打印在终端。

默认 HDC 目标为 `127.0.0.1:5559`，其他模拟器可设置 `HARMONY_TEST_TARGET`。需要保留测试应用时执行 `python3 harmony/testing/smoke_ui.py --keep-app`，先确保已经构建 Native 库。不要在真实应用中填写夹具地址，以免替换单主机凭据。

夹具提供历史分页、两个会话、独立流、停止、问题回答与取消、工具审批、无响应和断线恢复场景。输入 `question`、`approval-allow`、`approval-reject`、`approval-timeout`、`slow` 或 `drop` 可手动触发对应场景。夹具展示的工具命令不会执行。

## 上游资料

- [KuiklyBase 编译器](https://github.com/Tencent-TDS/KuiklyBase-kotlin)
- [KuiklyBase 组件与 KNOI](https://github.com/Tencent-TDS/KuiklyBase-components)

## 首轮基础验证记录（2026-10-30，以下为当时快照）

- 鸿蒙共享层 17 项 JVM 集成测试通过，涵盖身份、双通道、流协议、历史分页、问题、审批、过期响应和会话切换。
- 实际 ArkTS 核心 8 项测试通过，系统 WebSocket / Asset 被隔离替换，验证连接代次、逐请求超时、前台恢复、发送失败、身份失败和草稿。
- KuiklyBase 真实 ARM64 ELF 动态库链接通过，C++ N-API 与 ArkTS 编译通过。
- HAP 构建并安装到本机 Mate 70 Pro 模拟器，应用启动正常。
- 最终隔离包端到端回归通过：配对、双通道、历史分页、草稿隔离、发送确认、同一消息 ID 的连续增量刷新、停止、多题回答、取消、单次允许、拒绝、审批超时、前后台恢复和断线重连。
- 审批超时场景确认提交中按钮禁用、超时可见、不会自动重发，服务端解决后移除卡片。
- 测试夹具正确回复 RFC WebSocket Ping/Pong，避免 SDK 的协议心跳产生虚假断线。
- 原有 Android 和 iOS Simulator ARM64 的共享模块重新编译通过。
- 更新后的正式应用复用原有 Asset 凭据连接真实 Harness，新建专用会话，发送后收到真实助手回复 `PONG`。
- 真实 Harness 发起单选问题，鸿蒙原生表单提交答案后会话继续，收到 `DONE`；测试未读写项目文件。
- 分页、停止、断线和审批等异常场景通过本机夹具验证；尚未完成鸿蒙真机与上架签名验证。

[模拟器聊天验证截图](testing/screenshots/chat.png)（使用本机测试网关数据）。

原生界面截图（均为隔离网关数据）：

- [多题回答](testing/screenshots/question.png)
- [工具审批](testing/screenshots/approval-allow.png)
- [审批超时](testing/screenshots/approval-timeout.png)
- [会话与恢复](testing/screenshots/conversation.png)


## P2 产品页面与当前验收范围

当前入口已拆分为品牌工作区首页、主机管理／认证／编辑、目录与工作区选择、会话侧栏、聊天、待处理交互及基础设置。业务状态按本地主机 ID 隔离，Token／设备身份继续只存 Asset；旧版单主机 Asset 可迁移，普通偏好只存资料与外观设置。

截至 2026-10-31，核心验证为 100 项 Kotlin/JVM 与 47 项 Node 测试；早期报告保留其当时数量。运行 `./scripts/build-harmony.sh test` 后，再执行 Native 和 HAP 构建。多主机与设置可分别复测：

```bash
python3 harmony/testing/product_ui.py --scenario hosts --keep-app
python3 harmony/testing/product_ui.py --scenario settings --keep-app
python3 harmony/testing/product_ui.py --scenario catalog --keep-app
python3 harmony/testing/product_ui.py --scenario navigation --keep-app
python3 harmony/testing/product_ui.py --scenario native-navigation
```

这些场景使用独立 smoke 包，开始时卸载该测试包，不能与其他 HDC UI 测试同时运行；不覆盖正式应用。聊天全场景仍用 `smoke_ui.py`；可用 `--scenario recovery` 单独检查前后台和断线恢复。

真实验收证据包括旧包 Asset 迁移、已有工作区／目录／搜索，以及专属临时 Host 生成中实际重启后的序号完整性和续聊。独立 Host 使用受控本地模型，协议、Agent、会话持久化与网关是真实实现，但不能将受控上游结论写成外部 DeepSeek 模型服务测试。详见 [任务规划书](../Docs/harmonyos-client-plan.md) 和 `testing/reports/`。

`live_*`、`isolated_*` 脚本是本轮本机验收工具，依赖已建立的专属 DSH_HOME、会话白名单、临时工程和控制端；环境不符时应拒绝运行。不要把独立 Host 端口改成用户主服务来运行数据管理或进程重启测试。

当前 Host 只提供归档，没有恢复协议；不得用本地隐藏／显示伪造服务端恢复。页面逐状态视觉对照、P1 完整边界、设备全链路与真机签名仍以规划书未勾选项为准。富文本与实际加密 RDB 已进入产品；`@lidary/markdown` 兼容样例不作为正式渲染器，当前使用原生组件、TaskPool 中复用的纯文本词法解析及共享内容高亮／导出；未使用该依赖的 UI 渲染器。

代码检查已运行 ktlint、Google clang-format 和 detekt；detekt 当前仍报告结构复杂度／长度／数值命名等存量问题，最新 135 项记录见 `testing/reports/2026-10-31-P7-quality/detekt-findings.txt`，不能宣称静态检查全部通过。

首页状态专项：`python3 harmony/testing/home_state_ui.py /absolute/path/to/isolated-smoke-app`，目录必须已构建且包名为 smoke。脚本仅连接本机夹具，开始会重置测试包，不得传正式工程。Gallery 系统字体探针只调用本应用字体 API，不修改设备设置；物理系统字体变更另行验收。

覆盖抽屉打开时才创建交互层，底层页面保留并隐藏其无障碍子树。自动化同名节点选最上层；无副作用导航和同值偏好选择最多重读重试一次。发送、认证、保存、确认不自动重试，日志保留重试情况。


## 鸿蒙原生页面栈

入口使用 `Navigation`、`NavPathStack` 和 `NavDestination`。首页是根节点；聊天、待处理、设置及字号／隐私／关于、主机列表及认证／编辑、工作区及目录是独立目的页，正常打开时入栈，工具栏和系统返回按真实来源逐级出栈。手机保留系统默认转场与左右边缘返回，不添加拦截整页滑动的自定义手势。

键盘与打开的抽屉优先关闭；弹窗由原生控制器处理返回。认证页真正出栈时才取消在途连接，低于阈值的边缘尝试不提前取消。保存主机编辑后返回原列表；选择主机、成功配对或选择工作区时按业务语义回首页。聊天切换复用一个目的页，防止每选一次会话就累积一层。

页面实例由原生栈管理，出栈后可以销毁；阅读偏移、展开状态及当前视图由 `HostRuntime` 按主机／会话保留；文字草稿和稳定历史另由加密 RDB 持久化。页面重入锚点与杀进程恢复分别验收，前者不替代后者。`native-navigation` 专项只在临时 smoke 工程加路由诊断节点，正式源码与 HAP 不包含诊断 UI。系统边缘手势使用 Input Kit 的 `uinput -T -m` 注入；此 Mate 70 Pro 模拟器选取下半屏热区，普通 UITest 应用级 swipe 不能替代系统手势验收。

首轮原生导航 7 项模拟器专项、设置完整复验、当时 20 项 Node 核心测试与 Native／HAP 构建通过，详见 [导航验收记录](testing/reports/2026-10-30-P2-navigation/review.md)。


## P3～P7 当前专项

所有设备 UI 测试串行运行，且会重置独立 smoke 包。先构建 `./scripts/build-harmony.sh all`；Native 默认为 release 优化，可用 `HARMONY_NATIVE_MODE=debug` 显式调试，不混用测量候选。测试工具从生产工程复制并冻结临时副本；性能、方向或协议适配器只注入该副本。

```bash
python3 harmony/testing/product_ui.py --scenario reading --keep-app
python3 harmony/testing/product_ui.py --scenario files --keep-app
python3 harmony/testing/product_ui.py --scenario uploads --keep-app
python3 harmony/testing/product_ui.py --scenario voice --keep-app
python3 harmony/testing/product_ui.py --scenario goals --keep-app
python3 harmony/testing/product_ui.py --scenario cache --keep-app
python3 harmony/testing/product_ui.py --scenario perf --keep-app
python3 harmony/testing/product_ui.py --scenario adaptation --keep-app
```

完整文件选择／上传还需 `HARMONY_UPLOAD_SELECT=1`。语音协议场景使用固定 PCM 适配器，不会采集环境声音。`adaptation` 调用测试窗口的真实方向 API，不修改设备全局设置；不能替代平板／读屏 D。冻结性能门槛见 `testing/baseline/p7-performance-gates.json`，应用绘制回调不等于 GPU 帧时间。真实 G 必须由 `start_isolated_acceptance.py` 建立独立主机与控制端并显式传 `HARMONY_OWNED_REAL_ROOT`，不得使用用户服务端口。

实时消息回归 E31 保留了初版 ArkUI 数据通知混用造成的崩溃证据。当前统一使用批量通知、保存调用方数组快照并分帧定位尾部，真实原生新消息自动可见、三题回答、工作区下载和冷启恢复已通过 G。完整 V 与 D 尚未关闭。
