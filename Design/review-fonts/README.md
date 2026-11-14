# 文件审阅公共字体

两端从本目录读取同一份字体文件，仅用于文件改动审阅界面。中文及界面文字使用 Noto Sans SC，代码的拉丁字符使用 JetBrains Mono，代码中文显式回退至 Noto Sans SC。字重通过字体自身的 wght 轴指定。

Figma 文件名中使用的 SF Pro 在落地时统一为 Noto Sans SC，以使 Android 与 iOS 的字形宽度、换行和字重一致，避免分发 Apple 平台专用字体。

原始字体来自 Google Fonts 的官方仓库：

- https://github.com/google/fonts/tree/main/ofl/notosanssc
- https://github.com/google/fonts/tree/main/ofl/jetbrainsmono

两者使用 SIL Open Font License 1.1。许可随 iOS 资源和 Android assets 一起打包。Android 通过资源源目录接入，iOS 通过工程资源引用接入，不再维护两份字体副本。
