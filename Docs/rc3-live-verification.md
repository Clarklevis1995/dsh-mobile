# RC.3 真实网关验收记录

验证日期：2026-11-14。此次连接用户升级后的真实 DSH 与网关，使用 iPhone 17（iOS 27.2）模拟器和已有配对的 Android Pixel Tablet 模拟器；没有使用替身宿主替代这些联调结果。

## 环境与隔离范围

- 实际 hello：`dshVersion=0.2.2-rc.3`、`protocol=3`、`historyFormatVersion=4`、`authenticated=true`，声明团队和子代理能力。
- 网关管理状态版本为 `0.12.0`，运行中的工作区源码已包含 RC.3 修订。
- 复用 App 原有配对；没有关闭设备认证、导出 token 或新增设备授权。
- 新建隔离工作区 `/private/tmp/dsh-rc3-live/workspace`，测试会话标题为 `RC3移动端独立验收1114`，会话 ID 为 `session-b2ccd705-b23a-423f-95af-e29b89360f63`。
- 测试仅在这棵会话树中使用子代理、共享任务和最小文本消息；未指令模型读写项目文件或修改其他既有会话。

首次尝试孙代理时，真实宿主返回 `subagent depth 2 exceeds maxDepth 1`。用户将最大递归深度调整到 2 后，两条真实分支创建成功；客户端没有把原先的失败伪装为成功。

## 已通过的真实场景

| 场景 | 实际结果 |
|---|---|
| 启用空 Team | WebUI 原生模型菜单启用 Team 后，移动端收到 enabled=true、members=[]、tasks=[]；共享快照 visible/isTeam 均为 true，原生团队视图呈现空任务板，无 invalid team projection 弹窗 |
| name 与 label 分离 | reviewer / writer 作为成员标题；检查分镜 / 撰写摘要作为描述 |
| 同名孙代理 | reviewer/checker 和 writer/checker 同时存在，ID 各不相同，均保留各自直接父 ID |
| 任务负责人 | 4 项真实任务保留 ownerId 与 ownerName；task-4 的负责人为 reviewer/checker，未误匹配 writer/checker |
| 未分配筛选 | 仅 task-3 无 ownerId/ownerName；Android 实际点击未分配筛选后显示该任务 |
| 负责人跳转与当前成员筛选 | Android 从真实 task-4 卡片点击负责人，生产状态层选中 reviewer/checker；当前成员筛选显示对应任务 |
| 一级与孙代理续聊 | 均取得真实 sent 回执；孙代理历史中真实 assistant 文本为 RC3_GRANDCHILD_ACK |
| 直接父地址 | 孙代理的 sent、history、session-cancelled 回执均携带直接父 reviewer 的 ID |
| 非法父地址拒绝 | 故意把根会话声明为孙代理的直接父级，返回 subagent/unauthorized，未取得 sent 回执 |
| 运行中停止 | sent 后观察到该孙代理 running=true，随后收到 session-cancelled；历史 turn/end 为 aborted，reason 为 user |
| 断开重连 | iOS 客户端重新完成认证 hello，随后状态 ready，4 项任务、5 个完整成员路径与入口均保持，无列表累加重复 |
| 关闭 Team | 在 WebUI 原生确认弹层中停用测试 Team 后，真实 enabled=false；App 仍保留 4 项任务、5 个成员及可见入口 |

真实任务板为 task-1→reviewer、task-2→writer、task-3→未分配、task-4→reviewer/checker。测试会话与任务保留供复查，Team 模式已关闭；停止操作仅针对隔离测试孙代理。

## 验证方式与产物

iOS 使用已签名测试宿主中的生产 GatewayClient 与 AppStore，直接读取已有 Keychain 配对进行联调；实际 App 也经界面打开测试工作区、团队入口、成员列表和子会话。临时交互驱动验证结束后已从仓库测试源码移除，不会随普通测试运行。

Android 增加显式启用的 `TeamTaskCardDeviceTest.liveGatewayTeamFilteringAndOwnerNavigation`。它复用当前 App 主机及生产 AndroidSharedStateHolder，加载每层真实历史，操作团队菜单和任务面板，并验证筛选及负责人点击。默认没有 `rc3SessionId` 参数时跳过，不自动新建真实会话或任务。

在配置好相同样本数据的已配对模拟器上，可使用：

```sh
adb -s emulator-5558 shell am instrument -w \
  -e class com.clarklevis.dsh.android.ui.TeamTaskCardDeviceTest#liveGatewayTeamFilteringAndOwnerNavigation \
  -e rc3SessionId session-b2ccd705-b23a-423f-95af-e29b89360f63 \
  com.clarklevis.dsh.android.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Android 此项真实 UI 测试通过。iOS 临时驱动正常结束，外部断言额外核对了 assistant 的实际回复、错误父级没有 sent、停止前后的运行状态和用户中断记录；不能仅凭“驱动退出成功”代替这些断言。

此次证据保存在 `/tmp/dsh-rc3-live/`：`rpc-report.json`、`final-state.json`、`android-team.json`、`android-task.png`、协议帧及运行日志；iOS 测试结果包为 `/tmp/dsh-rc3-live-signed.xcresult`。这些产物不纳入应用资源或仓库。

## 尚未覆盖

- 此次是真实宿主/网关联调，但客户端运行在模拟器；未在物理手机上完成相同流程。
- 真实宿主任务会同时提供 ownerId 与 ownerName，只有 ownerId、未知 ownerId、缺少目录但保留历史任务等边界仍由已有自动化 fixture 回归覆盖。
- 未重新完成全部手机/平板窗口、横竖屏、大字号的完整像素及手势对照验收；已有尺寸截图验证与此次真实业务链路验证应分开看待。
