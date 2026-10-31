<div align="center">

**English** · [简体中文](README.zh-CN.md) · [日本語](Docs/readme/README.ja.md) · [한국어](Docs/readme/README.ko.md) · [Español](Docs/readme/README.es.md) · [Français](Docs/readme/README.fr.md) · [Deutsch](Docs/readme/README.de.md) · [Português (Brasil)](Docs/readme/README.pt-BR.md)

<img src="Design/whale-girl-ios-app-promo-16x9-v3.png" alt="Whaleness iOS app showcase" width="100%">

# Whaleness — A Third-Party Mobile Client for DeepSeek Harness

**Native Android and iOS apps with core business logic shared through Kotlin Multiplatform.**

Connect to DeepSeek Harness through Mobile Gateway to access workspaces, live conversations, Agent traces, plugins, scheduled tasks, and files from your phone, tablet, or foldable.

Website: [Whaleness](https://clarklevis1995.github.io/dsh-mobile/)

<p>
  <a href="https://testflight.apple.com/join/1Euwby6r"><img src="Docs/images/badges/testflight.svg" alt="Join the iOS TestFlight beta" width="180" height="60"></a>
  &nbsp;&nbsp;
  <a href="https://github.com/Clarklevis1995/dsh-mobile/releases"><img src="Docs/images/badges/android-github.svg" alt="Download the Android APK from GitHub Releases" width="180" height="60"></a>
</p>

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![iOS 16.1+](https://img.shields.io/badge/iOS-16.1%2B-111827?logo=apple&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![SwiftUI](https://img.shields.io/badge/iOS-SwiftUI-F05138?logo=swift&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Realtime-2563EB)

</div>

## Overview

Whaleness is an independently developed third-party native mobile client for DeepSeek Harness. Both Android and iOS apps use Kotlin Multiplatform (KMP):

- `shared` is the source of truth for shared business state, including protocol handling, state machines, reducers, projections, and synchronization.
- `androidApp` implements the Android app with Kotlin, Jetpack Compose, and OkHttp.
- `DeepSeekHarnessMobile` builds the iOS interface with SwiftUI/UIKit and accesses shared logic through a Kotlin/Native framework.

Both apps connect to Harness over WebSocket through [`dsh-plugin-mobile-gateway`](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway). With the gateway’s experimental WebRTC option enabled, paired clients prefer two native RTC data channels and explicitly fall back to WebSocket when negotiation fails. Settings shows the selected connection method. See [App integration and validation](Docs/webrtc-app-integration.md). Networking, secure storage, files, images, lifecycle handling, and background execution use native platform APIs. Business rules are consolidated in `shared/commonMain` wherever practical.

## Downloads and Prerequisites

- **iOS**: [Join the TestFlight beta](https://testflight.apple.com/join/1Euwby6r). Supports iPhone and iPad running iOS / iPadOS 16.1 or later.
- **Android**: [Download an APK from GitHub Releases](https://github.com/Clarklevis1995/dsh-mobile/releases). Requires Android 7.0 (API 24) or later. In-app update checks prefer the accelerated download source and verify downloaded packages.
- **Desktop host**: Run DeepSeek Harness with **`dsh-plugin-mobile-gateway` 0.11.1 or later** installed and enabled. [`AppRelease/release-info.json`](AppRelease/release-info.json) defines the required minimum version.
- **Voice input**: Enable the official Voice Input plugin in the desktop host's Plugins section, finish model download and initialization, and grant microphone access on the mobile device.

## Beta Community

Join the Whaleness beta WeChat group 2 to share feedback, discuss your experience, and get support. Scan with WeChat, or save the image and recognize it in WeChat.

<p align="center">
  <a href="website/assets/support-wechat-2026-10-28.png"><img src="website/assets/support-wechat-2026-10-28.png" alt="Whaleness beta WeChat group 2 QR code, valid before November 4, 2026" width="320"></a>
</p>

> Updated on October 28, 2026. The original image states that the code is valid before November 4, 2026. If it has expired, check the [product page](https://clarklevis1995.github.io/dsh-mobile/) for the latest group QR code.

## Platform Status

| Platform / module | Status | Main components |
| --- | --- | --- |
| KMP `shared` | Used by Android and iOS | Gateway protocol, stores/reducers, conversation and trace projections, history synchronization, questions/approvals, session control, workspace file state, and code preview support |
| Android | Native client available | Jetpack Compose, OkHttp WebSocket, DataStore for non-sensitive settings, Keystore for credentials, CameraX/ML Kit QR scanning, foreground service, attachment cache, file downloads, and previews |
| iOS | Native client available | SwiftUI/UIKit, URLSession WebSocket, UserDefaults for non-sensitive settings, Keychain for credentials, QR pairing, background execution, attachments, and workspace files |

> Android and iOS each have their own native UI and platform integrations. Features and interactions are being aligned, while some system-level behavior differs between platforms.

## Features

### Recent additions

- **Agent teams**: View members, switch between the lead and member conversations, and inspect shared tasks with status, ownership, and links to the relevant conversation.
- **File uploads**: Choose files in the conversation, follow upload progress, remove attachments, or retry a failed upload. Sent and generated files appear as cards that can be opened or downloaded.
- **Text selection and tables**: Open a dedicated text-selection screen to select, select all, and copy without losing your conversation position. Read Markdown tables full screen, rotate them, copy them, or export CSV; narrow tables fit the available width and wide tables scroll horizontally.
- **Chat history and storage**: Cache conversations locally and search common conversations offline. Review storage used by images, chat history, file previews, and temporary files, and clear local caches by category. Restore history when returning to a session and load earlier messages progressively.
- **iPhone Duo and wide screens**: Adapt home-screen columns, safe areas, the composer, and the animated background to folding, unfolding, and rotation. Wide conversations provide a persistent sidebar with sessions grouped by workspace; navigation and task controls adapt to the available space.
- **iOS 16.1 compatibility**: The deployment target is now iOS 16.1, with compatible interfaces for older systems. System-dependent effects and features remain subject to the iOS version and device capabilities.
- **Refined native interactions**: Improve Android frosted-glass menus, session renaming, and swipe navigation, plus iOS background reconnection, file browsing, and image compatibility.

### Conversations and Agent Execution

- **Live native conversations**: Stream WebSocket events with Markdown, code, reasoning, tool calls, tool results, and image attachments.
- **Voice and text input**: Switch to a dedicated hold-to-talk area, release to transcribe into an editable draft, or slide up to cancel. Transcription uses the voice input capability on the host.
- **History, queues, and commands**: Merge paginated history with the live tail and deduplicate events. View and edit queued messages, and use slash commands provided by the host. On iOS, loading older messages preserves the current reading position.
- **Agent traces**: Inspect User, Assistant, and Tool events, including arguments, results, schemas, token usage, and timing.
- **Interactive Agent workflows**: Respond to Human Questions and tool approval requests, including submission, cancellation, rejection, and one-time approval.

### Hosts, Workspaces, and Extensions

- **Multiple hosts and sessions**: Save and switch between Gateway hosts with separate credentials. Browse, create, and switch workspaces; search, create, switch, and archive sessions.
- **File cards and previews**: Open file cards directly from conversations, browse remote directories, and download files in verified chunks. Preview PDFs, images, and code, or share and open files with system apps.
- **Markdown source / preview**: Browse code with syntax highlighting, line numbers, and horizontal scrolling for long lines. Switch Markdown files between source and rendered views, retaining each view's reading position.
- **Plugin management**: Browse official and installed plugins, details, and runtime status. Install, enable, disable, and configure plugins where supported by the host.
- **Scheduled tasks**: Browse tasks created in conversations, search and filter them, expand their rules, next execution time, and delivery information, and edit or delete tasks.
- **Session and default settings**: Manage Agent presets, providers/models, reasoning effort, and Harness Agent permissions: `read-only`, `workspace-write`, and `danger-full-access`. These control the Agent on the host, not the phone's local filesystem.

### Native Device Experience

- **Tablets and foldables**: Layouts adapt to the available window size on iPad, Android tablets, and foldables. Wide home screens use columns, conversations retain a comfortable reading width, and file previews resize with the window. Android layouts account for the folding hinge area.
- **Themes and text size**: Light and dark appearances, plus system or manually adjusted app text size, with Harness's ocean, grid, and whale visual identity.
- **iOS Live Activities and notifications**: Follow task progress, approvals, and completion on the Lock Screen and supported Dynamic Island devices, with system notifications for important updates.
- **Secure device pairing**: Scan a WebUI QR code or enter pairing details manually. Long-lived credentials are stored in platform secure storage.
- **Mobile lifecycle handling**: Neither app intentionally closes a healthy connection when backgrounded. Connections recover on returning to the foreground after a disconnect; manual disconnects do not trigger automatic reconnection. Android runs a foreground service only during active Agent turns. iOS uses the limited background execution time granted by the system and does not guarantee an indefinitely open WebSocket.

## Screenshots

> Latest iOS product screenshots: the 1.7.10 image set was captured on October 22–23, 2026; the iPhone Duo set was captured on October 26, 2026. The interface shown is Chinese. [Product screenshot sources](Docs/images/screenshots/2026-10-23/SOURCES.md) · [Duo screenshot sources](Docs/images/screenshots/2026-10-26-duo/SOURCES.md).

### iPhone Duo: Landscape, Portrait, and Navigation

<table>
  <tr>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-home.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-home.webp" alt="Unfolded landscape home" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-home-alt.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-home-alt.webp" alt="Landscape home background" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Unfolded landscape home</strong></td>
    <td align="center"><strong>Landscape home background</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-settings.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-settings.webp" alt="Landscape settings" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-conversation.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-conversation.webp" alt="Landscape conversation" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Landscape settings</strong></td>
    <td align="center"><strong>Landscape conversation</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-navigation.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-navigation.webp" alt="Expanded navigation sidebar" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/portrait-home.webp"><img src="Docs/images/screenshots/2026-10-26-duo/portrait-home.webp" alt="Unfolded portrait home" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Expanded navigation sidebar</strong></td>
    <td align="center"><strong>Unfolded portrait home</strong></td>
  </tr>
</table>

### Teams, Attachments, and Local Storage

<table>
  <tr>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/team-members.webp"><img src="Docs/images/screenshots/2026-10-23/team-members.webp" alt="Agent team members" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/team-tasks.webp"><img src="Docs/images/screenshots/2026-10-23/team-tasks.webp" alt="Shared team tasks" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/attachment-picker.webp"><img src="Docs/images/screenshots/2026-10-23/attachment-picker.webp" alt="File and image picker" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Agent team members</strong></td>
    <td align="center"><strong>Shared team tasks</strong></td>
    <td align="center"><strong>File and image picker</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/text-selection.webp"><img src="Docs/images/screenshots/2026-10-23/text-selection.webp" alt="Dedicated text selection" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/conversation-files.webp"><img src="Docs/images/screenshots/2026-10-23/conversation-files.webp" alt="Files in a conversation" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/release-notes.webp"><img src="Docs/images/screenshots/2026-10-23/release-notes.webp" alt="Bundled release notes" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Dedicated text selection</strong></td>
    <td align="center"><strong>Files in a conversation</strong></td>
    <td align="center"><strong>Bundled release notes</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/storage.webp"><img src="Docs/images/screenshots/2026-10-23/storage.webp" alt="Local storage and cache categories" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/voice-input.webp"><img src="Docs/images/screenshots/2026-10-23/voice-input.webp" alt="Hold-to-talk input" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/file-upload.webp"><img src="Docs/images/screenshots/2026-10-23/file-upload.webp" alt="File attachment in the composer" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>Local storage and cache categories</strong></td>
    <td align="center"><strong>Hold-to-talk input</strong></td>
    <td align="center"><strong>File attachment in the composer</strong></td>
  </tr>
</table>

<details>
<summary>Earlier screenshots: iPad, previews, settings, and pairing</summary>

> Screenshots show the iOS app with Chinese UI; Android implements the corresponding information architecture and workflows with Jetpack Compose. The voice, file, text-size, and iPad screenshots were captured on October 16, 2026, using version 1.7.6. See [screenshot sources](Docs/images/screenshots/2026-10/SOURCES.md).

### Voice Input, File Previews, and Text Size

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/2026-10/voice-input.png" alt="Hold-to-talk voice recording overlay on iPhone" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/2026-10/pdf-preview.png" alt="PDF preview opened from a conversation file card" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/2026-10/font-size.png" alt="App text size preview and slider" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Hold to talk</strong><br>Release to transcribe, slide up to cancel</td>
    <td align="center"><strong>PDF preview</strong><br>Open files directly from a conversation</td>
    <td align="center"><strong>Text size</strong><br>Follow the system or adjust manually</td>
  </tr>
</table>

[View the slide-up-to-cancel state](Docs/images/screenshots/2026-10/voice-cancel.png)

### iPad and Larger Screens

<table>
  <tr>
    <td width="50%" align="center"><img src="Docs/images/screenshots/2026-10/ipad-home.png" alt="Two-column iPad home screen with workspace and recent sessions" width="100%"></td>
    <td width="50%" align="center"><img src="Docs/images/screenshots/2026-10/ipad-conversation.png" alt="Landscape iPad conversation with a file card" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Wide home screen</strong><br>Workspaces and sessions side by side</td>
    <td align="center"><strong>Tablet conversations</strong><br>Comfortable content width and composer</td>
  </tr>
  <tr>
    <td align="center"><img src="Docs/images/screenshots/2026-10/ipad-pdf-preview.png" alt="PDF preview window over an iPad conversation" width="100%"></td>
    <td align="center"><img src="Docs/images/screenshots/2026-10/ipad-voice-input.png" alt="Voice recording overlay adapted to a landscape iPad" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>File previews</strong><br>Keep the conversation in context</td>
    <td align="center"><strong>Voice input on iPad</strong><br>Recording feedback adapts to the window</td>
  </tr>
</table>

### Core Workflows

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/home.png" alt="iOS workspace home screen" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/2026-10/file-card.png" alt="iOS conversation with a file card and execution statistics control" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/trajectory-light.png" alt="iOS Agent trace in light mode" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Home</strong><br>Workspaces and recent sessions</td>
    <td align="center"><strong>Conversation</strong><br>Markdown, code, and tool results</td>
    <td align="center"><strong>Agent trace</strong><br>Timing overview and event timeline</td>
  </tr>
</table>

### Default Settings

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/settings.png" alt="iOS app settings" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/agent-presets.png" alt="iOS Agent presets" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/default-model.png" alt="iOS default model settings" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Settings</strong><br>Deployment defaults and Gateway status</td>
    <td align="center"><strong>Agent presets</strong><br>Tools, prompts, and capabilities</td>
    <td align="center"><strong>Default model</strong><br>Provider, model, and reasoning effort</td>
  </tr>
</table>

### Plugins and Scheduled Tasks

<table>
  <tr>
    <td width="50%" align="center"><img src="website/assets/plugins.webp" alt="iOS list of official and installed plugins" width="100%"></td>
    <td width="50%" align="center"><img src="website/assets/tasks.webp" alt="iOS scheduled task rules and details" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Plugin management</strong><br>Browse details and enabled state</td>
    <td align="center"><strong>Scheduled tasks</strong><br>Inspect schedules and execution status</td>
  </tr>
</table>

<details>
<summary>More screens: workspaces, trace details, and device pairing</summary>

### Workspace Actions and Trace Details

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/workspace-switcher.png" alt="iOS workspace switcher" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/workspace-directory-picker.png" alt="iOS workspace directory picker" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/trajectory-detail-light.png" alt="iOS trace details panel" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Switch workspaces</strong><br>Access different projects</td>
    <td align="center"><strong>Choose a directory</strong><br>Browse and create workspaces</td>
    <td align="center"><strong>Trace details</strong><br>Summary, tokens, and content preview</td>
  </tr>
</table>

### Light Mode, Dark Mode, and Pairing

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/pairing-light.png" alt="iOS device pairing in light mode" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/pairing-dark.png" alt="iOS device pairing in dark mode" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/conversation-dark.png" alt="iOS conversation in dark mode" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>Light-mode pairing</strong><br>Scan a QR code or enter details</td>
    <td align="center"><strong>Dark-mode pairing</strong><br>Native dark appearance</td>
    <td align="center"><strong>Dark-mode conversation</strong><br>Reading, code, and composer</td>
  </tr>
</table>

</details>

</details>

## Cross-Platform Architecture

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

### Shared Module Responsibilities

- `protocol/`: Gateway DTOs, JSON representations, and wire decoding.
- `gateway/`: Platform transport interfaces, pairing, authentication, request correlation, reconnection, and event backpressure. Android uses the full shared runtime, with OkHttp providing the underlying transport.
- `domain/`: Reducers for sessions, questions, approvals, and session control.
- `projection/`: Incremental conversation and trace projections.
- `sync/`: History pagination, live-tail merging, and deduplication.
- `facade/`: Coarse-grained stores, intents, and events for Swift/Kotlin adapters, plus workspace paths, chunk ordering, progress, and SHA-256 verification.

The shared module does not depend on Compose, SwiftUI, Android Framework, or Apple UI frameworks. iOS reuses shared stores, reducers, projections, and file state machines. Its Swift `GatewayClient` still owns WebSocket connections and reconnection, forwarding events into the shared module through an adapter; iOS does not yet use the full KMP Gateway Runtime.

Each platform handles secure storage, image processing, temporary files, system sharing/opening, and application lifecycle. The shared module owns the remote file protocol, state transitions, chunk consistency, and integrity checks without accessing platform filesystems directly.

## Repository Layout

```text
.
├── shared/                         # Kotlin Multiplatform business logic
│   └── src/
│       ├── commonMain/             # Protocol, state machines, projections, sync, facades
│       └── commonTest/             # Cross-platform unit tests
├── androidApp/                     # Native Android app (Jetpack Compose)
│   └── src/
│       ├── main/                   # App code and platform integrations
│       ├── test/                   # JVM tests
│       └── androidTest/            # Device tests
├── DeepSeekHarnessMobile/          # Native iOS app (SwiftUI/UIKit)
├── DeepSeekHarnessMobileTests/     # iOS XCTest tests
├── DeepSeekHarnessMobile.xcodeproj
├── Docs/                           # Research, KMP migration, and validation
└── Design/                         # Design specifications and artwork
```

## Development Requirements

### Common

- Java 17.
- DeepSeek Harness with `dsh-plugin-mobile-gateway` 0.11.1+ enabled.
- A simulator, emulator, or physical device that can reach Mobile Gateway.

### Android

- Android Studio or Android SDK Command-line Tools.
- Android SDK Platform 36 (`compileSdk` / `targetSdk`).
- An Android 7.0 (API 24) or later device or emulator.

### iOS

- macOS and Xcode 26+ with the iOS 26 SDK to compile APIs such as Liquid Glass. The app's deployment target is iOS 16.1.
- An iOS 16.1+ simulator or physical device.
- Android SDK: the Xcode build phase invokes Gradle to compile the KMP framework.
- An available Metal toolchain, which the build phase locates automatically.

## Run Android

1. Set up Java 17 and point `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your Android SDK.
2. Build a Debug APK from the repository root:

   ```bash
   ./gradlew :androidApp:assembleDebug
   ```

3. Install it on a connected device or emulator:

   ```bash
   ./gradlew :androidApp:installDebug
   ```

4. Launch `DshMobile` and scan the pairing QR code from WebUI, or enter pairing details manually.

The Debug APK is generated at:

```text
androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

To let an Android emulator access the host's `127.0.0.1:3080`, first run:

```bash
adb reverse tcp:3080 tcp:3080
```

## Run iOS

1. Set up Java 17 and the Android SDK.
2. Open `DeepSeekHarnessMobile.xcodeproj` in Xcode.
3. Select the `DeepSeekHarnessMobile` scheme and your target device.
4. Build and run. The `Build KMP Framework` phase automatically selects and compiles the matching Kotlin/Native framework.
5. Scan the WebUI pairing QR code in the app, or enter pairing details manually.

You do not need to build the framework manually first. To build only the Apple Silicon iOS Simulator framework:

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## Connection and Pairing

1. Install or upgrade `dsh-plugin-mobile-gateway` in DeepSeek Harness (minimum 0.11.1), then fully exit and restart `dsh web`:

   ```bash
   dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
   ```

2. Open the Mobile Devices panel in WebUI and enable mobile connections and device authentication.
3. Make sure the WebSocket endpoint is reachable from your mobile device or simulator.
4. Generate a one-time pairing QR code/token and authenticate in the Android or iOS app.
5. Return to the home screen, confirm that Gateway is connected, then choose a workspace and session.

Common endpoints:

| Environment | Endpoint |
| --- | --- |
| iOS Simulator | `ws://127.0.0.1:3080/ws/mobile` |
| Android Emulator with `adb reverse` | `ws://127.0.0.1:3080/ws/mobile` |
| Physical device on the same LAN | `ws://<HOST-LAN-IP>:3081/ws/mobile` |
| Public deployment | `wss://<your-domain>/ws/mobile` |

> [!IMPORTANT]
> A physical phone cannot reach your computer through `127.0.0.1`. The `ws://` examples are for trusted local development only. Use `wss://` with correctly configured TLS and device authentication on public or untrusted networks. Do not include one-time pairing tokens in logs, issues, or chat messages.

> [!NOTE]
> Physical devices on a LAN use the separate port `3081` listener provided by `dsh-plugin-mobile-gateway`. It serves only authenticated `/ws/mobile` requests; other paths return 404. Port `3080` belongs to DSH WebUI and listens on loopback only, so physical devices cannot connect through it.

## Build and Test

Run Android unit tests, lint, and the APK build:

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
```

Run the shared Kotlin/Native tests on Apple Silicon macOS:

```bash
./gradlew :shared:iosSimulatorArm64Test
```

With an Android device or emulator available, run device tests:

```bash
./gradlew :androidApp:connectedDebugAndroidTest
```

For iOS tests, select the `DeepSeekHarnessMobile` scheme in Xcode and choose **Product → Test**. The build phase links `DeepSeekHarnessShared.framework` for the selected architecture automatically.

## Related Documentation

Some linked technical documents are currently available in Chinese.

- [Harness / WebUI features and protocol research](Docs/exploration.md)
- [Mobile design specification](Design/design-spec.md)
- [Third-party notices](Docs/third-party-notices.md)

The following documents record the KMP migration and Android implementation. For the current state, refer to this README and the source code:

- [KMP development guide](Docs/kmp-development.md)
- [Android Gateway stage 12 validation](Docs/kmp-stage12-android-gateway-verification.md)
- [Android UI stage 13 validation](Docs/kmp-stage13-android-ui-verification.md)

## Project Status

Whaleness is under active development. The KMP shared module and native Android and iOS apps are implemented. Protocol support, platform consistency, and the mobile experience continue to evolve alongside DeepSeek Harness and Mobile Gateway.

This is an independently developed third-party client for DeepSeek Harness.

[![Listed on dsh-plugin.org](https://dsh-plugin.org/badges/listed.svg)](https://dsh-plugin.org/plugins/clarklevis1995/dsh-plugin-mobile-gateway)

### Bundled Release Notes

Both apps' About screens use `AppRelease/release-info.json` and `AppRelease/release-notes.md`. The JSON `version` must match the app version; `minimumGatewayVersion` specifies the required minimum Mobile Gateway plugin version. Markdown contains the release notes for that version. Apps load these bundled resources locally without fetching the text over the network.

The KMM dual-release skill writes reviewed release notes and synchronizes the resource version before compilation. It verifies that the resources in the APK, AAB, and iOS bundle match the source files, and restores resources and version files if packaging fails. Changes to release notes require rebuilding the apps; editing only the release output directory does not update bundled content.

### iOS Metal Compilation from Android Studio

The `Build Metal Library` phase runs `scripts/build_metal_library.sh`, locates an installed Metal toolchain, and writes `default.metallib` into the app bundle. It clears Android Studio's injected `TOOLCHAINS` and `SDKROOT` only in its subprocess, while explicitly preserving the current iOS SDK and deployment target. This avoids false missing-Metal-toolchain errors caused by forcing `XcodeDefault`.

### Android Update Sources

Android checks `https://download.whaleness.cc/latest.json` first and falls back to GitHub Releases if the request fails or the configuration is invalid. The `schemaVersion: 1` configuration includes `version`, `versionCode`, `packageName`, `downloadUrl`, `size`, and `sha256`, with optional `githubDownloadUrl` and `releaseNotes`. Downloads accept only configured trusted accelerated-download domains and this project's GitHub URLs. If the accelerated source fails, the app switches to the fallback while verifying package size, SHA-256, version, package name, and signature against the same configuration.

Publishing uploads an immutable versioned APK before updating the root `latest.json`. Update manifests should bypass CDN caching; APK files may be cached. When no newer build is found, no GitHub request is made. Startup checks remain silent, while manual checks in Settings display their result.

### Automatic R2 Publishing

Run `python3 scripts/configure_r2_release.py` to save S3 object read/write credentials for the configured bucket in `~/.config/whaleness/r2-release.json` with permissions `600`. `sh scripts/r2_release.sh --check` only verifies connectivity. `sh scripts/r2_release.sh --prepare <release-directory>` generates a manifest; `--publish <release-directory>` performs the remote publication. The S3 SDK is installed in the isolated `build/r2-tools` environment.

When local configuration exists, the KMM dual-release skill publishes automatically: validate local packages, upload an immutable APK whose filename includes the build number, verify both the remote object and public download, then conditionally update `latest.json`. A failed upload can be retried with the same release directory without rebuilding. Set `R2_UPLOAD=0` for local packaging only. Full upload tests may generate R2 read requests; the initial connection check does not modify remote objects.
