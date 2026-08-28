# 阶段 12 Android Gateway 验证清单

> 自动化只证明 fake transport、协议关联与构建通过。本文的“真实 Gateway”部分必须由开发者连接实际 Mobile Gateway 后执行；在没有真实环境和设备结果前，不得标记为通过。

## 实现边界

- `commonMain` 的 `GatewayTransport`、`GatewayPreferences`、`GatewayCredentialStore`、`GatewayAttachmentCache`、`GatewayNetworkMonitor` 和 `GatewayClock` 是平台边界。
- `GatewayRuntime` 拥有 version 2 配对解析、`paired`/`hello` 鉴权边界、请求关联、重连、订阅恢复、活动 turn 和附件字节校验。
- Android 平台拥有 OkHttp socket、ConnectivityManager、DataStore、Android Keystore、文件 cache、ContentResolver/Bitmap、进程生命周期和前台服务。
- 普通 `gateway.preferences_pb` 只保存 endpoint、workspace 和 session JSON。token/device id 经 Android Keystore AES-GCM 加密后才进入独立的 `no_backup/gateway_credentials.preferences_pb`；产品代码没有网络日志拦截器。Debug 仅以 `DshGateway` 输出结构化元数据，Release 关闭该诊断器；两者都不得输出 endpoint 全文、Authorization、pairingCode、token、消息正文、Session/附件 ID 或 Base64。
- 连接默认只在前台维持。用户发出消息后，到对应 `turn/end` 前启动 `connectedDevice` 前台服务并显示低打扰通知；空闲进入后台会主动挂起，回到前台或网络恢复后重新连接并恢复订阅。
- release manifest 禁止明文流量；仅 debug manifest 为开发机/LAN `ws://` 冒烟开启 cleartext。生产或跨不可信网络必须使用 `wss://`，endpoint 禁止携带 URL user-info。
- Application graph 单例持有 Runtime、Projection 和 StateHolder，Activity ViewModel 仅作为 UI facade；配置重建不重新创建 socket 或清空共享投影。

## 自动化命令

```bash
./gradlew :shared:allTests --rerun-tasks
./gradlew :androidApp:testDebugUnitTest --rerun-tasks
./gradlew :androidApp:connectedDebugAndroidTest --rerun-tasks
./gradlew :androidApp:lintDebug :androidApp:assembleDebug
git diff --check
stat -f '%z bytes' androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

fake transport 与 Android JVM 门禁覆盖（不等价于真实 Gateway/设备测试）：

1. 已保存 token 冷启动连接，token 只从 `GatewayCredentialStore` 进入 transport spec。
2. `hello` 后自动请求 `workspaces`/`sessions`。
3. 全局响应允许缺少 `sessionId`；单 session 响应若显式携带错误 `sessionId`，在 UI/store 前拒绝。
4. message/question 的 busy 明确拒绝与 UI 输入保留，attachment 三项 FIFO，history coalesce 替换通知；active timeout/cancel、queued 推进、generation 晚到隔离与 A/B session 关联。
5. attachmentId/question rpcId 严格关联；无 requestType 或缺少强关联字段的 error 保守释放 lane；Base64 与声明字节数一致、磁盘提交成功后才发布。
6. OkHttp 生产队列按 callback 顺序交付 frame/failure；文本不创建整帧 UTF-8 副本，单帧 16 MiB、8 帧、累计 24 MiB，压力溢出显式 failure，恢复超时关闭 transport 后旧 generation 的 hello 不能从 FAILED 恢复为 connected。
7. 非空 history 多页 effect 与 live chunk/final 交错经 `SharedHistoryStore` / `SharedConversationStore` 去重投影，订阅 payload 与重连后投影保留均有组合 fake 覆盖。
8. 32 MiB/7 天磁盘 cache、16 MiB 访问型 UI 缩略图 LRU、最多 256 项状态、LazyColumn 可见项按需请求、失败负缓存/显式重试、跨 session 淘汰、图片输入硬字节上限和高分辨率采样；API 35 instrumentation 真实压缩图 decode 不超过 1024 边长/1,048,576 像素。
9. 新 session 的 `sent` 关联、明确 message error/`turn/end`/disconnect 释放后台保活；活动 turn 在后台断网时保留，在有界恢复窗口重连，send 失败只释放一次。
10. hello 自动恢复与 deferred 请求按 response kind 去重，快速 subscribe/session 切换经过两轮 hello 后 socket 数稳定；stored-connect 在 CONNECTING/AUTHENTICATING/CONNECTED 幂等。
11. 401/4003 在新用户 intent 前不自动重连；MVI 在临时状态严格验证 domain/schema/sequence/transaction、未知字段、patch/operation/target/index/effect 后原子提交，坏 payload/effect 零状态/零 I/O 并永久 fail-closed，transaction 窗口最多 64；token/Base64/message 的 DTO 描述不会泄露内容。
12. Runtime 到 Android 投影使用 8 事件/48 MiB 双边界背压队列；attachment 成功落盘后事件不再携带 raw Base64。产品组合 instrumentation 以 1 MB frame 验证 decode/MVI 在后台串行 dispatcher 执行，Main 只提交 UI state。
13. 后台最后一个 `turn/end` 立即关闭并进入 `SUSPENDED`；`WAITING_FOR_NETWORK` stored-connect 不重开 socket或取消恢复 deadline；凭据读取失败关闭旧 transport。Application graph 单例 Projection/StateHolder 在 Activity finish/reopen 后保留 baseline。
14. history/conversation 使用 per-session domain sequence 拒绝回退和非法重复；附件缓存使用 session+attachment 复合键。同屏缩略图超过 16 MiB 时同步淘汰状态为 `DEFERRED`，不自动反复加载，并按实际显示像素预算采样。
15. Android Projection 的 Frame、select、fixture、reset、history terminal 与 Main snapshot 发布由同一 actor 串行提交；Frame→select 和 frame/reset/fixture 确定性交错不会让旧结果晚覆盖。消息发送使用不可变输入快照和 generation 条件清理；后台 message timeout 进入 `SUSPENDED`，前台自动重连；非 clear history replace 不允许 tail 回退。

## 真实 Gateway 冒烟入口

1. 启动实际 Mobile Gateway，并在 WebUI 生成未过期的 version 2 配对字符串。
2. 构建并安装：

   ```bash
   ./gradlew :androidApp:installDebug
   adb shell am start -n com.clarklevis.dsh.android/.MainActivity
   ```

3. 在首页“Android Gateway 开发冒烟入口”粘贴 Base64URL 配对字符串，点击“配对”。不得把 pairingCode 或长期 token 粘贴到日志、Issue 或本文。
4. 必须依次看到 `CONNECTING`、`AUTHENTICATING`、`CONNECTED`；会话列表自动出现。断开并点击“连接”，确认不需要再次粘贴配对信息。
5. 选择一个真实 session。确认客户端发送 `subscribe` 与 `history(maxMessages=50,maxBytes=4194304,view=mobile)`，历史消息出现且实时尾部继续更新。
6. 发送一条长回答消息。确认先收到 `sent`，随后流式 `event` 连续更新，最终 `assistant/message`/`turn/end` 不重复、不回退。
7. 回答进行时切到后台：允许通知权限后，应看到“DeepSeek Harness Agent 正在运行”通知；回答结束后通知消失。空闲切后台不应保留常驻服务，回到前台应自动恢复连接和原 session 订阅。
8. 回答过程中关闭 Wi-Fi/移动网络，再恢复网络。状态应进入 `WAITING_FOR_NETWORK` 后自动连接；已渲染的 session/conversation 不清空，恢复后不重复提交原消息。
9. 点击“选择图片”，分别测试一张限制内原图和一张超大/带旋转信息的图片。限制内原图保留原格式；超限图片等比缩放并转 JPEG，最长边不超过 2000、总像素不超过 4000 万、字节数不超过 3,670,016。发送后在历史中能重新下载并显示附件。
10. 在应用信息中强制停止后重新打开，确认可用长期凭据恢复；清除应用数据后必须重新配对。

## 安全与 Console 人工检查

```bash
adb logcat -c
adb logcat -s DshGateway:D '*:S'
```

- 关键路径应能看到 `intent`、`transport opening/open/failed`、`runtime state/frame/request-*` 和 `lifecycle`；仅包含状态、frame kind、请求类型、连接 generation、关闭码、字节数和布尔关联标记。
- 日志不得出现 `Authorization`、`Bearer`、`dsh-pair.`、配对字符串或 paired frame 的 token。
- `files/datastore/gateway.preferences_pb` 不得含 token/device id；`no_backup/gateway_credentials.preferences_pb` 只允许出现 AES-GCM 密文和非敏感索引。
- HTTP 401 会删除对应失效 token 并停止自动重连；HTTP 503 / close 4004 保留凭据并重试；close 4003 停止重试并要求人工重连/重新配对。
- 对端返回错误 session 的 history/attachment/control 响应，不得进入 UI 或覆盖当前 session。

## 待人工填写

本轮自动化记录（2026-08-28）：第五轮统一 `--rerun-tasks` 门禁中 KMP iOS Simulator 89 项、Android JVM 22 项均 0 失败，Lint 0 issue，release merged manifest 为 `usesCleartextTraffic=false`，Debug APK 为 13,769,139 bytes；Pixel 9 AVD / Android 15 / API 35 instrumentation 5 项通过，包含真实压缩图、Activity 配置重建与 finish/reopen baseline、同屏缩略图预算，以及可注入 AndroidAppGraph 的 Runtime→Holder→Projection/history terminal/手动 visible attachment cache 组合 fake。JVM 另覆盖 actor 的 Frame→select、frame/reset/fixture 确定性交错和输入 generation；该组合测试手动提供 visible ID，未覆盖 Compose LazyColumn viewport 计算或真实跨 session Gateway。首轮人工点击“连接”发现 `AndroidGatewayClock.delay()` 同名递归导致 `StackOverflowError`，修复为完全限定的协程 delay 后新增 JVM 回归；随后补充仅 Debug 生效的 `DshGateway` 结构化诊断器与脱敏测试，Android JVM 当前为 24 项，Lint 0 issue，当前 Debug APK 为 13,785,523 bytes。真实设备已验证 application→bearer opening→open→hello→workspaces→sessions→CONNECTED 日志，未包含敏感字段。最终 APK 安装/冷启动成功；尚未完成其余真实 Mobile Gateway 业务验证，以下项目仍未执行。

- 设备与 Android 版本：Pixel 9 AVD / Android 15 / API 35
- Mobile Gateway 版本/commit：待填写
- endpoint 类型（LAN `ws` / TLS `wss`）：本机 `ws://127.0.0.1:3080/ws/mobile`，模拟器通过 `adb reverse tcp:3080 tcp:3080` 访问
- 配对、冷启动凭据恢复：部分通过；已保存凭据连接成功，首次 version 2 配对与强制停止冷启动仍待执行
- 会话、消息、流式、历史：部分通过；已收到 `sessions` 并显示真实会话列表，订阅、历史、消息与流式仍待执行
- 断网/后台/通知：未执行
- 图片上传/下载/cache：未执行
- 敏感信息与 Console：未执行

全部填写并通过后，才能勾选计划中的 12.6.2 和阶段 12 总验收。
