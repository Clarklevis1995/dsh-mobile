# iOS 自动连接卡住（冷启动 / 回前台）排查与修复

日期：2026-09-18。范围：iOS 客户端的自动连接门禁、回前台恢复与主机列表在线指示灯。

## 症状

已配对的手机在自动路径上一直停在“未连接/挂起”，主机列表的在线灯是灰的；只要点一次
“连接”（设置页按钮、或主机列表里点该主机）就立刻连上。

## 根因

`AppStore.connectOnColdLaunchIfPaired()` 在检查凭据**之前**就消费掉了一次性标记，而
`GatewayClient.hasStoredCredential(for:)` 把三种情况合并成同一个 `false`：

1. Keychain 里确实没有 token；
2. 设备尚未首次解锁 / App 预热，`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
   条目此刻读不到（`errSecInteractionNotAllowed`）；
3. 之前发生过 401，token 已被 `handleFailure` 删除。

情况 2 只在冷启动窗口出现：等用户真正打开 App 时设备已解锁，于是手动“连接”
（`AppStore.connect()`，不经过任何凭据门禁）必然成功。而自动路径已经记账为“处理过”，
`RootNavigationHost.task` 整个 AppStore 生命周期只跑一次，`handleScenePhase(.active)`
当时也不补连接，于是自动连接被永久放弃。

同一错误也影响主机列表：`refreshPresence()` 先清空 `onlineIDs`，随后探测路径用同一个
`hasStoredCredential` 判定，一次读不到就 `return`，已配对且在线的手机因此长期显示灰灯。

另外两个放大器：

- `isRecoveringFromBackground` 只在收到 `hello` 时清除（只有进后台会置位），一次没等到
  `hello` 的尝试会让后续网络失败的告警被永久抑制（`shouldReportFailure` 恒为 false）。
- `applicationDidBecomeActive()` 只在 `!state.isConnected` 时重开连接，状态停在
  `.connecting`（iOS 冻结 socket、超时任务被暂停）时不会重置这次尝试。

## 修复

- `GatewayClient` 新增 `CredentialState`（`available` / `missing` / `temporarilyUnavailable`）
  与 `credentialState(for:)`；Keychain 只有 `errSecItemNotFound` 才算“没有凭据”，其它
  `OSStatus` 一律抛 `GatewayCredentialError.unavailable`。凭据读取抽到
  `GatewayCredentialStoring` 协议后面，便于回归测试注入。
- `AppStore.connectOnColdLaunchIfPaired()` 改为仅在“真的发起了自动连接”或“确认从未配对”
  之后才记账；`temporarilyUnavailable` 时保留待重试状态、不产生用户可见失败。
  新增 `retryAutomaticConnectionIfNeeded()`，在 `handleScenePhase(.active)` 补齐自动连接，
  直到真正建立连接；正在 `.connecting` 时不重复拆建 socket。
- `applicationDidBecomeActive()` 回到前台即清除 `isRecoveringFromBackground`；若这次尝试
  已超过 `connectionAttemptTimeout`（15s）仍停在 `.connecting`，直接重开而不是继续等。
- `MultiGatewayStore` 的 presence 探测改为 `waitForReadableCredential`：短暂重试（最多
  4 次 / 750ms）后才判定读不到，并且读不到时继续尝试下一个候选地址而不是放弃整台主机。
- 传输层行为不变：自动连接仍要求“已配对才恢复”，未配对主机仍然不打开未鉴权 socket，
  也不会每次回前台重复弹提示。

## 证据

复现工程 `/tmp/dsh-probe`（SwiftPM）：mock 网关完成 WebSocket 握手后发送 `hello`，并要求
`Authorization: Bearer`；harness 直接使用仓库里的**真实** `GatewayClient.swift`，只额外插入
日志与“尝试次数”计数，并在 AppStore 门禁处并列运行修复前/修复后两套逻辑。

- 修复前（`--gate old`）：冷启动时 Keychain 条目不可读 → `attempts=0`；3s 后“设备解锁”、
  3.5s 回前台，仍是 `attempts=0`、`final=disconnected`，只剩一次提示。
- 修复后（`--gate new`）：冷启动不发起连接也不报错（`lastError=nil`、`attempts=0`）；
  解锁并回前台后 `attempt=1 credentialState=available`，0.03s 内 `connected`，随后 14s 保持
  `connected`、`lastError=nil`。
- 未配对主机（`--token none`）：修复后仍然不发起连接（`attempts=0`），只给一次引导提示。
- 传输层：握手成功但网关不发送 `hello` 时，15.0s 超时准时触发 → `failed` → 2s/4s/8s/16s/30s
  有界重连，不会永久停在 `.connecting`（`run.sh bare --seconds 25`）。
- 已连接时回前台不拆建 socket：`hello` 场景下 2s 连接成功，20s 再回前台仍是
  `connected`、`attempts=1`。
- 卡在 `.connecting` 的旧尝试：把超时任务冻结（模拟 iOS 暂停 App，`PROBE_FREEZE_TIMEOUT=1`）
  后，第一次尝试在 24.7s 仍未结束；再次回前台立刻发起 `attempt=2`
  （`foreground-again-at`），不再被动等待。

## 回归测试

新增 `DeepSeekHarnessMobileTests/GatewayCredentialRecoveryTests.swift`（含本地 mock 网关
`MockGatewayServer`，并已注册进 Xcode 工程）：

1. `testColdLaunchWithUnreadableCredentialConnectsAfterSceneBecomesActive`；
2. `testColdLaunchOnTemporarilyUnreadableCredentialStillReachesGateway`（读不到时不阻断，
   之后仍能连上，不再永久停留）；
3. `testColdLaunchWithoutAnyCredentialStillBlocksTheSocket`（未配对／手动连接不受门禁影响）；
4. `testForegroundReturnDoesNotChurnAnAlreadyConnectedSocket`；
5. `testPresenceProbeKeepsPairedHostOnlineAfterAFailedCredentialRead`；
6. `testLegacyHasStoredCredentialReportsReadabilityOnly`。

## 验证状态

- `swiftc -typecheck -swift-version 5`（真实 KMP framework + `-enable-testing -module-name
  DeepSeekHarnessMobile`）在 iOS Simulator SDK 与 iPhoneOS 设备 SDK 上各跑一次：
  `AppStore.swift`、`GatewayClient.swift`、`MultiGatewayStore.swift` 与新增测试文件
  **零错误**；输出中仅剩既有 `KMPSharedAdapter.swift` 的 18 条 ObjC 桥接噪音（离线
  typecheck 的固有现象，该文件本次未改动）以及一个仅用于本次检查的本地 stub 文件。
- `./gradlew :shared:linkDebugFrameworkIosArm64` 成功，设备侧 KMP framework 可链接。
- 复现工程 `swift build` 通过并给出上述 before/after 证据。
- **XCTest 套件未能在本机执行**：当前机器只有 iOS SDK，没有安装 iOS Simulator runtime
  （`xcrun simctl list runtimes` 为空，`xcodebuild -showdestinations` 报 “iOS 26.5 is not
  installed”）。`xcodebuild -downloadPlatform iOS` 需要 8.52 GB，实测吞吐约 0.3 MB/s
  （7 小时以上），不适合在本次会话内等待，已放弃；`./gradlew :shared:allTests` 同样因为
  `iosSimulatorArm64Test` 缺少 runtime 而失败。请在装有 runtime 的机器上执行：

  ```bash
  xcodebuild -project DeepSeekHarnessMobile.xcodeproj -scheme DeepSeekHarnessMobile \
    -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO test
  ```

- 真机人工验收未执行：需要一台已配对手机 + 真实 Mobile Gateway，且要复现“冷启动瞬间
  Keychain 不可读”（锁屏状态启动、或 App 预热）才能观察到原症状。
