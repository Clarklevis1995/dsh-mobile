# Whaleness / dsh-mobile：iOS 16.1 适配与构建安装说明

> 目标设备：iPhone 13 + iOS 16.1，通过 TrollStore 2 安装。
> 上游仓库：https://github.com/Clarklevis1995/dsh-mobile

## 一、先说明边界：做到了什么，没做到什么

| 项 | 状态 |
|---|---|
| 源码降到 iOS 16.1（部署目标 + 全部 iOS 17/16.4/16.2 专属 API） | 已改完，逐处可查 |
| 清除工程里作者硬编码的签名标识（`T58RY5HUA7` / `com.lichaofan.dshmobile`） | 已改完 |
| GitHub Actions 流水线（macos-26 编译 → ad-hoc 签名 → 出 ipa） | 已跑通 |
| **实际编译 + 归档 + 签名 + 出 ipa** | **已验证**：Actions run `40092268487` 成功，产物 13 MB |
| **真机运行时行为** | **未验证**。需要装到 iPhone 13 上实测 |

## 构建结果（已验证）

| 项 | 值 |
|---|---|
| 仓库分支 | `summ9919/dsh-mobile` @ `ios-16.1` |
| 成功运行 | Actions run `40092268487`，全 10 步通过 |
| 产物 | `Whaleness-iOS16.1-trollstore.ipa`，13 MB（解包 25 MB / 36 个条目） |
| `CFBundleIdentifier` | `com.example.dshmobile` |
| `MinimumOSVersion` | **16.1** |
| `NSSupportsLiveActivities` | `true` |
| 内嵌扩展 | `PlugIns/AgentLiveActivityWidget.appex`（`MinimumOSVersion` 同为 16.1） |
| 签名 | ad-hoc（`codesign -f -s -`），`_CodeSignature/` 存在；扩展与主包均已签名 |
| 编译错误修复轮次 | 3 轮，共修 54 处（53 个可用性/类型错误 + 1 处类型不匹配） |

## 二、编译环境（硬性要求，缺一不可）

| 组件 | 要求 | 原因 |
|---|---|---|
| 操作系统 | **macOS 26 Tahoe**（CI 用 `macos-26` runner） | Xcode 26 只支持 macOS 15.6+ 以上 |
| Xcode | **26.x** | 项目用了 `glassEffect` / `safeAreaBar` 等 iOS 26 API（已用 `#available(iOS 26.0, *)` 包裹），必须用 iOS 26 SDK 编译 |
| iOS SDK | iOS 26（随 Xcode 26 提供） | 同上 |
| 部署目标 | **iOS 16.1** | Apple 官方：Xcode 26 支持的部署目标区间是 **iOS 15–26**，16.1 在允许范围内 |
| JDK | **17**（Temurin） | Gradle 构建 KMP framework；root `shared` 模块 `compileSdk = 36`，AGP 8.x 需要 JDK 17+ |
| Android SDK | **Platform 36** + 已接受 license | `shared` 模块含 `android {}` 目标，Gradle 配置期就要 SDK，否则直接失败 |
| Metal Toolchain | Xcode 26 起需单独下载：`xcodebuild -downloadComponent MetalToolchain` | 构建阶段 `Build Metal Library` 要调 `metal` 编译器 |
| 网络 | 可访问 GitHub、Maven Central、Google Maven | 拉 SwiftPM 依赖（ChatLayout 2.4.3、NetworkImage、swift-cmark）与 Gradle 依赖 |

Xcode 构建阶段还会自己跑一次 `gradlew :shared:linkReleaseFrameworkIosArm64`（见 `project.pbxproj` 的 `Build KMP Framework` 阶段），所以 Xcode 进程也必须能拿到 `JAVA_HOME` 与 `ANDROID_HOME`——CI 里这两个都由前序步骤导出到环境变量。

## 三、签名配置要求

### 3.1 TrollStore 路线（你选的，推荐）

**不需要** Apple 开发者账号、不需要证书、不需要描述文件、不需要设备 UDID。

- 签名方式：构建产物用 `ldid -S` 做 **ad-hoc 签名**（`codesign` 的 `-` 身份等价），CI 已自动完成。
- 安装前提：**设备上必须先装好 TrollStore 2**。iPhone 13（A15）在 iOS 16.1 上属于 TrollStore 2 支持范围（iOS 14.0–16.6.1 / 17.0）。TrollStore 靠 CoreTrust 漏洞跳过签名校验，所以 ad-hoc 包能永久安装，不会 7 天过期。
- 首次安装 TrollStore 本身需要一个安装器（如 TrollInstallerX）。这一步仍然要用 AltStore/Sideloadly 之类带 Apple ID 的方式侧载一次安装器——**只有这一次需要 Apple ID**，之后装 Whaleness 不再需要。
- Bundle ID：CI 默认用 `config/Debug.xcconfig` 的 `com.example.dshmobile`。TrollStore 不做唯一性校验，可原样使用；想换就在 Run workflow 时填 `bundle_id` 输入框。

### 3.2 如果将来要改用 Xcode 真机调试 / 自签

上游已经预留了本地签名入口，我已把 **Release 配置也接上**这个 xcconfig（原来漏了，导致 Release 下 `$(LOCAL_*)` 解析为空）：

```bash
cp config/Local.xcconfig.example config/Local.xcconfig
# 填自己的：
# LOCAL_BUNDLE_IDENTIFIER = com.<你的名字>.dshmobile
# LOCAL_DEVELOPMENT_TEAM = <10 位 Team ID>
```

- 免费 Apple ID：7 天有效、最多 3 个 App、需重复签名。
- 付费开发者（$99/年）：1 年有效，可 TestFlight。

## 四、改动清单

### 4.1 工程配置

| 位置 | 改动 |
|---|---|
| `project.pbxproj` 8 处 `IPHONEOS_DEPLOYMENT_TARGET` | `17.0` → `16.1`（App / Tests / UITests / LiveActivityWidget × Debug/Release） |
| `project.pbxproj` 3 处 `DEVELOPMENT_TEAM = T58RY5HUA7` | 改为 `"$(LOCAL_DEVELOPMENT_TEAM)"` |
| `project.pbxproj` 3 处 `PRODUCT_BUNDLE_IDENTIFIER = com.lichaofan.dshmobile*` | 改为 `"$(LOCAL_BUNDLE_IDENTIFIER)*"` |
| 4 个 Release 配置 | 补 `baseConfigurationReference` 指向 `config/Debug.xcconfig`（否则 Release 下这两个变量是空串，包名会变成空） |

### 4.2 代码降级

| API | 最低系统 | 处理 |
|---|---|---|
| `.onChange(of:) { old, new in }` 双参数 | iOS 17 | 30 处统一改为 `.onChangeCompat(of:)`，由 `Theme.swift` 里的兼容层还原"新旧值"语义 |
| `ContentUnavailableView` | iOS 17 | 9 处改为 `DshEmptyState`（自绘，覆盖代码里用到的 3 种构造形式） |
| `scrollBounceBehavior` | iOS 16.4 | 6 处直接删除（纯滚动手感） |
| `presentationBackground` | iOS 16.4 | 5 处改为 `.background(...)` |
| `presentationCompactAdaptation` | iOS 16.4 | 1 处删除（iPhone 上 popover 本来就会降级为 sheet） |
| `contentMargins`（Theme 自适应宽度） | iOS 17 | 改为 `.padding(.horizontal, ...)` |
| `contentMargins` / `contentMarginsDisabled`（灵动岛区域，Widget） | iOS 17 | 删除，用默认边距（iPhone 13 无灵动岛，无实际影响） |
| `.defaultScrollAnchor` | iOS 17 | 1 处删除 |
| `.geometryGroup()` | iOS 17 | 1 处删除 |
| `visualEffect` + `ShaderLibrary`（Metal 流体背景） | iOS 17 | 保留 17+ 原实现，16.1 走静态渐变兜底；网格与鲸鱼粒子图层（Canvas，iOS 15+）不受影响 |
| `onGeometryChange` | iOS 18 | 3 处改为 `GeometryReader` + `onChangeCompat` 上报尺寸 |
| `ActivityViewContext.isStale` | **iOS 16.2** | 9 处改为 `isStaleCompat`（16.1 恒按"未过期"处理） |
| `LiveActivityIntent` + `Button(intent:)` | iOS 17 | 两个 Intent 标注 `@available(iOS 17.0, *)`；16.1 上实时活动里的审批按钮显示为"在 App 内处理审批" |

### 4.2b 编译阶段才发现、静态扫描漏掉的 8 类问题（已按编译错误逐条修完）

上游用 `#available` 包住了 UI 层的 iOS 26 API，但**非 UI 层的系统 API 版本要求没管**，只有真正编译才暴露：

| API | 最低系统 | 处理 |
|---|---|---|
| `ActivityContent` / `Activity.request(attributes:content:pushType:)` / `update(_:)` / `end(_:dismissalPolicy:)` / `activity.content` | **iOS 16.2** | 在 `AgentLiveActivityManager.swift` 尾部加 `requestCompat` / `stateCompat` / `updateCompat` / `endCompat` 四个兼容方法：16.2+ 走 ActivityContent，16.1 走 `contentState` 版 API（`request(attributes:contentState:)`、`update(using:)`、`end(using:)`）。**36 个错误一次性收敛** |
| `AVAudioApplication.recordPermission` / `requestRecordPermission` | iOS 17 | 回退到 `AVAudioSession.sharedInstance()` 的同名 API |
| `CLBackgroundActivitySession` | iOS 17 | 属性改为 `AnyObject?` 承载，17+ 才创建，关闭时 `as?` 降级 |
| `UIHostingController.safeAreaRegions` | **iOS 16.4** | 2 处包 `#available(iOS 16.4, *)` |
| `WKWebView.traitOverrides` / `updateTraitsIfNeeded` | iOS 17 | 17+ 用 traitOverrides，16.1 退回 `overrideUserInterfaceStyle` |
| `Chart` 的 `SectorMark` | iOS 17 | 17+ 保留环形图，16.1 退回占位圆环 |
| `.animation(_:body:)`、`Text.foregroundStyle`、`AVCaptureConnection.videoRotationAngle` | iOS 17 | 分别改为 `.animation(_:value:)`、`.foregroundColor`、`.videoOrientation`（含 `UIInterfaceOrientation → AVCaptureVideoOrientation` 显式映射） |

教训：**`#available` 只守卫了 UI 层**。这类项目要降到更低系统，必须先跑一次真编译拿到完整错误清单，静态 grep 一定漏。

### 4.3 已确认无需改动

- `@Observable` / Observation：**项目零使用**（grep 命中的是变量名 `pairingObservation`），状态层是 Combine `ObservableObject`，无需重构。
- `Info.plist` 已含 `UIApplicationSceneManifest`：iOS 26 SDK 编出的 App 缺这个键会启动即崩，上游已处理。
- `Vendor/swift-markdown-ui` 的 `Package.swift` 声明 `.iOS(.v15)`，16.1 兼容。
- `import Charts`（iOS 16）、`import AppIntents`（iOS 16）、`import ActivityKit`（iOS 16.1，正好是本项目的最低线）。
- 全部 `glassEffect` / `.buttonStyle(.glass)` / `safeAreaBar` 调用点都已包在 `#available(iOS 26.0, *)` 内且有 else 分支。

## 五、怎么构建

> **当前状态：已经构建好了。** 产物 `Whaleness-iOS16.1-trollstore.ipa`（13 MB）可直接装。
> 下面这段是「以后要重新构建 / 换 bundle id」时用的。

```bash
# 方案 A（推荐）：直接用已经推好的分支，不需要重新 patch
git clone -b ios-16.1 https://github.com/summ9919/dsh-mobile.git
cd dsh-mobile
# 打开仓库 Actions 页面 → "Build iOS 16.1 IPA (TrollStore)" → Run workflow
# （可选）bundle_id 输入框填自己的包名，留空则用 com.example.dshmobile

# 方案 B：从上游干净克隆 + 打补丁
git clone https://github.com/<你的用户名>/dsh-mobile.git
cd dsh-mobile
git apply --whitespace=nowarn ../ios16.1-adaptation.patch
git add -A
git commit -m "feat: support iOS 16.1 (deployment target + API backport) and add TrollStore CI"
git push
```

单次构建约 **11 分钟**（Gradle 6 分钟 + Xcode 归档 5 分钟）。跑完在 Actions 的 Artifacts 里下载 `Whaleness-iOS16.1-trollstore`。

## 六、TrollStore 安装

1. 确认 iPhone 13 上 TrollStore 2 已就绪（打开 TrollStore 能看到 Apps 列表）。
2. 把 ipa 传到手机（AirDrop / 文件 App / 局域网）。
3. TrollStore → 右上角 `+` → 选 ipa → Install。
4. 首次启动若提示"未受信任的开发者"，回 TrollStore 里对该 App 执行一次 `Refresh App Registrations`。

不需要 Apple ID，不会 7 天掉签，不用重签。

## 七、验证清单（首次跑完 CI 请按这个顺序看）

1. CI 是否卡在 Gradle（KMP）——若是，看是不是 `ANDROID_HOME` / JDK 没生效。
2. 是否卡在 Metal 编译——若报 `metal: not found`，是 `MetalToolchain` 没下载成功。
3. Swift 编译错误——最可能出现的地方（按概率排序）：
   - `Theme.swift` 的 `DshEmptyState` 泛型初始化器与调用点匹配；
   - `ConversationView.swift` 里 `GeometryReader` 替换后的布局约束；
   - Widget 里 `isStaleCompat` 扩展的类型推断。
4. 装到手机后重点看：能否连上网关（WebSocket）、Markdown 渲染、图片附件、语音输入、锁屏实时活动。

## 八、已知降级与风险

**观感降级（可接受）**：Metal 流体背景变成静态渐变；空状态页是自绘版而非系统版；部分滚动手感修饰符缺失。

**功能降级**：实时活动里的"允许/拒绝"按钮在 16.1 上不可点（iOS 17 才有交互式实时活动），需回 App 内处理审批。

**未验证风险**：
- `ldid -S` 默认不带任何 entitlement。若启动后遇到钥匙串/安全存储相关报错，需要加 entitlements 重新签名（`ldid -S<file>`）。
- TrollStore 下 App 扩展（Live Activity Widget）能否正常加载未经实测；若不加载，实时活动不显示，主 App 功能不受影响。
- iOS 26 SDK 编译、跑在 iOS 16.1 上的运行时兼容性只能实测。

## 九、回退

```bash
git checkout -- .
# 或
git reset --hard origin/main
```

适配改动全部集中在上面第 4 节的 13 个文件 + 1 个新增 workflow，没有触碰业务逻辑、协议层、KMP 共享层。
