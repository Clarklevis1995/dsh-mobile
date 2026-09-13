# DSH 0.1.5-rc.2 移动端适配记录

## 对照基线

- 网关接入说明：`dsh-plugin-mobile-gateway/docs/dsh-rc2-mobile-integration.md`。
- 移动端起点：`82cd43a`，已合入 PR #19 的 `4774ec1`。
- PR #19 解决的是旧协议同 seq chunk 的 iOS 行内折叠；rc.2 独立流不能再走该历史 reducer。

## 本次补充

1. Android 和 iOS 的订阅发送 `assistantStream: true`。支持该能力的连接以 `session-snapshot` 恢复历史窗口和活动生成前缀，不再同时请求 latest history。
2. 共享 `AssistantStreamState` 分别管理 subscriptionId、streamId、attemptId、revision、index 和持久 cursor。临时 chunk 从未写入 `SessionEvent` 历史，文本、思考、工具参数、usage、finish 原始内容保留至 attempt 结算。
3. snapshot 替换当前历史窗口，分页位置来自 `nextBeforeSeq`，持久水位来自 `cursor`。即使精简窗口没有任何可见事件，也能继续向前分页。
4. 重复增量、重复持久事件及旧订阅/旧 stream 的迟到帧被丢弃。committed end 必须关联已经收到的持久事件；abandoned、reset、断连和切换清除临时输出。连续性异常重新订阅，永久 reset 显示错误。
5. 历史缓存跟随每个 gateway 独立的状态容器，按 session 记录格式版本。未标注版本或版本变化的历史及分页状态清除后重新安装基线；分页在发起时捕获游标所属版本，不给旧坐标补写新版本。
6. 晚到的 latest history 不覆盖活动快照；更早历史与持久事件合并后恢复临时前缀。iOS 的异步历史处理由原有处理 token 在新快照时失效。
7. Android 的 hello、订阅确认、snapshot、独立增量、持久事件及 reset 共用有序的 conversation 队列，控制投影和文件响应继续走控制队列。
8. todo/goal 的控制基线按整体替换处理，缺失 session/键和空对象会清除旧值。会话快照也安装对应投影。
9. 保留 interrupted、attempt stream、usage，以及持久帧根节点的 surfaceOp/sourceEventSeqs；历史工具结果识别内层 `tool-result.isError`。中断消息和失败 attempt 显示中断状态。
10. 对话采用独立增量 patch；轨迹页组合持久节点和临时节点，临时节点只引用真实生成起点，事件记录为空，不伪造 seq。
11. 不产生对话行的持久事件（如 `turn/start`、`step/start`、`request/header`）仍发送水位 patch，同步平台侧序号，避免下一条临时增量误报“临时流改变了持久流水位”。纯水位更新不触发 iOS 对话重绘。回归测试在修复前精确复现了该弹窗，修复后通过。

现有客户端没有按 `atSeq` 分叉的 UI 或缓存锚点，本次未新增此功能。将来增加时必须与分页一样携带读取坐标时的 `historyFormatVersion`，格式失效后重新选取锚点。

## 自动验证

- 共享层测试：165 项通过，包括前缀恢复、去重、reset/重订阅、旧订阅隔离、版本失效、混合 chunk 保留、最终事件关联、空 attempt、控制基线替换、工具失败、双连接投递顺序和不可见持久事件的水位同步。
- Android 单测：85 项通过，包括真实共享 Store 的 snapshot → chunk → final → end、补页保留前缀、reset/切换清理、不可见持久事件后的临时增量、基线替换及既有用例。
- iOS 模拟器全量测试：154 项通过，包括 AppStore 的 rc.2 前缀恢复、重复投递、最终消息唯一性、不可见持久事件后的临时增量水位校验，以及原有 PR #19 回归用例。
- Android Lint、Debug APK 构建；新状态机的 ktlint、detekt；`git diff --check`。

以上测试均为 0 失败、0 跳过。新版 rc.2 Host 与 Android/iOS 真机联调仍未进行；本次未发布网关或移动端。
