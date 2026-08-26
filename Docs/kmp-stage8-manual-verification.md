# 阶段 8 iOS 人工核验

> 目标：确认 DEBUG 只读影子验证没有改变现有 iOS 产品行为。KMP 只计算 route/effect descriptor，AppStore 仍以 Swift 结果作为唯一写路径。

## 准备

1. 使用 Xcode 打开 `DeepSeekHarnessMobile.xcodeproj`，选择 `DeepSeekHarnessMobile` 和 Debug 配置。
2. 启动 iOS 模拟器或真机，并连接可用的 Mobile Gateway。
3. 在 Xcode Console 搜索 `KMP` 或筛选 category `KMPShadow`。
4. 启动后应看到“`KMP 只读影子验证已启用；影子 effect 不会执行`”。

## 必测流程

- [ ] 完成连接或配对，确认首页、连接状态和已有 Workspace 正常出现。
- [ ] 刷新 Session 列表并切换一个已有会话，标题、运行状态和未读状态正常。
- [ ] 进入有历史记录的会话，确认首屏历史和向上加载更早历史均正常，无重复、跳序或滚动异常。
- [ ] 发送一条消息，观察 reasoning/assistant 流式内容、最终消息替换和工具调用结果均只出现一次。
- [ ] 如 Gateway 能触发 Human Question：完成一次回答或取消，确认请求、响应和 resolved 状态正常。
- [ ] 打开模型、权限、Context Usage 与 Session Stats，确认加载完成且选择操作仍生效。
- [ ] 打开 Workspace/目录选择流程，确认目录读取、创建目录或创建 Workspace 没有新增异常。
- [ ] 断开并重新连接一次，确认订阅、历史尾部和当前会话恢复正常。

## 通过标准

- Console 中没有“`KMP 影子差异`”错误。
- 同一个用户操作没有产生重复的网络请求、重复消息或重复弹窗。
- UI、持久化结果、滚动位置、后台执行与阶段 7 人工测试一致。
- 即使出现影子差异，产品流程仍应继续工作；请保留包含 frame kind 和两侧 fingerprint 的 Console 日志。

## 不需要测试

- Android 阶段 7 Fixture 页面无需重复人工回归；阶段 8 未改变它的产品路径。
- 不需要验证 KMP effect 是否真的执行；本阶段明确禁止执行影子 effect。
- Release 包不会创建 `KMPShadowValidator`，因此不会出现上述影子日志。
