<div align="center">

<img src="Design/whale-girl-ios-app-promo-16x9-v3.png" alt="DeepSeek Harness Mobile iOS 应用展示" width="100%">

# DeepSeek Harness Mobile

**使用 Kotlin Multiplatform 共享核心逻辑，为 Android 与 iOS 提供原生移动端体验。**

通过 Mobile Gateway 在手机上访问 DeepSeek Harness 的工作区、会话、实时对话、Agent 轨迹与文件。

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![iOS 17+](https://img.shields.io/badge/iOS-17.0%2B-111827?logo=apple&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![SwiftUI](https://img.shields.io/badge/iOS-SwiftUI-F05138?logo=swift&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Realtime-2563EB)

</div>

## 项目简介

DeepSeek Harness Mobile（DshMobile）是面向 DeepSeek Harness 的社区原生移动客户端。项目已完成 Kotlin Multiplatform（KMP）架构改造，并同时提供可运行的 Android 与 iOS 应用：

- `shared` 负责跨端协议、状态机、Reducer、投影和同步逻辑，是共享业务状态的唯一来源。
- `androidApp` 使用 Kotlin、Jetpack Compose 和 OkHttp 实现 Android 产品能力。
- `DeepSeekHarnessMobile` 使用 SwiftUI/UIKit 构建 iOS 界面，并通过 Kotlin/Native framework 接入共享逻辑。

两端均通过 [`dsh-plugin-mobile-gateway`](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) 与 Harness 建立 WebSocket 连接。网络、安全存储、文件、图片、生命周期和后台任务由各平台原生实现，业务规则则尽可能收敛到 `shared/commonMain`。

## 当前平台状态

| 平台/模块 | 当前状态 | 主要实现 |
| --- | --- | --- |
| KMP `shared` | 已接入 Android 与 iOS | Gateway 协议、Store/Reducer、Conversation/Trajectory 投影、History 同步、Question/Approval、Session Control、工作区文件状态与代码预览支持 |
| Android | 已实现原生客户端 | Jetpack Compose、OkHttp WebSocket、DataStore（非敏感配置）、Keystore（凭据）、CameraX/ML Kit 扫码、前台服务、附件缓存、文件下载与预览 |
| iOS | 已实现原生客户端 | SwiftUI/UIKit、URLSession WebSocket、UserDefaults（非敏感配置）、Keychain（凭据）、二维码配对、后台执行、附件与工作区文件 |

> Android 与 iOS 使用各自的原生 UI 和平台能力，并不是共享 UI。功能和交互会持续对齐，但个别系统级行为可能因平台限制而不同。

## 功能亮点

- **原生实时对话**：处理 WebSocket 增量事件，展示 Markdown、代码、思考过程、工具调用、工具结果与图片附件。
- **历史与实时解耦**：分页加载历史记录时保留实时尾部，按序列与事件身份去重，避免消息重复或回退。
- **完整 Agent 轨迹**：查看 User、Assistant、Tool 等事件，以及调用参数、结果、Schema、Token 和耗时信息。
- **工作区与会话管理**：远程浏览、创建和切换 Harness 主机上的工作区，搜索、创建、切换及归档会话。
- **工作区文件**：浏览 Harness 主机上的远端目录，分块下载文件，校验完整性，预览常见代码文件或交给系统应用打开。
- **交互式 Agent 流程**：支持 Human Question 与工具审批请求，可提交、取消、拒绝或单次允许。
- **会话和默认配置**：管理 Agent 预设、Provider/模型、思考等级与 `read-only`、`workspace-write`、`danger-full-access` Harness Agent 权限；这些不是手机本地文件系统权限。
- **安全设备配对**：扫描 WebUI 二维码或手动输入配对信息；长期凭据保存到平台安全存储。
- **移动端生命周期**：Android 在 Agent 回合执行期间通过前台服务维持连接；iOS 使用系统授予的有限后台执行时间管理活动任务，不保证无限期维持 WebSocket。
- **浅色与深色主题**：两端均提供原生主题适配，并保留 Harness 的深海、网格与鲸鱼视觉语言。

## 界面预览

> 当前仓库中的界面截图来自 iOS 版本；Android 使用 Jetpack Compose 实现相同的信息架构和核心流程。

### 核心体验

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/home.png" alt="iOS 工作区首页" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/conversation-light.png" alt="iOS 浅色模式对话" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/trajectory-light.png" alt="iOS 浅色模式轨迹" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>工作区首页</strong><br>工作区与最近会话</td>
    <td align="center"><strong>对话</strong><br>Markdown、代码与工具结果</td>
    <td align="center"><strong>轨迹</strong><br>时间概览与事件时间线</td>
  </tr>
</table>

### 默认配置

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/settings.png" alt="iOS 应用设置" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/agent-presets.png" alt="iOS Agent 预设" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/default-model.png" alt="iOS 默认模型" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>设置</strong><br>部署默认值与 Gateway 状态</td>
    <td align="center"><strong>Agent 预设</strong><br>工具、提示词与能力组合</td>
    <td align="center"><strong>默认模型</strong><br>Provider、模型与思考等级</td>
  </tr>
</table>

### 工作区操作与轨迹详情

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/workspace-switcher.png" alt="iOS 切换工作区" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/workspace-directory-picker.png" alt="iOS 选择工作区目录" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/trajectory-detail-light.png" alt="iOS 轨迹详情面板" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>切换工作区</strong><br>快速访问不同项目</td>
    <td align="center"><strong>目录选择</strong><br>浏览并创建工作区</td>
    <td align="center"><strong>轨迹详情</strong><br>摘要、Token 与内容预览</td>
  </tr>
</table>

### 浅色、深色与设备配对

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/pairing-light.png" alt="iOS 浅色模式设备认证" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/pairing-dark.png" alt="iOS 深色模式设备认证" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/conversation-dark.png" alt="iOS 深色模式对话" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>浅色配对</strong><br>扫码或手动输入</td>
    <td align="center"><strong>深色配对</strong><br>完整深色模式适配</td>
    <td align="center"><strong>深色对话</strong><br>阅读、代码与输入面板</td>
  </tr>
</table>

## 跨端架构

```text
                         DeepSeek Harness
                                │
                  dsh-plugin-mobile-gateway
                                │
                             WebSocket
                ┌───────────────┴───────────────┐
                │                               │
     Android OkHttp Transport          iOS URLSession GatewayClient
                │                               │
       KMP Gateway Runtime             Swift Router / KMP Adapter
                │                               │
                └───────────────┬───────────────┘
                                │
                    KMP shared / commonMain
            Protocol · Store · Reducer · Projection
       History Sync · Question/Approval · Workspace Files
                ┌───────────────┴───────────────┐
                │                               │
       Jetpack Compose                     SwiftUI / UIKit
          Android UI                          iOS UI
```

### 共享层职责

- `protocol/`：Gateway DTO、JSON 表示与 wire decoder。
- `gateway/`：平台传输接口，以及配对、鉴权、请求关联、重连与事件背压策略。Android 使用完整共享 Runtime，OkHttp 只实现底层传输。
- `domain/`：Session、Question、Approval 和 Session Control 的 Reducer。
- `projection/`：Conversation 与 Trajectory 的增量投影。
- `sync/`：History 分页、实时尾部合并和去重。
- `facade/`：面向 Swift/Kotlin 平台层的粗粒度 Store、Intent、Event，以及工作区路径、分块顺序、进度与 SHA-256 校验逻辑。

共享层不依赖 Compose、SwiftUI、Android Framework 或 Apple UI Framework。iOS 复用共享 Store、Reducer、Projection 和文件状态机，但当前仍由 Swift `GatewayClient` 负责 WebSocket、连接与重连，再通过 Adapter 将事件送入共享层；它尚未接入完整的 KMP Gateway Runtime。

Android 和 iOS 均在平台侧负责安全存储、图片处理、临时文件落盘、系统分享/打开以及应用生命周期。共享层负责远端文件协议、状态流转、分块一致性和完整性校验，不直接操作平台文件系统。

## 项目结构

```text
.
├── shared/                         # Kotlin Multiplatform 共享业务模块
│   └── src/
│       ├── commonMain/             # 协议、状态机、投影、同步与 facade
│       └── commonTest/             # 跨平台单元测试
├── androidApp/                     # Android 原生应用（Jetpack Compose）
│   └── src/
│       ├── main/                   # 产品代码与平台实现
│       ├── test/                   # JVM 测试
│       └── androidTest/            # 设备测试
├── DeepSeekHarnessMobile/          # iOS 原生应用（SwiftUI/UIKit）
├── DeepSeekHarnessMobileTests/     # iOS XCTest
├── DeepSeekHarnessMobile.xcodeproj
├── Docs/                           # 调研、KMP 迁移与验证文档
└── Design/                         # 设计规范与展示素材
```

## 环境要求

### 通用

- Java 17
- 已启用 `dsh-plugin-mobile-gateway` 的 DeepSeek Harness
- 能够访问 Mobile Gateway 的模拟器或真机

### Android

- Android Studio 或 Android SDK Command-line Tools
- Android SDK Platform 36（`compileSdk` / `targetSdk`）
- Android 7.0（API 24）及以上设备或模拟器

### iOS

- macOS 与可构建 iOS 17.0+ 的 Xcode
- iOS 17.0+ 模拟器或真机
- Android SDK（Xcode 构建阶段会通过 Gradle 编译 KMP framework）

## 运行 Android

1. 配置 Java 17，并确保 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 Android SDK。
2. 在仓库根目录构建 Debug APK：

   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

3. 安装到已连接的设备或模拟器：

   ```bash
   ./gradlew :androidApp:installDebug
   ```

4. 启动 `DshMobile`，扫描 WebUI 生成的配对二维码，或手动输入配对信息。

Debug APK 位于：

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

当 Android Emulator 需要访问宿主机的 `127.0.0.1:3080` 时，可以先执行：

```bash
adb reverse tcp:3080 tcp:3080
```

## 运行 iOS

1. 配置 Java 17 和 Android SDK。
2. 使用 Xcode 打开 `DeepSeekHarnessMobile.xcodeproj`。
3. 选择 `DeepSeekHarnessMobile` Scheme 与目标设备。
4. 构建并运行。Xcode 中的 `Build KMP Framework` 阶段会自动选择并编译对应的 Kotlin/Native framework。
5. 在应用中扫描 WebUI 配对二维码，或手动输入配对信息。

无需提前手工构建 framework；如需单独生成 Apple Silicon iOS Simulator framework，可执行：

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## 连接与配对

1. 在 DeepSeek Harness 中安装并启用 `dsh-plugin-mobile-gateway`。
2. 打开 WebUI 的“移动设备”面板，启用移动设备连接与设备鉴权。
3. 确认 WebSocket 地址能被手机或模拟器访问。
4. 生成一次性配对二维码/Token，并在 Android 或 iOS 客户端中完成认证。
5. 返回首页，确认 Gateway 状态已连接，然后选择工作区和会话。

常见地址：

| 场景 | 地址 |
| --- | --- |
| iOS Simulator | `ws://127.0.0.1:3080/ws/mobile` |
| Android Emulator + `adb reverse` | `ws://127.0.0.1:3080/ws/mobile` |
| 同一局域网内的真机 | `ws://<HOST-LAN-IP>:3080/ws/mobile` |
| 公网部署 | `wss://<your-domain>/ws/mobile` |

> [!IMPORTANT]
> 真机不能使用 `127.0.0.1` 访问电脑。表中的 `ws://` 仅用于受信任的本地开发环境；公网或不可信网络必须使用 `wss://` 并正确配置 TLS 与设备鉴权。一次性配对 Token 不应写入日志、Issue 或聊天记录。

## 构建与测试

执行共享层测试、Android 单元测试、Lint 和 APK 构建：

```bash
./gradlew \
  :shared:allTests \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
```

有可用 Android 设备或模拟器时，可继续执行：

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

iOS 测试可在 Xcode 中选择 `DeepSeekHarnessMobile` Scheme 后执行 **Product → Test**。构建阶段会自动链接对应架构的 `DeepSeekHarnessShared.framework`。

## 相关文档

- [Harness / WebUI 功能与协议调研](Docs/exploration.md)
- [移动端设计规范](Design/design-spec.md)
- [第三方许可证说明](Docs/third-party-notices.md)

以下文档记录 KMP 迁移和 Android 落地过程，阶段性状态以本文档和当前代码为准：

- [KMP 开发说明](Docs/kmp-development.md)
- [Android Gateway 阶段 12 验证记录](Docs/kmp-stage12-android-gateway-verification.md)
- [Android UI 阶段 13 验证记录](Docs/kmp-stage13-android-ui-verification.md)

## 当前状态

项目处于持续开发阶段。KMP 共享层、Android 原生应用和 iOS 原生应用均已落地，协议、跨端一致性和移动端体验仍会随 DeepSeek Harness 与 Mobile Gateway 持续演进。

本项目是面向 DeepSeek Harness 的社区客户端，不代表 DeepSeek 官方发布。
