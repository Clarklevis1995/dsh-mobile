# 移动端 Team 模式开关

对话右上角「更多」菜单在「工作区文件」「会话状态」下方新增「启用智能体团队协作」。模型菜单只保留模型与推理等级；按用户要求不提供说明入口或说明弹窗。iOS 使用原生 Menu + Button，复用团队入口的同一多智能体图标：开启为蓝色，关闭为灰色，不显示对勾。三行共用相同图标列和文字左边距；Android 同步这一布局和颜色。开关作用于当前根会话，子代理不显示。新对话先创建真实根会话，发送首条消息之前即可配置；不是全局默认选项。

## 协议与状态

- 网关额外声明 `agent-team-mode-v1`，旧网关仍可浏览团队，但不显示可写开关。
- 控制请求为 `agent-team-set-enabled`，仅包含 type/sessionId/requestId/enabled；官方 Host 调用为 `agentTeams.setEnabled({agentId,enabled})`。根会话校验、权限与持久化由宿主负责。
- 持久化完成并取得精确投影后，返回 `agent-team-mode-set`，包含 agentTeam、asOfSeq、projectionState。共享层按序号合并，旧回执不能覆盖较新的宿主投影。
- 保存期间禁用重复操作与发消息，以真实投影显示状态。错误、超时或断线均提示结果待确认并刷新；不自动重放写请求，不凭失败回滚已经收到的宿主状态。
- 关闭保留成员、任务、历史与子代理运行状态。已有 modelSelection.lastUsed 的会话停用前显示确认，确认目标固定为打开弹层时的根会话。

## 2026-11-14 验证

使用用户已升级的 DSH 0.2.2-rc.3、现有配对和认证配置。网关本地源码及 DSH Web 的 file 依赖安装副本已同步新增接口；重载前确认目录内无运行会话。安装副本原实现备份在 `/tmp/dsh-team-mode-installed-backup`。

| 验证 | 结果 |
|---|---|
| 共享层全量回归 | 657 项通过，含新增状态机及 Runtime 回执关联测试 |
| 网关完整 npm test | 通过，WebRTC 有一项环境条件跳过 |
| 官方生成的严格描述符 | RC.3 真实 Gateway/Connection + 业务 fixture 通过 |
| WebSocket 分发及回执 | 11 项团队/开关测试通过 |
| iOS AppStore 集成 | 15 项通过，含保存前不乐观切换、重复点击、关闭确认、错误刷新、旧能力隐藏 |
| iOS 真实生产客户端 | 开启→确认关闭→重连读取一致；原 4 项任务、5 个成员保留 |
| 新空根会话 | 未发消息即可开启，空团队入口可见；关闭后入口隐藏，不需要模型使用确认 |
| Android Pixel Tablet 真实 UI | 更多菜单开启、取消停用、确认停用通过；说明入口不存在，原任务列表保留；测试结束恢复该轮测试前的开关状态 |
| Android 兼容 UI | 旧网关隐藏、等待保存禁用通过；新的 250dp 更多菜单检查手机/平板横竖屏，并以真实 App 字号设置复核放大字号 |

首次协议验收后，真实会话 `session-b2ccd705-b23a-423f-95af-e29b89360f63` 恢复关闭；后续入口迁移验收复用同一会话，并恢复该轮测试前的开关状态。新增空会话 `session-a4098b00-0c6a-4bb2-87df-70435ebb5875` 位于隔离验收工作区，最终关闭，未发送模型消息。临时 iOS 真实交互驱动已从测试源码移除，生产凭据未导出。

此次客户端为模拟器，尚未完成物理手机验收。Mac 解锁后已在 iPhone 17 / iOS 27.2 实际操作并核对：更多菜单含文件、状态、协作开关三项，模型菜单仅含模型与推理等级；从新入口点击开关正常出现原生停用确认，取消后状态不变，说明入口已移除。Android 更多菜单按此原生菜单校准宽度、行间距、图标列和锚点。尚未完成 iPad 的完整像素与手势对照，不将自动化通过等同于所有尺寸均完全一致。全量本地化检查仍报现有文件改动页面的缺项，本次 Team 新增文案中英文无缺项。

证据：`/tmp/dsh-team-mode-*.log`、`/tmp/dsh-team-mode-live.xcresult`、`/tmp/dsh-team-mode-ios-final.xcresult`、`/tmp/dsh-team-mode-ios-complete.xcresult`、`/tmp/dsh-team-mode-*-command.json` 及 `/tmp/dsh-rc3-live/frames.jsonl`。临时产物不作为应用资源提交。

入口迁移回归：`/tmp/dsh-team-move-ios.xcresult`（15 项通过）、`/tmp/dsh-team-move-android-live2.log`、`/tmp/dsh-team-visual/`（新入口、模型菜单及手机/平板方向截图）。

字号对照发现 Android Popup 独立窗口会覆盖调用处的 App 字号，已在统一菜单容器显式传递 Density。大字号测试改为使用真实 AppearanceSettings，并断言 1.5 倍菜单高度确实增大，避免只设置测试密度却没有实际放大。统一菜单、文件入口及会话状态入口回归共 7 项通过，日志为 `/tmp/dsh-team-move-density-device.log`。横竖屏测试结束后恢复模拟器原始旋转配置。

颜色版另外修正了 iOS 导航壳未订阅 teamMode 的问题。导航栏只订阅这一轻量快照，收到宿主回执后刷新图标颜色，不随普通消息 token 重建。iPhone 模拟器已完成蓝色开启 → 确认停用 → 灰色关闭 → 再次开启的实际操作，且逐次核对真实宿主投影；测试结束恢复原始开启状态。截图在 `/tmp/dsh-team-color-visual/ios-on.png` 和 `ios-off.png`，没有对勾或说明项。

颜色版 iOS 15 项集成回归通过（`/tmp/dsh-team-color-ios.xcresult`）。Android 同一真实隔离会话亦完成开关、取消与确认回归，开启与关闭截图分别为 `/tmp/dsh-team-color-visual/android-on.png` 和 `android-off.png`；手机和平板按横竖屏各检查正常及 1.5 倍字号。普通字号取消 Material 默认额外字距，与原生菜单标题排版保持一致。
