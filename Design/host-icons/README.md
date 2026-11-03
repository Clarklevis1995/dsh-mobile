# 主机显示器图标

采用 Google Material Symbols Rounded / desktop_windows，与参考截图一致使用屏幕描边和实心底座。

- Figma 预览：https://www.figma.com/design/Dvs7ruDuthX1vtF1rDQyFc?node-id=41-215
- 官方 SVG：https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/desktop_windows/default/24px.svg
- `desktop.original.svg`：官方原始 SVG。
- `desktop.svg`：Figma 导出的 24×24 标准画布，作为 Android 矢量路径来源。
- Android 资源：`androidApp/src/main/res/drawable/ic_gateway_pc.xml`。

主机切换列表、顶部主机入口和主机类型选择共用这一资源，沿用各处现有尺寸、模板着色及交互。
许可为 Apache 2.0，声明随 Android assets 的 `HostIcons.LICENSE.txt` 分发。
