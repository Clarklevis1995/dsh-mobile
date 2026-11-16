# iOS 本地真机调试配置

将 `Local.xcconfig.example` 复制为 `Local.xcconfig`，填写：

- `LOCAL_BUNDLE_IDENTIFIER`：自己固定使用的调试 Bundle ID。
- `LOCAL_DEVELOPMENT_TEAM`：Xcode 账号对应的 Apple Team ID。
- `LOCAL_APP_GROUP`：可选，默认是 `group.$(LOCAL_BUNDLE_IDENTIFIER)`。默认名称不可用或切换团队时，可指定当前团队的独立 App Group；主应用及分享扩展会共用该值。
- `LOCAL_EXTENSION_SUFFIX`：可选，默认留空。扩展 Bundle ID 在当前团队不可注册时，可设置如 `-yourteam`，统一追加到三个 Debug 扩展标识后；不修改主应用与 Release 标识。

主应用、单元测试、UI 测试、分享扩展和 Live Activity 扩展的 Debug 配置共用这些值，测试和扩展自动追加对应后缀。`Local.xcconfig` 已加入 Git 忽略规则。Release 保留现有签名配置；本机希望 Debug 与正式发布使用同一团队时，将 `LOCAL_DEVELOPMENT_TEAM` 填为正式团队 ID，Debug 仍保留独立包名和数据容器。

选择 Debug 构建后正常运行即可，无需修改 Xcode 中的 Bundle Identifier 或 Team，否则可能覆盖配置变量。本地文件缺失时使用示例 Bundle ID 和空 Team，需要填写本地配置才能真机签名。

此配置不向 Apple 注册标识符，也不会修复服务端的“标识符不可用”错误；如需更换调试标识符，只修改本地文件。
