# KMP 开发说明

## 模块边界

```text
androidApp ──> shared <── KMPSharedAdapter <── SwiftUI/AppStore
   Compose      commonMain             iOS 薄边界
                ├── protocol
                ├── domain
                ├── data
                └── usecase
```

- `shared`：Kotlin Multiplatform library，承载协议模型和纯业务逻辑；不得依赖 Android UI 或 Apple UI 框架。
- `androidApp`：原生 Android application，只负责 Compose UI、Android 生命周期和平台 effect。
- `DeepSeekHarnessMobile`：现有 SwiftUI application；通过 `KMPSharedAdapter` 和粗粒度 `SharedMobileFacade` 链接共享静态 framework，现有网络、持久化、生命周期和 UI 行为仍留在 Swift。

## 工具链

- Java 17
- Gradle 9.1.0
- Android Gradle Plugin 9.0.1
- Kotlin 2.3.20
- compileSdk / targetSdk 36
- minSdk 24

本机 Android SDK 默认位于 `/Users/lichaofan/Library/Android/sdk`。不要提交 `local.properties`；命令行环境没有配置 `ANDROID_HOME` 时，应显式传入：

```bash
ANDROID_HOME=/Users/lichaofan/Library/Android/sdk ./gradlew tasks
```

## 验证命令

```bash
ANDROID_HOME=/Users/lichaofan/Library/Android/sdk \
  ./gradlew :shared:allTests \
  :androidApp:testDebugUnitTest \
  :androidApp:assembleDebug

ANDROID_HOME=/Users/lichaofan/Library/Android/sdk \
  ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 \
  :androidApp:lintDebug
```

主要产物：

- Android APK：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- iOS Simulator framework：`shared/build/bin/iosSimulatorArm64/debugFramework/DeepSeekHarnessShared.framework`

## iOS 集成

Xcode target 的 `Build KMP Framework` phase 会根据 Debug/Release、Simulator/Device 和当前架构调用对应的 Gradle link task。Swift 仅从 `KMPSharedAdapter` 访问 `SharedMobileFacade`，不直接耦合内部 Reducer。

首次在新机器构建前需要安装 Java 17、Android SDK，并保证 `ANDROID_HOME`、`ANDROID_SDK_ROOT` 或默认的 `$HOME/Library/Android/sdk` 之一有效。之后直接在 Xcode 构建和测试即可，无需手工预编译 framework。

## Android 人工测试

安装并启动 Debug APK 后：

1. 确认首页显示 `DeepSeekHarnessShared · schema 1`。
2. 点击“加载共享 Fixture”，确认出现一条 `android-demo` 运行中 Session、两条对话和“待回答 Human Question：1”。
3. 点击“交给 KMP decoder / reducer”，确认最后 frame 为 `event`，流式临时消息被“最终消息会替换流式临时消息。”替换。
4. 将 Gateway JSON 改为无效文本并提交，确认页面显示错误但既有 Session 和 Conversation 没有丢失。
5. 点击“重置”，确认共享状态清空且应用不崩溃。

## 后续迁移规则

1. 先在 `commonMain` 建立平台无关模型和 Reducer，再由 Android UI 接入。
2. Swift 现有实现继续作为行为基线，迁移一组逻辑就同步建立 `commonTest` fixture。
3. 网络、磁盘、Keychain、后台任务和 UI 生命周期不得直接进入 `commonMain`。
4. Swift 通过 `KMPSharedAdapter` 访问粗粒度 facade；不要从 SwiftUI 直接操作 Kotlin Reducer 或复杂协程类型。
