# 账号认证图标

采用 Google Material Symbols Rounded 的 Passkey（人物＋钥匙），保留官方矢量路径。

- Figma 预览：https://www.figma.com/design/Dvs7ruDuthX1vtF1rDQyFc?node-id=33-192
- 官方 SVG：https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/passkey/fill1/24px.svg
- Android 资源：`androidApp/src/main/res/drawable/ic_gateway_auth.xml`

SVG 原始画布为 `0 -960 960 960`，尺寸为 24×24。Android 将 Y 坐标平移 960，保持图形比例与路径一致。
按钮继续使用现有尺寸与模板着色，账号认证菜单、扫码和手动配对行为沿用原实现。

许可为 Apache 2.0，随资源保留 LICENSE。

## 认证菜单图标

- 扫描二维码：Material Symbols Rounded / qr_code_scanner，源文件 `qr-scanner.svg`。
- 手动输入配对信息：Material Symbols Rounded / keyboard，源文件 `keyboard.svg`。
- Figma 预览：https://www.figma.com/design/Dvs7ruDuthX1vtF1rDQyFc?node-id=38-202
- 官方 SVG：https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/qr_code_scanner/default/24px.svg
- 官方 SVG：https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsrounded/keyboard/default/24px.svg

Android 对应资源为 `ic_qr_viewfinder.xml` 和 `ic_keyboard_outline.xml`，保留官方路径，使用菜单现有模板着色。
以上图标均使用 Apache 2.0 许可，已包含在 Android 的 `AuthenticationIcons.LICENSE.txt` 中。
