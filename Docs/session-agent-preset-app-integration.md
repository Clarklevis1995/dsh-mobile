# 会话模式选择接入

Android Compose 与 iOS SwiftUI 共用 `SharedSessionAgentPresetStore`，每个 Gateway 实例独立持有状态。

## 界面与行为

- 输入框上方左侧显示模式胶囊，与右侧轮次统计共用一行。
- 当前模式来自 `session-agent-preset` 查询，不用全局默认值覆盖已有会话。
- 首次查询完成后可以反复选择；使用 `select-agent-preset` 修改原 Session，全程不重建会话。
- 菜单显示名称、单行短介绍和选中标记。自定义模式保留服务端名称与介绍，过长时省略。
- 损坏模式禁用，兼容 `broken: true` 以及包含原因的字符串。
- 保存期间禁止再次切换和发送。普通消息或斜杠命令提交时暂时隐藏胶囊；收到 `sent` 或锁定通知后永久隐藏。
- 命令执行结束、发送失败或超时后查询服务端。只有明确返回未锁定才能恢复选择；普通设置命令不会被当成永久锁定依据。
- 停止、对话完成和重连不会解除已经确认的锁定。
- 没有 `session-agent-preset` capability，或 Host 返回 `modeSelectionEnabled: false`，则隐藏入口。

## 状态一致性

- 查询和选择都携带唯一 `requestId`。只接收当前页面的最新响应。
- 锁定为单向事实，迟到的 `locked: false` 不会覆盖已经收到的锁定通知。
- 控制通知不受会话订阅过滤。其他 Session 的通知不会修改当前页面。
- 接受 WebUI 等其他客户端发出的模式变化与锁定通知；通知没有携带的字段保持原值。
- 每次进入会话或重连重新查询。历史投影可以在查询完成前提供名称，不能据此判断是否允许编辑，也不能覆盖已经确认的当前模式。
- 模式变化后丢弃旧命令菜单并重新请求 `commands`；Gateway 的该响应已包含 commands 和 skills 分组。
- 保留 Host 的具体错误码及信息。模式不存在/损坏保留原值并刷新列表；未知状态保持禁用；Session 不存在则刷新列表并退出会话页。
- 两端标题栏同步当前会话模式；全局默认模式设置独立保留。

## 验证

运行共享层 `iosSimulatorArm64Test`、Android 单元测试、Android 模拟器模式菜单与统计菜单交互测试、Android lint，以及 iOS Gateway 协议与输入组件回归测试。

2026-09-16 验证通过：共享层 202 项、Android 单元测试 97 项、Android 模拟器交互 2 项、iOS 回归 140 项；Android lint 通过。最终共享框架更新后，再次完成 Android 构建、共享层与 Android 单元测试，以及 iOS 构建和 2 项新增模式协议测试。

重点覆盖反复切换且 Session ID 不变、保存期间禁止发送、首条消息锁定、迟到响应、跨 Session/Gateway 隔离、停止与重连、消息/命令失败后重查、损坏模式、Host 隐藏设置、命令与技能缓存失效。

Android 菜单组件截图：[查看截图](test-evidence/2026-09-16/android-session-agent-preset-menu.png)。截图使用测试模式目录，不连接真实 DSH。

仓库没有配置可运行的 ktlint/detekt 任务，本次执行现有 Android lint，未额外引入工具依赖。

尚未使用新版真实 DSH/Gateway 做双端端到端联调；需要服务端实际启用 `session-agent-preset` capability。未修改发布版本、升级 DSH 或发布 npm。
