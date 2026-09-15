# 移动端 queue / steer 对接

## 交互

Android 与 iOS 输入框上方显示 Host 队列。只有 `placement=queued` 的条目进入列表，`steering` 和 `context` 不会被重复展示为排队消息。多条消息默认折叠，可展开查看，队列高度受限。

队列作为输入框外部的独立横条，左右边缘与输入框对齐，顶部两角与输入框同为 24，底部与输入框顶部衔接；不占用输入框内部区域。出现时从底部展开并淡入，约 0.3 秒完成；iOS 开启“减弱动态效果”时使用淡入。

- 普通发送明确提交 `mode=queue`。生成中输入文字后，主按钮由停止变为发送；没有输入时仍可停止当前生成。
- 铅笔按本次要求回填到主输入框：先向 Host 提交 `queue-update/action=remove`，收到 `accepted=true` 后取回原文字，用户修改并重新发送。已有草稿不会被覆盖，编辑内容追加到其后。请求失败不删除本地队列，不伪造成功。
- 垃圾桶提交 `queue-update/action=remove`。
- 队列向上箭头提交 `queue-update/action=steer`，按原条目 ID 提升，保留 Host 中的原始消息和附件，不另造一条重复消息。Host 决定何时将插话交给 Agent。
- 尚未运行、断线、缺少 `queue-control` 能力或已有队列操作等待确认时，禁用相应操作。
- 与当前官方 Web 的编辑能力一致，带非文本内容的条目可以展示、删除、插话，但不允许文本编辑，以免移除附件后无法完整重发。

本机官方 Web 的铅笔实现为队列内编辑；移动端按用户明确要求改为移出队列后回填主输入框。

## 实现

共享 `SharedQueueStore` 处理队列基线、单会话全量替换、条目去重、操作互斥、回执校验及按会话暂存待回填文字。两端只负责原生 UI 与网络执行。重新连接后以 Host 新基线重建队列，没有添加磁盘历史缓存。

普通消息等待 `sent` 回执再清空输入框；期间用户若修改草稿或切换会话，旧回执不会清除当前的新草稿。失败和超时保留输入内容。发送排队消息不会重新订阅已有实时流或重载已打开的历史。

后台保活同时考虑活动轮次和 Host 待执行队列，避免插话或被删除的排队项被永远计作尚未完成的新轮次。

网关已经提供 `message.mode`、`queue-update`、`session-queues` 和 `session-queue`。最初的队列操作无需更改网关；后续插话回显修复补充了用户消息 ID 转发，见下文。Host 的执行顺序仍由 Host 决定。

## 插话用户气泡补充（2026-09-15）

原实现仅把 `queued` 项显示在队列条，遗漏了尚未进入持久历史的 `steering` 消息。现在 Android 与 iOS 都把控制流中的 `steering` 项交给共享 `SharedConversationStore`，通过增量投影显示为普通用户气泡；`queued` 和 `context` 不在此处显示为用户消息。

- 临时用户行不修改持久事件的序号水位，不触发重载历史。
- 正式 `user/message` 到达时按原消息 ID 更新同一行，保留气泡 ID；重复队列快照不会重复插入已进入历史的消息。
- 共享投影保留文本与图片附件，同文但不同 ID 的消息分别显示。
- 全量队列基线清理已消失会话的临时行；历史重建会重新合并仍处于 steering 的消息。
- 网关 `lib/index.mjs` 在实时用户事件中补充 `raw.id`，取自 Host 原始消息 ID。只有这一个可选标识字段，没有改变 Host 执行逻辑。
- 旧网关不带 ID 时，按新到达事件的正文和附件顺序匹配一条临时行；不会按正文全局去重。跨端同时发送相同正文、或控制流与事件流逆序时无法保证精确关联，应配合更新网关使用 ID 匹配。

本轮验证：共享 Kotlin 185 项、Android 单元测试 97 项、网关分发 125 项通过。已新增 iOS KMP 桥接回归用例；iOS 测试启动因自动审批服务容量不足被拦截，尚未取得本轮 XCTest 与模拟器联调结果。此前队列动画验证不能代替本轮插话气泡验证。修改尚未提交、发布，也未重启正在运行的网关。

## 自动验证

- 共享 Kotlin 测试：178 项通过。
- Android 单元测试：95 项通过。
- iOS XCTest：169 项通过，包含 Swift 队列字段完整桥接和后台计数回归。
- Android 队列专项设备测试：5 项通过。4 项验证按钮、折叠、断线和忙碌状态，1 项通过产品 Runtime/StateHolder 验证发送确认、编辑回填保留旧草稿、修改后重排、提升为 steer 的完整链路。
- Android Debug 构建与 Lint 通过；iOS 模拟器签名构建通过。

完整 Android 设备测试另有失败，不能表述为全量设备测试通过。已在未包含此次改动的主干 `62cf92b` 隔离副本复现两项失败：

1. `AndroidAppGraphFakeIntegrationDeviceTest.injectedProductGraphRunsRuntimeHolderProjectionHistoryAndVisibleAttachment`：旧分页用例未取得预期 `beforeSeq`。
2. `AndroidUiParityDeviceTest.offlineNewSessionOpensComposerWithoutShowingAnInternalSubscribeError`：离线新会话界面断言失败。

键盘设备用例 `inputFocusMovesLatestMessageAboveImeAndTimelineTapHidesIme` 在当前分支单独复查通过，完整设备套件中的失败未在单独运行时重现。

本次修改尚未提交或发布。iOS Computer Use 操作被 Mac 锁屏阻断；自动 XCTest 通过不能替代真实 Host 的手动联调。

## Android 实际 Host 联调

使用 Android 模拟器连接本机正在运行的 Host，在专用测试会话 `session-1dd29b95-12f2-4b64-b66c-817b8631d96e` 中完成以下操作：

1. Agent 执行等待命令期间发送 `Reply ORIGINAL.`，消息出现在输入框上方的队列条中。
2. 点击铅笔，Host 确认移出后，原文字回填并聚焦输入框。
3. 修改为 `Reply EDITED.` 并重新发送，Host 日志确认 `mode=queue`，队列显示修改后的文字。
4. 点击向上箭头，Host 接受 `queue-update`，该条目从排队 UI 移除；执行仍交由 Host，App 没有取消或重启 Agent。
5. 再发送 `Reply REMOVE.`，点击垃圾桶，Host 确认后条目移除。

另一次实际请求已观察到排队消息被 Host 消费后进入正式对话正文。上述测试结束后通过 App 停止了测试会话。初次通知权限弹窗及脚本误选正文的焦点问题已在联调过程中排除，不计为产品测试通过项。

后续横条宽度、圆角及弹出动效调整已通过 Android 构建与 Lint、iOS 模拟器构建。此轮未重新进行设备动画录屏验证。
