# App 实验性 WebRTC 接入

日期：2026-10-30。Android 和 iOS 接入网关 `webrtc-upgrade-v1`，业务协议、配对二维码和旧网关连接保持兼容。2026-10-31 更新：网关默认 P2P 优先，管理页“配对连接方式”提供与 Cloudflare 并列的“P2P 外网直连”。选择会保存，配对地址和 STUN 也可在界面配置，无需手改 YAML；显式关闭配置仍受尊重。

## 启用与显示

App 使用内网入口（私网 IP、回环地址或 `.local` 主机名）时直接建立 WebSocket，不发送 RTC 升级请求，不创建 RTC Peer，也不为升级重新建立配对连接。外网入口继续 RTC 优先，失败后按原有协议回退。判断依据是当前连接入口，不是手机是否连接 Wi-Fi；改用外网入口后会重新按外网策略连接。内网跳过升级的日志原因为 `lan-websocket`。

在支持该能力的网关中配置：

```yaml
webrtc:
  enabled: true
```

手机使用已保存的设备凭据连接后，先通过鉴权 WebSocket 校验网关身份，再尝试两个独立的 RTC PeerConnection。首次扫码只领取并保存凭据；保存成功后另开鉴权连接进行协商。

Android 与 iOS 设置页的 **Mobile Gateway → 连接方式** 显示实际业务承载：

- `WebSocket`：普通连接，或 RTC 协商失败且网关已显式确认回退。
- `WebRTC`：两条 DataChannel 均已绑定、网关确认选择、两个真实业务 hello 已收齐。设置与网络检测页分别列出控制通道、会话通道的实际 ICE 路径、UDP/TCP 和本地候选地址 → 远端候选地址（含端口）。
- 路径只读取 `transport.selectedCandidatePairId` 引用的成功候选对：双方私有 host 地址标为“内网直连”，srflx/prflx 标为“NAT 穿透直连”，任一方 relay 标为“TURN 中继”，其余明确选中的非中继路径标为“直连”。不凭 Wi-Fi、配对入口或未选中的 relay 候选猜测路径。公网 IPv6 也可能在同一内网中直连，因此不自动声称走了外网。
- 未获得明确选中候选对时显示“正在确认路径”，地址被平台隐藏时显示 `—`。连接生效时立即读取，前三秒重试，此后每五秒读取本地统计以跟随路径变化；不发送额外探测包，断开或回退时清除旧路径。
- `未连接`：业务握手未完成或连接已断开。

信令 WebSocket 在 RTC 生效后仍保持在线。网关关闭实验开关、设置 `ws-only`、缺少 RTC 运行库或旧版本不提供升级能力时，App 使用原有 WebSocket。不会隐式添加公共 STUN 或 TURN。

## RTC 回退日志

iOS 在 Xcode 控制台筛选 `[GatewayRTC]`（OSLog category 为 `gateway-rtc`）；Android 使用 `adb logcat -s DshGateway` 后查找同一前缀。RTC 固定字段诊断在发布包也会输出，禁用诊断的测试替身仍保持静默。

示例：`[GatewayRTC] event=fallback-requested reason=negotiation-timeout phase=negotiating channel=group mode=none elapsedMs=10500`。

- `negotiation-timeout`：RTC 协商超时；`selection-timeout`：选路提交未能及时确认。
- `native-rtc-unavailable`：原生 RTC 无法就绪。前一条 `native-failure` 会标出通道及 `ice-failed`、`ice-closed`、`datachannel-closed`、`sdp-offer-failed`、`sdp-set-failed` 或 `runtime-unavailable`。
- `gateway-rtc-unavailable`：网关要求回退；`rtc-connection-lost` / `connection-lost`：已建立的 RTC 或其依赖连接中断。
- `gateway-no-upgrade`：网关未提供 RTC 升级；`pairing`、`no-credential`、`no-gateway-identity`、`websocket-recovery` 表示此次连接为何没有发起 RTC。
- `message-too-large`：消息超过 RTC 上限，主动换新 WebSocket 连接。

`fallback-requested` 表示请求回退；后续 `selected ... mode=websocket` 才表示网关已确认使用 WebSocket。身份、协议或安全校验失败记录为 `blocked`，不会为了打印日志改变失败策略。日志只包含白名单元数据，不打印地址、token、票据、SDP、原始异常及业务内容，也不把超时直接判定为某种 NAT。

## 实现边界

- `shared/.../transport/RtcUpgradeMachine.kt`：两端共用的协商状态机。验证身份、信令会话与顺序、重复 JSON 字段、票据绑定、整组提交和失败策略。bootstrap 不向业务层发布，也不提前开启普通 conversation WebSocket。
- Android `RtcGatewayTransport` 在 `SplitGatewayTransport` 下复用虚拟双通道；`AndroidRtcPeer` 调用原生 libwebrtc；MGW1 使用共享 `MgwCodec`。
- iOS `GatewayRtcLink` 在 `GatewayClient` 内抽出传输边界；与 Android 共用协商状态机，Swift `GatewayMgwCodec` 直接读写 64 位整数头。
- 每条通道可靠、有序，仅传数据。临时 PC 在失败、退出、切换主机及关闭时释放；旧连接回调通过连接代次或实例身份丢弃。
- 原生库锁定为 Android `io.github.webrtc-sdk:android:144.7559.15` 和 iOS `stasel/WebRTC` `154.0.0`。SPM 归档校验值为 `a2bcdda93578c82452ceb6e49d54a2746e1bcb4caf7c2fa601ffac8028b58c16`。依赖来源：[Android SDK](https://github.com/webrtc-sdk/android)、[iOS Package](https://github.com/stasel/WebRTC/tree/154.0.0)。

协商超时不延长网关给出的总预算；预留最后 1.5 秒用于显式选择 WebSocket。提交状态不确定时关闭整组，下一次连接使用普通 WebSocket。安全校验失败或网关明确 `recoverable=false` 时停止自动恢复。

普通 RTC 故障后的第一次自动恢复使用 WebSocket；前台恢复则按下述限时策略优先尝试 RTC。用户主动连接也可以重新尝试 RTC。已发送的写请求不重放。现有业务恢复流程重新取得会话、历史与投影状态，文件传输不继承旧 owner 的上传/下载标识。

### iOS 前台恢复

回到前台时，对仍显示在线的 RTC 同时发送 control、conversation 业务 Ping，严格关联各自的 `rtc-resume:` Pong。两条通道均在 2 秒内响应时继续复用原有 RTC，不重新握手。探活超时、发送失败或后台已经断开时，如果进入后台前使用 RTC 且当前仍为外网入口，通过上次成功且仍受信任的入口重新鉴权，限时 3 秒尝试一次新的 RTC；协商失败或超时后立即使用 WebSocket，跳过普通重连退避。后台已先自动恢复 WebSocket 的情况也保留进入后台时的 RTC 偏好。内网仍直接使用 WebSocket。

2 秒是旧通道探活预算，3 秒是新 RTC 恢复预算，两者按实际经过的步骤累加；回退 WebSocket 的建连、凭证读取和鉴权另需时间。恢复失败后不会持续循环尝试 RTC，已发送的业务写请求不重放。

网络路径通知及冷启动的首次 active 通知不打断正在进行的握手，原有连接超时仍生效。原生 RTC 的普通连接失败属于可恢复传输故障；身份、协议或安全校验失败仍停止自动恢复。手动断开、再次进入后台或切换连接后，旧探活响应不能恢复旧连接。

Xcode 控制台筛选 `[GatewayLifecycle]`：`foreground-rtc-probe-ping` / `foreground-rtc-probe-pong` 表示业务探活，`foreground-rtc-probe-failed reason=pong-timeout channels=...` 显示未响应通道，`foreground-fast-reconnect transport=webrtc` 表示限时重新尝试 RTC，`foreground-rtc-recovery-timeout fallback=websocket` 表示尝试超时，`foreground-fast-reconnect transport=websocket` 表示立即回退且跳过退避，`foreground-recovered elapsedMs=...` 表示重新收到鉴权 hello 的耗时。`network-path-recovery-deferred reason=handshake-in-progress` 表示保留当前握手。

MGW1 使用最多 16 KiB 帧和默认 16 MiB 逻辑消息，连续序号从 1 开始，每通道一次只重组一条。非法头、跨通道、乱序、非法 UTF-8 或 JSON、30 秒缺片超时均终止整组。原生回调队列有 4 MiB 预算，发送有背压和 30 秒超时，关闭取消等待。

过大的请求在发送第一片前拒绝并换用新 WebSocket 连接，不自动提交。Android 保留未获确认的输入草稿；iOS 在清空草稿前对消息和图片的 JSON 大小计算保守上界，大消息会提前切换 WebSocket，提示用户连接完成后重新发送。不同承载之间不复制已发送写操作。

## 隔离实连测试

测试使用网关仓库自带的 Host 替身及自动生成的临时配对设备，不访问用户真实 Harness 会话，也不把 token 打到终端。

启动测试网关：

```sh
node scripts/webrtc-test-gateway.mjs /绝对路径/dsh-plugin-mobile-gateway
```

服务仅监听本机 `127.0.0.1:18765`，提供 RTC、关闭实验功能、100 ms 连接超时三个实例。它返回的凭据仅属于这些临时实例；使用结束后终止进程。

Android 模拟器可通过 `10.0.2.2:18765` 获取 fixture；若宿主网络不支持该入口，使用 `adb reverse` 转发 fixture 端口及 fixture 返回的三个 WebSocket 端口，再使用 `http://127.0.0.1:18765`。**需要模拟器具备可用的 UDP 网络路由**；仅转发 TCP 不能修复没有默认路由的模拟设备。

```sh
ANDROID_SERIAL=目标模拟器 ./gradlew :androidApp:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.clarklevis.dsh.android.RtcGatewayDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.rtcFixtureUrl=http://127.0.0.1:18765
```

未传 fixture 地址时，这项实连测试明确跳过。共享协议和普通 Android 单元测试不依赖测试网关。

iOS 指定已有模拟器和环境变量：

```sh
TEST_RUNNER_DSH_RTC_FIXTURE_URL=http://127.0.0.1:18765 \
xcodebuild -project DeepSeekHarnessMobile.xcodeproj \
  -scheme WorkspaceDrawerUITests \
  -destination 'platform=iOS Simulator,id=目标模拟器UUID' \
  CODE_SIGNING_ALLOWED=NO IPHONEOS_DEPLOYMENT_TARGET=17.0 \
  -collect-test-diagnostics never -parallel-testing-enabled NO \
  -only-testing:DeepSeekHarnessMobileTests test
```

使用共享 `WorkspaceDrawerUITests` Scheme 可避开本机同名用户 Scheme 对 `DeepSeekHarnessMobile` 测试配置的遮蔽；命令只运行 XCTest 单元测试。当前仓库部分原有测试直接使用 iOS 17 trait API，因此测试命令单独指定 17.0；不因此提高 App 的发布最低系统版本。没有 fixture 环境变量时，RTC 实连测试明确跳过。

## 验证范围

本轮记录见 [验收记录](webrtc-app-acceptance.md)。模拟器互通不能代替家宽/蜂窝、双重 NAT、IPv6、企业网络、后台恢复与真机功耗测试；本轮不发布 App 或网关，不修改线上配置。


2026-10-31 默认发现服务更新：P2P 默认使用 Cloudflare STUN，地址由网关通过 `iceServers` 下发，App 无需另填。管理页空列表恢复默认，自定义列表可覆盖；已有连接不强制切换，重新连接后生效。

## iOS 切换传输时的数据一致性

WebSocket 与 WebRTC 使用同一个 Gateway、会话和共享业务状态，不建立两份独立数据库。切换保留已经同步的可见历史，并在新连接完成鉴权后重新请求只读状态、重新订阅当前会话；会话窗口按新订阅的原子快照更新，再接收后续增量。

- 每次换连接生成新的连接代次。旧 Socket 从异步 receive 返回后，必须先核对 Socket 身份与连接代次，才能进入当前 RTC 协商或业务解码。
- 异步解码成功、解码失败及 RTC 发送错误返回时再次检查所属连接，旧代次的错误和状态回调不得覆盖新连接。
- 新旧订阅通过 subscriptionId、streamId 隔离，同一订阅的持久事件按 seq 去重；相同快照不重复创建会话内容，旧订阅的快照和事件不安装到新订阅。
- 已提交但未获确认的消息、命令不在新连接自动重放。静默取消本地等待并同步服务端状态，不额外显示待确认提示。

取消本地等待不会撤销服务端已经执行的操作，也不构成跨服务端的事务或通用“恰好执行一次”保证。文件传输等连接所属资源沿用原有断线清理和重新验证流程，不继承旧连接的 owner 标识。
