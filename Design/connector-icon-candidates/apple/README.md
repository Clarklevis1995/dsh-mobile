# 苹果 SF Symbols 插头候选

预览由本机 AppKit 的 NSImage(systemSymbolName:) 实际渲染，使用 monochrome 黑色、Regular 字重、Medium scale。用户已选定第 05 款 powercord，已应用到 iOS 两个侧栏入口，并为 Android 独立绘制对应的带线插头样式。

| 编号 | 符号名 | 系统可用性 |
| --- | --- | --- |
| 01 | powerplug | iOS 15+ |
| 02 | powerplug.fill | iOS 15+ |
| 03 | powerplug.portrait | iOS 18+ |
| 04 | powerplug.portrait.fill | iOS 18+ |
| 05 | powercord | iOS 17+ |
| 06 | powercord.fill | iOS 17+ |

额外导出 cable.connector、cable.connector.horizontal（均 iOS 15+）作为线缆类备选；app.connected.to.app.below.fill 保留为旧图标参考。

名称及 iOS 最低版本通过本机苹果 CoreGlyphs.bundle 的 name_availability.plist（symbols / year_to_release）核对。当前系统没有 powerplug.circle / powerplug.portrait.circle 的独立内置符号；如果要圆形外框，需要组合绘制，不能把它标成一个现成 SF Symbols 名称。接入新系统符号时必须保留旧版 iOS 回退。

官方资料：[SF Symbols](https://developer.apple.com/sf-symbols/)、[配置系统符号的字重与尺寸](https://developer.apple.com/documentation/uikit/configuring-and-displaying-symbol-images-in-your-ui)。图形版权及使用条款属于 Apple，本目录不是开源图标素材包。
