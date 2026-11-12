# 地图应用选择面板设计稿

[Figma 设计稿](https://www.figma.com/design/0qYyEvoG07J77OKPPnseTp?node-id=4-41)

- iOS 画板：`4:50`，苹果、高德、百度、谷歌。
- Android 画板：`4:112`，高德、百度、谷歌。
- 真实 App 图标来自各厂商官方 App Store 条目，见 `assets/sources.json`；原始素材为 512×512 JPEG。
- 原生底部面板，展示目的地、出行方式、已安装地图应用和网页备用入口。
- 应用图标使用独立组件，列表行使用可替换图标与名称的组件实例；文本、分隔线、面板布局均为可编辑图层。
- 字体采用 SF Pro；中文标题使用 Noto Sans SC 映射设备系统中文字体。背景及正文样式复用 Figma 官方 iOS 组件库变量与样式，并沿用 Whaleness 蓝色。
- `preview.png` 是设计稿的导出预览；`figma-design.js` 记录主体图层的创建逻辑。

## 两端实现与验收

- iOS：`MapApplicationPicker` + `MapApplicationPickerContent`，使用官方图标的本地 Asset Catalog；按内容高度展示系统 Sheet，自动适应安全区和辅助字号。
- Android：`MapAppPickerSheet` + `MapAppPickerContent`，本地 `drawable-nodpi` 图标；显式中性面板与列表颜色、0 tonal elevation、32% 黑色遮罩和自定义拖动条。
- 两端保持应用安装检测、返回时刷新、路线跳转及失败重选。
- Android 自动化在紫色 Material 主题下检查实际背景像素，验证图标 44 dp，保持按下状态向下再向上拖动，检查标题与拖动条相对位置；iOS 验证本地素材及明暗布局快照。
- 实际预览：`implementation-android-light.png`、`implementation-android-dark.png`、`implementation-ios-light.png`、`implementation-ios-dark.png`。iOS 内容快照由 UIHostingController 渲染；原生系统安全区及 Home 指示条由系统处理。
