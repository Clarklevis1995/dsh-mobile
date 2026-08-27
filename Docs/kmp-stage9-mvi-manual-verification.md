# 阶段 9.7～9.9 推送式 MVI 人工验收

> 目标：确认 iOS 产品路径已经形成 `Swift UI Intent → KMP Store → KMP Event → Swift UI 投影/平台 Effect` 闭环。自动化通过后执行本清单；全部通过后再勾选计划 9.9。

## 环境

- iPhone Simulator 或真机：____________
- iOS 版本：____________
- Mobile Gateway 版本/来源：____________
- 验收日期：____________

## SessionList

- [ ] 冷启动后旧会话、归档状态和当前选择正确恢复，没有会话被清空或回到 1970 年。
- [ ] 新建/发送消息后会话只出现一次，排序和运行状态正确。
- [ ] 切换会话、标记已读、归档会话后 UI 立即更新；重启 App 后结果仍一致。
- [ ] 快速重复点击同一会话不会产生闪烁、重复持久化或重复会话。

## Human Question

- [ ] Agent 发出问题时只出现一张卡片；重连 replay 不重复创建卡片。
- [ ] 提交答案只发送一次，按钮进入提交状态；服务端接受后问题正常消失/继续执行。
- [ ] 跳过问题只发送一次，结果正确。
- [ ] 断线时提交会显示可理解的失败状态；重连后可以重试。

## SessionControl

- [ ] 打开会话后模型、权限、Context Usage、Session Stats 正常加载。
- [ ] 切换模型和权限成功，UI 与服务端状态一致；重复刷新不会重复执行请求。
- [ ] 快速 A→B 切换会话，A 的迟到响应不会覆盖 B。
- [ ] 归档/删除有控制状态的会话后，旧响应不会把已删除数据重新写回。
- [ ] 断线重连后模型与权限可以重新加载，没有持续 quarantine 或假成功提示。

## 推送链路与稳定性

- [ ] 连续进行“发送消息 → Agent 回复 → Human Question → 回答 → 切模型/权限”完整流程，App 无崩溃、无重复网络动作。
- [ ] Xcode/Android Studio Console 未出现 `event sequence 不连续`、`event 信封无效`、`订阅基线与初始化快照不一致`。
- [ ] Console 未出现 `mutation 未发布对应的 MVI event`、`invalidPatch`、`invalidEffect` 或 KMP fail-closed 提示。
- [ ] App 退到后台再回前台后状态仍正确，后续 Intent/Event 链路可继续工作。

## 通过记录

- [ ] 以上项目全部通过。
- 备注：
