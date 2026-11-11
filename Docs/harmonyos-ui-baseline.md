# 鸿蒙产品基准冻结记录

日期：2026-10-30。主基准为当前 Android 产品源码（747939e8 加工作树现状），iOS 已归档实际截图补充品牌与宽屏层级。来源文件 SHA-256 见 [sources.json](../harmony/testing/baseline/sources.json)，设计 token 与资源来源见 [design-tokens.json](../harmony/testing/baseline/design-tokens.json)。

## 已冻结的设计参数

色值逐项取自 `DshTheme.kt`：Navy #06172B、Ocean #2E6BE6、Ink #0E131A、Paper #F9FAFC、Success #2EB85C。首页内容水平留白 20dp，标题 32sp，说明 14sp，主要圆形触摸区至少 48dp；会话工具栏 54dp，设置标题 17sp。宽屏从 900dp、窗口高度至少 500dp 且字号缩放小于 1.5 倍时使用双列。鸿蒙按 vp/fp 映射，系统原生安全区单独计算。

首页采用真实鲸鱼 SVG 路径、网格与低对比粒子、局部玻璃表面。长文阅读保留高可读表面，不将全部正文透明化。字体随系统缩放，主题有系统、浅色、深色；语言基准为系统、简体中文和英语。

## 资源与许可

鲸鱼和品牌字样直接引用本仓库 iOS Asset Catalog 的原始 SVG，图标来自本仓库 Android drawable 路径，遵守根目录 MIT 及原文件附带许可。Montserrat 如被使用须保留 `Design/whaleness-wordmark/Montserrat-OFL.txt`；本轮不引入 iOS Liquid Glass 或 Android Backdrop 二进制。不得用宣传设备渲染图冒充运行截图。

## 交互边界

- 主机栏始终显示当前主机，列表编辑／移除有确认；不同主机状态隔离。
- 主页面包括工作区、会话和设置；插件、计划任务、轨迹等完整接入仍按后续阶段处理。
- 当前 Android `SessionContextMenu.kt` 只有重命名及删除（归档）；当前真实网关 0.11.0 的 `/ws/mobile` 没有归档恢复接口。规划中的“恢复”暂列协议缺口，不能用本地隐藏列表操作伪装成功。
- 当前 Android 有部分历史设备测试的 UI 文案假设与最新源码不一致，截图冻结以本次构建实际界面为准。

## 视觉截图进度

使用独立 Android 包 `com.clarklevis.dsh.android.harmonybaselinea9f5` 与测试数据采集，避免归档用户现有会话。正常、加载、空、失败等各页状态仍在采集；P0-04 只有矩阵所需证据齐全后才能勾选。现有 iOS 补充截图来源详见 `Docs/images/screenshots/*/SOURCES.md`。

### 本轮新增深色对照

隔离 Android 参考包实际切换深色，补充 `settings-dark.png` 与 `appearance-menu-light.png`。`DshTheme.kt` 的通用背景（浅 `#F9FAFC`／深 `#090D13`）、深色卡片 `#111720`、深色主色 `#7EA8FF`，以及 `SettingsScreen.kt` 的设置专用背景（浅 `#F1F1F6`／深 `#0D1118`）分别映射，避免混成同一种蓝色。鸿蒙外观／语言已改为原生锚定菜单并完成冷启动复测；字号预览与抽屉已经补齐基础实现，全状态矩阵仍需继续核对，不能因此勾选完整页面复刻。

本轮组件明暗／字号与仍有差异的项目见 [P2 视觉核对](../harmony/testing/reports/2026-10-30-P2/visual-review.md)。

采集纠正：Android CLI 必须使用 `--device=emulator-5556`。旧参数不能可靠锁定设备，本次已将首页／浅深设置／外观菜单全部替换为唯一新包首次安装、未配对状态下的截图，并新增字号页。范围记录见 [capture-scope.json](../harmony/testing/baseline/android/capture-scope.json)。

### 2026-10-31 独立参考包复核（保留早期冻结记录）

新增独立包 `com.clarklevis.dsh.android.harmonyp7v`，原有参考包及旧证据未替换。实际 APK、同语言／376dp 视口的采集范围与摘要另行归档。当前 APK 对应 `ConversationScreen.kt` 的 `ConversationTopBar` 内容高 64dp、圆形按钮 46dp、图标 22dp；`AdaptiveLayout.kt` 的手机内容横向留白 22dp，`HarnessAnimatedBackground.kt` 在 600dp 以上使用宽屏流场。这些是本轮参考源与早期文档 54dp／20dp 记载的差异，不能混用两套尺寸宣称全页 V 已关闭。系统安全区单独计算；鸿蒙聊天栏外层 14vp 留白与 64vp 内容区合计 78vp，其他原生页面标题栏保留各自尺寸。

通用 Paper 仍为 `#F9FAFC`；聊天 `ConversationPagerChrome` 使用 `MaterialTheme.colorScheme.surface`，故聊天背景独立映射明暗 surface，不把所有页面的 Paper 改为纯白。差异容许值仍为规划书的 2vp／1fp，性能阈值未放宽。
