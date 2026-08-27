# KMP 阶段 11 人工验收清单

> 目标：验证删除 Swift 重复 Reducer/Projection、Shadow 和过渡映射后，iOS 产品行为与阶段 10 一致。

## 测试环境

- [x] 记录设备/模拟器型号和 iOS 版本。
- [x] 使用真实 Mobile Gateway，确认 App 连接成功。
- [x] 冷启动 App，确认旧 Session 缓存可恢复，无闪退或数据清空。

## Session 与持久化

- [x] 进入已有 Session，返回列表后选中状态、未读和运行状态正常。
- [x] 新建 Session 并发送消息，列表及时出现新会话，标题/活动时间正常。
- [x] 归档 Session，确认对应 Conversation/Trajectory/History/Control 数据不再出现。
- [x] 强制结束并重启 App，会话列表与选中结果可恢复。

## Conversation 与 History

- [x] 发送一条普通文本，检查用户消息、reasoning chunk、assistant chunk 与最终消息顺序正确。
- [x] 确认最终 assistant message 替换流式临时消息，不重复、不跳字、不丢字。
- [x] 打开较长会话，触发历史分页，旧消息无重复/乱序，实时尾部不丢失。
- [x] 在历史加载中切换 Session 再返回，不会用旧请求覆盖新 Session。
- [x] 验证 Markdown、图片附件、tool call/result 和 `run_code` JSON 显示。

## Trajectory

- [x] 在 Agent 运行中切到 Trajectory，request/assistant/tool/subtool 节点持续增量更新。
- [x] Token Usage、模型/推理强度、工具结果和子工具层级显示正确。
- [x] Conversation/Trajectory 反复切换，再次进入 Trajectory 时 baseline 完整，无空白或重复节点。

## Human Question 与 Session Control

- [x] Human Question 单选、多选、自定义答案、Submit 和 Skip 各完成一次。
- [x] 重复点击 Submit/Skip 不会发送两次，断线后重连问题不丢失。
- [x] 切换模型、reasoning effort 和权限，响应正常且不弹超时/quarantine 错误。
- [x] 查看 Context Usage、Stats、Agent Presets 和默认配置，快速 A↔B Session 切换不串数据。

## 生命周期与 Console

- [x] Agent 运行时将 App 切到后台再返回，回复继续，连接与状态不丢失。
- [x] 手动断网/恢复网络，App 能重连并重建 KMP/UI 镜像。
- [x] Console 不出现 `KMP ... fail-closed`、`invalid-event`、`sequence 不连续`、`invalidPatch`、`runtimeFailed` 或未预期 Gateway 错误。
- [x] iOS 系统自身的 CandidateGeneration/Keyboard/UIKit 无害日志不单独判为失败，以业务与 KMP 错误关键字为准。

## 验收结果

- [x] 以上项全部通过。
- [x] 记录日期、设备、iOS 版本、Gateway 版本/分支和未阻断 warning。

验收记录：2026-08-27，iPhone 17 / iOS 26.5 Simulator，通过 Android Studio 启动 iOS App，连接当前本地真实 Mobile Gateway。用户确认阶段 11 人工验收通过；Gateway 的精确 commit 未单独记录。仅保留 L10n 两处插值弃用、`traitCollectionDidChange` 弃用和 KMP framework script phase note，不阻断验收。

## 人工回归问题

- [x] 复验历史 Session 冷加载：全屏遮罩只在尚无内容时显示；首批消息出现后立即转为列表内加载提示，不得与消息重叠。
  - 2026-08-27 首轮 viewport 空/非空回调方案人工复验失败；根因不是单纯的遮罩撤下时机，而是 Session 选择 Event 延迟发布后，导航目标首帧仍读取旧 Session。
  - 第二轮改为导航等待 Session 选择 Event 提交；加载层强制不透明，并且只在新 Session diffable snapshot 完成后撤下，人工复验通过。
- [x] 快速连续切换两个已有内容的 Session：新 Session snapshot 出现前可以短暂留白/显示加载态，但绝不得闪现上一 Session 的文本、图片或工具记录。
  - 2026-08-27 首轮在复用 controller 内隐藏旧 snapshot 的方案人工复验失败。
  - 第二轮以 Session ID 作为 `UIViewControllerRepresentable` 硬身份，导航前先完成 KMP Event→Swift UI 镜像提交，人工复验通过。
