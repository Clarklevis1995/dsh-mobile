# 阶段 10 iOS 人工验收清单

> 目标：验证 Conversation、Trajectory、Token Usage 与 History 切换到 KMP 增量 MVI 后，长会话实时体验、滚动位置和重连行为没有回退。
>
> 建议环境：iPhone 17 Simulator、iOS 26.5、真实 Mobile Gateway。验收通过前不勾选迁移计划 10.6。

## 1. Conversation 流式与最终消息

- [x] 打开一个已有较长历史的会话，首屏内容、图片和工具卡片完整，没有重复消息。
- [x] 发送一条会产生较长回答的请求；流式文字连续增长，不回退、不重复、不闪烁。
- [x] 回答完成后，流式临时消息被最终消息原位替换，没有同时保留两份。
- [x] 回答期间切到会话列表再返回，内容仍连续，最终消息正确。
- [x] 回答期间主动向上浏览历史，页面不会被强制抢回底部；回到底部后继续跟随新内容。

## 2. Trajectory 与 Token Usage

- [x] 在回答流式过程中切到“轨迹”，request、assistant、tool/subtool 节点按顺序出现。
- [x] 同一个流式节点持续更新，不产生大量重复节点。
- [x] 工具调用与工具结果能够配对；失败工具显示失败状态。
- [x] request 节点的 input/cache/output/reasoning/content Token Usage 与事件数据一致。
- [x] 在“对话/轨迹”之间快速切换 5 次，节点不丢失、不重复，界面无明显卡顿。

## 3. History 分页与实时尾部

- [x] 打开一个有多页历史的会话，最新一批历史加载完成，顶部显示可继续加载提示。
- [x] 向上滑动触发更早历史；旧内容插入顶部后当前阅读位置不明显跳动。
- [x] 连续加载至少两批历史，没有重复消息、倒序或 cursor 循环错误。
- [x] 历史加载期间让同一会话继续产生实时回复；加载完成后实时尾部仍在，且不会被旧 history 覆盖。
- [x] 退出再进入已同步会话时，如果远端没有新活动，不应反复下载同一批历史。

## 4. 超时、重连与跨会话

- [x] 加载历史时断开 Gateway；加载状态能结束，本地已有内容保留，不出现永久转圈。
- [x] 恢复连接并重新进入会话，历史与实时订阅可以继续工作。
- [x] 在 A 会话加载历史时切到 B 会话并发送消息；A/B 数据互不串线。
- [x] 归档或删除会话后再收到晚到帧，不应重新出现已删除会话的 Conversation/Trajectory/History 内容。
- [x] 前后台切换一次，正在运行的 Agent、实时回复和后台执行状态保持正常。

## 5. 性能观察

- [x] 在包含大量消息和工具记录的会话中连续滚动 30 秒，无明显持续掉帧或卡死。
- [x] 长回答流式期间，输入框、页签切换和返回手势仍可响应。
- [x] Xcode Memory Graph/Debug Navigator 未观察到 Conversation/Trajectory timeline 持续无界增长或重复对象峰值。
- [x] Console 中没有 `KMP Conversation/Trajectory/History ... event sequence 不连续`、`增量事件无效` 或 `事件流已停止`。

## 通过标准

- 上述项目全部通过，且没有可复现的数据重复、历史覆盖实时尾部、滚动跳变或明显性能回退。
- 若失败，请记录：会话 ID、操作步骤、发生时间、页面（Conversation/Trajectory）、可见提示及 Console 中第一条 KMP 错误。

## Console 修复复验（10.6 阻断项）

- [x] 长回答、轨迹详情切页、sheet 高度变化及键盘开合期间，不出现 `Publishing changes from within view updates is not allowed`。
- [x] 轨迹详情页不出现 `UICollectionViewFlowLayoutBreakForInvalidSizes` 或 `item height must be less than`。
- [x] 前台长回答不创建或长期持有 `Complete DSH Agent Turn` 后台任务；进入后台后能够保活，turn 结束、回到前台或系统过期时及时释放，Console 不出现超过 30 秒风险警告。
- [x] 启动时不出现 `CFBundleDevelopmentRegion is not a string value`；动画背景/会话界面不出现 `UIColor ... outside the expected range`。
- [x] 允许忽略 UIKit/模拟器系统噪声：`CandidateGeneration`、`containerToPush is nil`、无可见 context menu；不得将 KMP/Gateway/业务超时归为系统噪声。

> 2026-08-27 首轮人工观察：业务表面正常，Console 未发现 KMP schema/sequence、Gateway/WebSocket 或业务请求错误；发现 SwiftUI 发布重入 15 次、PagingLayout 非法尺寸 14 次、后台任务超过 30 秒 2 次、Info.plist 类型与颜色分量警告各 1 次。因此 10.6 暂不通过，进入修复。

## 验收记录

- 日期：2026-08-27
- 设备 / 系统：iPhone 17 Simulator / iOS 26.5
- Gateway 版本 / 来源：真实 Mobile Gateway
- 结果：通过
- 备注：用户确认业务操作与 Console 复验均未发现其他问题；`CandidateGeneration` 属模拟器输入法系统噪声。KMP 71、Android 2、iOS XCTest 125 项及 iPhoneOS Release 构建均通过。
