# 做了个 DeepSeek Harness 原生移动客户端 Whaleness，支持 Android 和 iOS

佬友们好，分享一下自己做的项目：**Whaleness**，一个 DeepSeek Harness 的第三方原生移动客户端。

它想解决的需求很直接：电脑上的 Agent 还在干活，人已经离开桌面，也能拿手机看看进度、补一句要求，遇到提问或审批时继续处理。任务仍然在电脑上的 Harness 执行，手机通过 Mobile Gateway 连回去，接着用已有的工作区和会话。

现在 Android 和 iOS 都能用，iPad、Android 平板和折叠屏也做了布局适配。先放两张图看看。

![工作区与最近会话](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/website/assets/home.webp)

![原生对话与任务执行](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/website/assets/conversation.webp)


> 本帖主要使用 iOS 实际界面截图，部分来自模拟器，含不同版本的界面；Android 使用 Jetpack Compose 原生实现，具体样式和系统能力会有所区别。

## 在手机上继续对话和查看执行过程

打开工作区就能继续原来的会话，实时查看回复、代码、工具调用和工具结果，也能在会话里切换模型、思考等级和 Agent 权限。需要你回答问题或确认工具执行时，可以直接在手机上处理。

有时候只看最终回复还不够，就切到轨迹页。这里可以展开工具实际收到的参数、返回结果，以及 Token 用量和耗时，排查一次任务为什么卡住会方便一些。

![Agent 执行轨迹](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/website/assets/trajectory.webp)

![工具调用参数与结果详情](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/trajectory-detail-light.png)


## 智能体团队也能跟着看

主机启用了智能体团队后，手机可以查看成员，在 Lead 和子 Agent 的会话之间切换。共享任务看板里能看到任务状态、归属和关联会话，方便了解各个 Agent 的进度。

![切换 Lead 与子 Agent](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10-23/team-members.webp)

![团队共享任务看板](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10-23/team-tasks.webp)


## 文件可以发过去，结果也可以带走

可以从手机选择文件或图片作为附件发送，查看上传进度，失败后重试。Agent 生成的文件会以卡片显示，点开就能继续查看或下载。

文件预览支持 PDF、图片和代码，Markdown 文件可以切换源码与渲染视图。电脑上生成报告后，拿手机打开 PDF 看一遍，再交给系统分享，会比较顺手。

![从手机添加文件附件](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10-23/file-upload.webp)

![会话中的生成文件](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10-23/conversation-files.webp)

![直接预览 PDF](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10/pdf-preview.png)


## 语音输入和长内容阅读

不方便打字时可以按住说话，松手后转成文字草稿，确认一下再发送，上滑可以取消。转写使用电脑上的语音输入能力，需要先启用官方语音输入插件，并完成模型下载与初始化。

长回复可以进入独立页面选择和复制文字；表格支持全屏查看、旋转和导出 CSV。翻阅历史时也能继续接收会话的新内容。

![按住说话输入](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10-23/voice-input.webp)

![独立文字选择页面](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10-23/text-selection.webp)


## 插件和定时任务也有入口

手机上可以查看主机插件的详情与运行状态，按主机支持的能力安装、启停和配置插件。会话创建的定时任务也能集中查看，包括规则、下次执行时间和投递记录，并支持编辑、删除。

![主机插件管理](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/website/assets/plugins.webp)

![定时任务与执行详情](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/website/assets/tasks.webp)


Agent 预设、默认模型和权限配置也放进来了。另外做了本地缓存分类管理，可以查看聊天记录、图片、文件预览等各占多少空间，按需清理。

![Agent 预设](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/website/assets/presets.webp)

![本地缓存分类管理](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10-23/storage.webp)


## 手机之外，也适配了大屏

宽屏下，首页会把工作区和最近会话分栏展示；对话和文件预览也会根据窗口尺寸调整。Android 的折叠屏布局会考虑铰链占用区域。

下面依次是 iPad 首页，以及 iPhone Duo 模拟器中的宽屏首页和对话布局。

![iPad 工作区与会话分栏](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10/ipad-home.png)

![宽屏首页布局](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10-26-duo/landscape-home.webp)

![宽屏对话布局](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/Docs/images/screenshots/2026-10-26-duo/landscape-conversation.webp)


## 怎么连接

需要一台运行 DeepSeek Harness 的电脑，以及 `dsh-plugin-mobile-gateway` **0.11.1 或更高版本**。手机要求 Android 7.0+，或 iOS / iPadOS 16.1+。

1. 在电脑上安装网关插件，安装或升级后完整退出并重新启动 `dsh web`。
2. 在桌面 WebUI 的“移动设备”中启用网关，打开“允许移动设备连接”。
3. 选择手机能够访问的连接方式，生成配对二维码。
4. 在 Whaleness 中扫描自己电脑生成的二维码，连接后选择工作区即可。

```bash
dsh plugin --profile web add dsh-plugin-mobile-gateway@latest
```

![桌面端移动设备网关设置](https://raw.githubusercontent.com/Clarklevis1995/dsh-mobile/497947559a29eb6108efa34b6aced30822beafdb/website/assets/setup/gateway.webp)


同一 Wi-Fi 下可以选局域网直连。需要在外网使用时，可以按网关说明配置 Cloudflare Tunnel 等连接方式；Quick Tunnel 地址变化后，需要重新生成二维码配对。产品页里有配图连接教程。

iOS 还接入了锁屏实时活动和灵动岛，能显示任务进展、审批和完成状态，具体受系统版本和设备能力限制。后台连接同样受系统调度影响，回到前台会自动恢复；电脑上的 Harness 和网关需要保持运行。

## 下载和源码

- [iOS 和 iPadOS：加入 TestFlight 公测](https://testflight.apple.com/join/1Euwby6r)
- [Android：从 GitHub Releases 下载 APK](https://github.com/Clarklevis1995/dsh-mobile/releases)
- [产品介绍和图文连接教程](https://clarklevis1995.github.io/dsh-mobile/)
- [移动端源码](https://github.com/Clarklevis1995/dsh-mobile)
- [Mobile Gateway 插件源码与说明](https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway)

技术上用 Kotlin Multiplatform 共享协议、状态和同步逻辑，Android 界面用 Jetpack Compose，iOS 界面用 SwiftUI/UIKit。连接凭据分别保存在 Android Keystore 和 iOS Keychain。

Whaleness 是独立开发的第三方客户端。如果你也在用 Harness，欢迎试试，尤其想听听大家在长会话、后台恢复和不同尺寸设备上的使用反馈。遇到问题可以在 [GitHub Issues](https://github.com/Clarklevis1995/dsh-mobile/issues) 留下系统版本、App 版本和复现步骤。
