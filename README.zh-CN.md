<div align="center">

[English](README.md) · **简体中文** · [日本語](Docs/readme/README.ja.md) · [한국어](Docs/readme/README.ko.md) · [Español](Docs/readme/README.es.md) · [Français](Docs/readme/README.fr.md) · [Deutsch](Docs/readme/README.de.md) · [Português (Brasil)](Docs/readme/README.pt-BR.md)

<img src="Design/whale-girl-ios-app-promo-16x9-v3.png" alt="Whaleness iOS 应用展示" width="100%">

# Whaleness（DeepSeek Harness 的第三方移动端应用）

**使用 Kotlin Multiplatform 共享核心逻辑，为 Android 与 iOS 提供原生移动端体验。**

通过 Mobile Gateway 在手机、平板和折叠屏设备上访问 DeepSeek Harness 的工作区、实时对话、Agent 轨迹、插件、定时任务与文件。

官方网站：[Whaleness](https://clarklevis1995.github.io/dsh-mobile/)

<p>
  <a href="https://testflight.apple.com/join/1Euwby6r"><img src="Docs/images/badges/testflight.svg" alt="加入 iOS TestFlight 公测" width="180" height="60"></a>
  &nbsp;&nbsp;
  <a href="https://github.com/Clarklevis1995/dsh-mobile/releases"><img src="Docs/images/badges/android-github.svg" alt="从 GitHub Releases 下载 Android APK" width="180" height="60"></a>
</p>

![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-7F52FF?logo=kotlin&logoColor=white)
![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![iOS 16.1+](https://img.shields.io/badge/iOS-16.1%2B-111827?logo=apple&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4?logo=jetpackcompose&logoColor=white)
![SwiftUI](https://img.shields.io/badge/iOS-SwiftUI-F05138?logo=swift&logoColor=white)
![WebSocket](https://img.shields.io/badge/WebSocket-Realtime-2563EB)

</div>

## 项目简介

Whaleness 是独立开发的 DeepSeek Harness 第三方原生移动客户端。项目已完成 Kotlin Multiplatform（KMP）架构改造，并同时提供可运行的 Android 与 iOS 应用：

- `shared` 负责跨端协议、状态机、Reducer、投影和同步逻辑，是共享业务状态的唯一来源。
- `androidApp` 使用 Kotlin、Jetpack Compose 和 OkHttp 实现 Android 产品能力。
- `DeepSeekHarnessMobile` 使用 SwiftUI/UIKit 构建 iOS 界面，并通过 Kotlin/Native framework 接入共享逻辑。

两端均通过 [`dsh-plugin-mobile-gateway`](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway) 与 Harness 建立 WebSocket 连接。网络、安全存储、文件、图片、生命周期和后台任务由各平台原生实现，业务规则则尽可能收敛到 `shared/commonMain`。

## 下载与前置条件

- **iOS**：[加入 TestFlight 公测](https://testflight.apple.com/join/1Euwby6r)，支持 iPhone 与 iPad，要求 iOS / iPadOS 16.1 或更高版本。
- **Android**：[从 GitHub Releases 下载 APK](https://github.com/Clarklevis1995/dsh-mobile/releases)，要求 Android 7.0（API 24）或更高版本。应用内检查更新优先使用加速下载源，并校验安装包。
- **桌面端**：需要运行 DeepSeek Harness，并安装、启用 **`dsh-plugin-mobile-gateway` 0.11.1 或更高版本**。最低版本以 [`AppRelease/release-info.json`](AppRelease/release-info.json) 为准。
- **语音输入**：另需在桌面端的“插件”模块启用官方“语音输入”插件，完成模型下载和初始化，并授予移动端麦克风权限。

## 公测用户群

欢迎加入 Whaleness 公测用户 2 群，交流使用体验、反馈问题并获取支持。使用微信扫码，或保存图片后在微信中识别。

<p align="center">
  <a href="website/assets/support-wechat-2026-10-28.png"><img src="website/assets/support-wechat-2026-10-28.png" alt="Whaleness 公测用户 2 群微信二维码，2026 年 11 月 4 日前有效" width="320"></a>
</p>

> 当前二维码于 2026 年 10 月 28 日更新，原图注明 2026 年 11 月 4 日前有效。若已过期，请查看[产品页面](https://clarklevis1995.github.io/dsh-mobile/)中的最新群码。

## 当前平台状态

| 平台/模块 | 当前状态 | 主要实现 |
| --- | --- | --- |
| KMP `shared` | 已接入 Android 与 iOS | Gateway 协议、Store/Reducer、Conversation/Trajectory 投影、History 同步、Question/Approval、Session Control、工作区文件状态与代码预览支持 |
| Android | 已实现原生客户端 | Jetpack Compose、OkHttp WebSocket、DataStore（非敏感配置）、Keystore（凭据）、CameraX/ML Kit 扫码、前台服务、附件缓存、文件下载与预览 |
| iOS | 已实现原生客户端 | SwiftUI/UIKit、URLSession WebSocket、UserDefaults（非敏感配置）、Keychain（凭据）、二维码配对、后台执行、附件与工作区文件 |

> Android 与 iOS 使用各自的原生 UI 和平台能力，并不是共享 UI。功能和交互会持续对齐，但个别系统级行为可能因平台限制而不同。

## 功能亮点

### 近期新增与改进

- **智能体团队**：查看团队成员，在 Lead 与子 Agent 会话之间切换；通过共享任务看板查看任务状态、归属与关联会话。
- **文件上传**：在会话中选择文件，查看上传进度、移除附件或重试失败上传；已发送文件和生成文件以卡片展示，可继续打开或下载。
- **文字选择与表格阅读**：通过独立页面选择、全选和复制正文，关闭后保留会话阅读位置。Markdown 表格支持全屏查看、旋转、复制和导出 CSV；少列表格适应可用宽度，宽表格可横向滚动。
- **聊天记录与存储管理**：本地缓存会话，常用会话可离线搜索；分类查看图片、聊天记录、文件预览和临时文件的占用，并按类别清理本机缓存。重新进入会话时恢复历史，并渐进加载更早记录。
- **iPhone Duo 与宽屏适配**：随展开、闭合和旋转调整首页分栏、安全区、输入框和动态背景。宽屏会话提供按工作区分组的常驻会话侧栏，导航与定时任务控件根据可用空间调整。
- **兼容 iOS 16.1**：最低部署版本扩展至 iOS 16.1，为较旧系统提供兼容界面；依赖系统的效果与功能仍受 iOS 版本和设备能力限制。
- **原生交互优化**：改进 Android 毛玻璃菜单、会话重命名与侧滑导航，以及 iOS 后台恢复连接、文件浏览和图片格式兼容性。

### 对话与 Agent 执行

- **原生实时对话**：处理 WebSocket 增量事件，展示 Markdown、代码、思考过程、工具调用、工具结果与图片附件。
- **语音与文字输入**：切换到独立的“按住说话”区域，松手转写为可编辑草稿，上滑取消；语音转写通过主机上的语音输入能力完成。
- **历史、队列与命令**：分页加载历史记录时合并实时尾部并去重；查看和编辑排队消息，通过斜杠命令调用主机提供的能力。iOS 加载更早记录时保留当前阅读位置。
- **完整 Agent 轨迹**：查看 User、Assistant、Tool 等事件，以及调用参数、结果、Schema、Token 和耗时信息。
- **交互式 Agent 流程**：支持 Human Question 与工具审批请求，可提交、取消、拒绝或单次允许。

### 主机、工作区与扩展

- **多主机与会话管理**：保存并切换多个 Gateway 主机，分别管理认证信息；浏览、创建和切换工作区，搜索、创建、切换及归档会话。
- **文件卡片与预览**：直接打开对话中的文件卡片，浏览远端目录，分块下载并校验完整性；预览 PDF、图片和代码，或交给系统分享与打开。
- **Markdown 源码 / 预览**：代码预览支持语法高亮、行号和长行横向滚动；Markdown 文件可切换源码与渲染视图，并保留各自的阅读位置。
- **插件管理**：查看官方及已安装插件、详情和运行状态，按主机支持的能力安装、启用、停用和配置插件。
- **定时任务**：查看会话创建的定时任务，搜索和筛选任务，展开查看规则、下次执行时间及投递记录，并编辑或删除任务。
- **会话和默认配置**：管理 Agent 预设、Provider/模型、思考等级与 `read-only`、`workspace-write`、`danger-full-access` Harness Agent 权限；这些不是手机本地文件系统权限。

### 原生设备体验

- **大屏与折叠屏适配**：iPad、Android 平板和折叠屏根据可用窗口尺寸调整布局；宽屏首页使用分栏，会话保持合适的阅读宽度，文件预览随窗口伸缩。Android 布局会考虑折叠铰链占用的区域。
- **主题与字体大小**：浅色、深色主题，以及跟随系统或手动调整的应用字体大小；保留 Harness 的深海、网格与鲸鱼视觉语言。
- **iOS 实时活动与通知**：在锁屏和支持的灵动岛设备上查看任务进展、审批与完成状态，重要进展可通过系统通知提醒。
- **安全设备配对**：扫描 WebUI 二维码或手动输入配对信息；长期凭据保存到平台安全存储。
- **移动端生命周期**：两端进入后台均不主动关闭健康连接，断线后回到前台自动恢复；手动断开不自动重连。Android 仅在 Agent 回合执行期间使用前台服务，空闲连接不启动前台服务；iOS 使用系统授予的有限后台执行时间管理活动任务，不保证无限期维持 WebSocket。

## 界面预览

> 最新 iOS 产品截图：1.7.10 素材批次拍摄于 2026 年 10 月 22–23 日，iPhone Duo 截图拍摄于 2026 年 10 月 26 日，均展示中文界面。[产品截图来源](Docs/images/screenshots/2026-10-23/SOURCES.md) · [Duo 截图来源](Docs/images/screenshots/2026-10-26-duo/SOURCES.md)。

### iPhone Duo：横屏、竖屏与导航适配

<table>
  <tr>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-home.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-home.webp" alt="展开横屏首页" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-home-alt.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-home-alt.webp" alt="横屏首页背景" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>展开横屏首页</strong></td>
    <td align="center"><strong>横屏首页背景</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-settings.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-settings.webp" alt="横屏设置" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-conversation.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-conversation.webp" alt="横屏会话" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>横屏设置</strong></td>
    <td align="center"><strong>横屏会话</strong></td>
  </tr>
  <tr>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/landscape-navigation.webp"><img src="Docs/images/screenshots/2026-10-26-duo/landscape-navigation.webp" alt="展开导航侧栏" width="100%"></a></td>
    <td width="50.00%" align="center"><a href="Docs/images/screenshots/2026-10-26-duo/portrait-home.webp"><img src="Docs/images/screenshots/2026-10-26-duo/portrait-home.webp" alt="展开竖屏首页" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>展开导航侧栏</strong></td>
    <td align="center"><strong>展开竖屏首页</strong></td>
  </tr>
</table>

### 团队协作、文件附件与本地存储

<table>
  <tr>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/team-members.webp"><img src="Docs/images/screenshots/2026-10-23/team-members.webp" alt="智能体团队成员" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/team-tasks.webp"><img src="Docs/images/screenshots/2026-10-23/team-tasks.webp" alt="团队共享任务" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/attachment-picker.webp"><img src="Docs/images/screenshots/2026-10-23/attachment-picker.webp" alt="文件与图片选择" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>智能体团队成员</strong></td>
    <td align="center"><strong>团队共享任务</strong></td>
    <td align="center"><strong>文件与图片选择</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/text-selection.webp"><img src="Docs/images/screenshots/2026-10-23/text-selection.webp" alt="独立文字选择页面" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/conversation-files.webp"><img src="Docs/images/screenshots/2026-10-23/conversation-files.webp" alt="会话中的文件卡片" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/release-notes.webp"><img src="Docs/images/screenshots/2026-10-23/release-notes.webp" alt="应用内版本更新说明" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>独立文字选择页面</strong></td>
    <td align="center"><strong>会话中的文件卡片</strong></td>
    <td align="center"><strong>应用内版本更新说明</strong></td>
  </tr>
  <tr>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/storage.webp"><img src="Docs/images/screenshots/2026-10-23/storage.webp" alt="本地存储与分类缓存" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/voice-input.webp"><img src="Docs/images/screenshots/2026-10-23/voice-input.webp" alt="按住说话输入" width="100%"></a></td>
    <td width="33.33%" align="center"><a href="Docs/images/screenshots/2026-10-23/file-upload.webp"><img src="Docs/images/screenshots/2026-10-23/file-upload.webp" alt="输入框中的文件附件" width="100%"></a></td>
  </tr>
  <tr>
    <td align="center"><strong>本地存储与分类缓存</strong></td>
    <td align="center"><strong>按住说话输入</strong></td>
    <td align="center"><strong>输入框中的文件附件</strong></td>
  </tr>
</table>

<details>
<summary>更多历史截图：iPad、文件预览、设置与设备配对</summary>

> 截图来自 iOS 版本；Android 使用 Jetpack Compose 实现相应的信息架构和核心流程。本组语音、文件、字体及 iPad 截图拍摄于 2026 年 10 月 16 日（1.7.6），素材来源见[截图说明](Docs/images/screenshots/2026-10/SOURCES.md)。

### 语音输入、文件预览与字体

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/2026-10/voice-input.png" alt="iPhone 按住说话时的语音录入浮层" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/2026-10/pdf-preview.png" alt="从对话文件卡片打开 PDF 预览" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/2026-10/font-size.png" alt="应用字体大小预览与滑块设置" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>按住说话</strong><br>松手转写，上滑取消</td>
    <td align="center"><strong>PDF 预览</strong><br>从对话直接查看文件</td>
    <td align="center"><strong>字体大小</strong><br>跟随系统或手动调整</td>
  </tr>
</table>

[查看上滑取消状态](Docs/images/screenshots/2026-10/voice-cancel.png)

### iPad 与大屏

<table>
  <tr>
    <td width="50%" align="center"><img src="Docs/images/screenshots/2026-10/ipad-home.png" alt="iPad 首页的工作区与最近会话双栏布局" width="100%"></td>
    <td width="50%" align="center"><img src="Docs/images/screenshots/2026-10/ipad-conversation.png" alt="iPad 横屏对话和文件卡片" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>宽屏首页</strong><br>工作区与会话分栏展示</td>
    <td align="center"><strong>大屏对话</strong><br>舒适的内容宽度与输入区域</td>
  </tr>
  <tr>
    <td align="center"><img src="Docs/images/screenshots/2026-10/ipad-pdf-preview.png" alt="iPad 对话中的 PDF 预览窗口" width="100%"></td>
    <td align="center"><img src="Docs/images/screenshots/2026-10/ipad-voice-input.png" alt="适配 iPad 横屏的语音输入浮层" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>文件预览</strong><br>保留对话上下文</td>
    <td align="center"><strong>大屏语音输入</strong><br>录音反馈随窗口尺寸适配</td>
  </tr>
</table>

### 核心体验

<table>
  <tr>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/home.png" alt="iOS 工作区首页" width="100%"></td>
    <td width="33.33%" align="center"><img src="Docs/images/screenshots/2026-10/file-card.png" alt="iOS 对话中的文件卡片与执行统计入口" width="100%"></td>
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

### 插件与定时任务

<table>
  <tr>
    <td width="50%" align="center"><img src="website/assets/plugins.webp" alt="iOS 官方与已安装插件列表" width="100%"></td>
    <td width="50%" align="center"><img src="website/assets/tasks.webp" alt="iOS 定时任务规则与详情" width="100%"></td>
  </tr>
  <tr>
    <td align="center"><strong>插件管理</strong><br>查看详情与启用状态</td>
    <td align="center"><strong>定时任务</strong><br>查看计划与执行情况</td>
  </tr>
</table>

<details>
<summary>更多界面：工作区、轨迹详情与设备配对</summary>

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

</details>

</details>

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
- 已启用 `dsh-plugin-mobile-gateway` 0.11.1+ 的 DeepSeek Harness
- 能够访问 Mobile Gateway 的模拟器或真机

### Android

- Android Studio 或 Android SDK Command-line Tools
- Android SDK Platform 36（`compileSdk` / `targetSdk`）
- Android 7.0（API 24）及以上设备或模拟器

### iOS

- macOS 与 Xcode 26+（包含 iOS 26 SDK，以编译 Liquid Glass 等系统 API；应用部署目标为 iOS 16.1）
- iOS 16.1+ 模拟器或真机
- Android SDK（Xcode 构建阶段会通过 Gradle 编译 KMP framework）
- 可用的 Metal 工具链（构建阶段会自动查找）

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

1. 在 DeepSeek Harness 中安装或升级 `dsh-plugin-mobile-gateway`（最低 0.11.1），完整退出并重新启动 `dsh web`：

   ```bash
   dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
   ```

2. 打开 WebUI 的“移动设备”面板，启用移动设备连接与设备鉴权。
3. 确认 WebSocket 地址能被手机或模拟器访问。
4. 生成一次性配对二维码/Token，并在 Android 或 iOS 客户端中完成认证。
5. 返回首页，确认 Gateway 状态已连接，然后选择工作区和会话。

常见地址：

| 场景 | 地址 |
| --- | --- |
| iOS Simulator | `ws://127.0.0.1:3080/ws/mobile` |
| Android Emulator + `adb reverse` | `ws://127.0.0.1:3080/ws/mobile` |
| 同一局域网内的真机 | `ws://<HOST-LAN-IP>:3081/ws/mobile` |
| 公网部署 | `wss://<your-domain>/ws/mobile` |

> [!IMPORTANT]
> 真机不能使用 `127.0.0.1` 访问电脑。表中的 `ws://` 仅用于受信任的本地开发环境；公网或不可信网络必须使用 `wss://` 并正确配置 TLS 与设备鉴权。一次性配对 Token 不应写入日志、Issue 或聊天记录。

> [!NOTE]
> 局域网真机走的是 `dsh-plugin-mobile-gateway` 独立监听的 `3081` 端口，该端口只提供经过鉴权的 `/ws/mobile`，其余路径返回 404。`3080` 是 DSH WebUI 自身的端口且仅监听回环，真机无法通过它连接。

## 构建与测试

执行 Android 单元测试、Lint 和 APK 构建：

```bash
./gradlew \
  :androidApp:testDebugUnitTest \
  :androidApp:lintDebug \
  :androidApp:assembleDebug
```

在 Apple Silicon macOS 上执行共享层 Kotlin/Native 测试：

```bash
./gradlew :shared:iosSimulatorArm64Test
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

本项目是独立开发的 DeepSeek Harness 第三方客户端。

[![Listed on dsh-plugin.org](https://dsh-plugin.org/badges/listed.svg)](https://dsh-plugin.org/plugins/clarklevis1995/dsh-plugin-mobile-gateway)

### 应用内发布说明资源

Android 和 iOS 的“关于”页共用 `AppRelease/release-info.json` 与 `AppRelease/release-notes.md`。JSON 中的 `version` 必须与 App 版本一致，`minimumGatewayVersion` 表示必需的移动网关插件最低版本。Markdown 保存本版本发布说明；应用启动时从本地打包资源读取，不依赖网络获取正文。

KMM 双端打包技能会在编译前写入经过整理的最终发布说明并同步资源版本，校验 APK、AAB 和 iOS Bundle 内资源与源文件完全一致；打包失败会恢复资源和版本文件。更新说明后需要重新构建应用，不能只修改打包输出目录中的说明文件。

### Android Studio 的 iOS Metal 编译

项目通过 `Build Metal Library` 阶段执行 `scripts/build_metal_library.sh`，自动查找已安装的 Metal 工具链，并把 `default.metallib` 写入应用 Bundle。该阶段只在子进程中清除 Android Studio 注入的 `TOOLCHAINS` 与 `SDKROOT`，编译时仍明确使用当前 iOS SDK 和部署目标，避免强制 `XcodeDefault` 导致误报缺少 Metal Toolchain。

### Android 更新下载源

Android 优先读取 `https://download.whaleness.cc/latest.json`，请求失败或配置无效时回退 GitHub Releases。配置格式为 `schemaVersion: 1`，包含 `version`、`versionCode`、`packageName`、`downloadUrl`、`size`、`sha256`，可包含 `githubDownloadUrl` 与 `releaseNotes`。下载仅接受配置中的可信加速域名和本项目 GitHub 地址；加速源失败时切换备用地址，仍按同一份配置校验安装包大小、SHA-256、版本、包名和签名。

发布时先上传不可覆盖的版本 APK，再更新根目录的 `latest.json`。更新配置应绕过 CDN 缓存；APK 可缓存。未发现新构建时不请求 GitHub，启动检查保持静默，设置页手动检查显示结果。

### R2 自动发布

先运行 `python3 scripts/configure_r2_release.py`，将指定桶的 S3 对象读写凭据保存在 `~/.config/whaleness/r2-release.json`（权限 600）。执行 `sh scripts/r2_release.sh --check` 仅验证连接。执行 `sh scripts/r2_release.sh --prepare <发布目录>` 生成清单；`--publish <发布目录>` 才进行远程发布。S3 SDK 隔离安装在 `build/r2-tools`。

KMM 双端打包技能在本机存在配置时自动发布：先校验本地包，再上传带构建号的不可变 APK，验证远程对象及公开下载内容，最后条件更新 `latest.json`。上传失败可用同一发布目录重试，不需要重新打包。只需本地打包时设 `R2_UPLOAD=0`。完整上传测试可能产生 R2 读取请求；首次连接验证不会修改远程对象。
