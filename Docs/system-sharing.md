# 系统分享接收

Android 注册 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`，接收文本、链接及最多 20 个附件（合计 100 MiB）。内容先复制到应用私有目录，再显示工作区及会话选择底部面板；选择后进入会话，等待历史加载完成再填入草稿。图片复用压缩与缩略图逻辑，其他附件复用文件上传卡片，链接及分享文字复用引用 cell。旧网关不支持引用时保留原文。不会自动发送消息。

分享收件箱独立于会话数据，未处理内容可在进程重建后恢复。文件成功导入附件控制器后才清理分享副本，导入失败则保留供重试。

普通文件进入会话草稿后预上传至 DSH 附件存储，点击发送才将附件凭证作为消息提交；图片、文字及链接保留在草稿中，发送时提交。同一网关、同一会话断线重连时，已经完成上传的草稿保留会话凭证，不重复传输；未完成的上传从本地私有副本自动重新开始，旧连接的分块和完成回调不能覆盖新上传。网关明确拒绝附件凭证时自动重新上传附件，不自动重发消息。切换会话仍需重新上传并取得该会话的凭证；不同网关使用独立控制器。进程重建不恢复未经校验的旧凭证。

完全相同的含文件消息在重连后的手动重试保留原 `requestId`，避免消息已送达但回执丢失时重复入队；修改正文、图片或附件凭证后才创建新的提交标识。

Android 面板采用 iOS 风格的分组卡片，支持浅色和深色。顶部显示跨工作区最近活跃的三个进行中会话，可一键直达；下方工作区选择仅影响面板内的会话范围，不改变主页筛选。支持在当前工作区搜索会话、预览分享内容，以及在所选工作区新建会话。创建期间禁用重复点击和关闭，失败时保留分享内容供重试。未分组的新会话由网关默认工作区承接。

## iOS 扩展及签名

iOS 提供两个系统入口，共用同一套附件复制和草稿交接：

- `ShareExtension`：系统应用图标及头像会话建议。收到 `INSendMessageIntent.conversationIdentifier` 时直接定位已有会话；普通图标入口直接创建新会话。
- `ActionExtension`：下方竖向操作列表中的“用 Whaleness 询问”，直接创建新会话，不展示会话选择页。

两种入口均在扩展窗口出现后通过 `UIScene.open` 打开 `dshmobile://share?id=...`，主应用按指定分享 ID 读取 App Group 中的草稿。已在 iOS 27.2 模拟器中从 Safari 分别实测图标入口和竖向操作入口可以直接拉起主应用。系统拒绝打开时保留草稿并提供重试。

普通入口不使用当前工作区筛选：连接握手完成后，以不带 `workspaceId` 的 `session-create` 请求创建网关默认工作区的新会话。新会话 ID 先写回分享记录，再导航和填入草稿，避免恢复待处理记录时重复创建。未连接网关时保留分享，连接后继续。

从其他应用返回前台时，后台遗留的 `connected` 状态不能作为创建依据。普通分享入口等待前台健康探测完成；存在独立对话通道时，两条通道都需就绪。探测失败则保留分享草稿，等新连接握手完成后再创建一次会话，避免旧连接被回收时中断创建请求并间接弹出 `NSURLErrorDomain -1005`。诊断日志只记录创建阶段、失败错误域及错误码，不记录分享正文或文件名。

网关确认用户消息发送后，通过 `INInteraction` 提交 `INSendMessageIntent`，维护最近三个独立会话。每项包含会话标题、头像及主机隔离的稳定会话标识。超出三个的旧会话建议会移除。iOS 控制是否展示、展示位置和实际数量，应用不能强制固定系统头像栏。参考 [Apple 分享建议文档](https://developer.apple.com/documentation/foundation/supporting-suggestions-in-your-app-s-share-extension)。

主应用的 `NSUserActivityTypes` 必须包含 `INSendMessageIntent`，分享扩展的 `NSExtensionAttributes.IntentsSupported` 也必须声明该类型；只配置扩展会导致主应用的 `donate` 被系统拒绝。诊断日志分别记录上报成功及带错误域、错误码的失败，便于区分上报问题与系统推荐排序。

首次运行修复版本时，会补交本地已记录的最近三个会话，保留原消息交互时间；全部成功后标记完成。失败时下次回到前台重试，不要求用户重新发送消息，不把打开会话当成新的聊天行为。

`NSExtensionContext.intent` 只可在 Share Extension 中读取，Action Extension 调用会触发系统异常，因此两个入口必须先区分扩展类型。首次安装或尚无消息交互时，不需要先建立会话目录，普通入口仍然可以创建默认工作区草稿。

真机签名前，需要在同一开发团队下为主应用、`ShareExtension`、`ActionExtension` 的 App ID 启用同一个 App Group。Debug 默认是 `group.$(LOCAL_BUNDLE_IDENTIFIER)`；Release 真机使用 SHAOXI QIAN 团队（`T58RY5HUA7`）的独立容器 `group.com.lichaofan.dshmobile.t58ry5hua7`，主应用 Bundle ID 保持 `com.lichaofan.dshmobile`。两个 Release 分享扩展使用 `.ShareExtension-t58ry5hua7`、`.ActionExtension-t58ry5hua7` 后缀，避免与个人团队已注册的标识冲突。扩展 Bundle ID 必须以主应用 Bundle ID 加一个英文句点开头，后缀不能再含句点；团队区分使用连字符，避免上传校验拒绝。三个 target 的 `DSH_SHARE_APP_GROUP`、entitlements 和 Info.plist 已对应接入。

Release 的主应用、两个分享扩展及 Live Activity Widget 必须统一使用正式团队；不能让分享扩展继承 Debug 的 `LOCAL_DEVELOPMENT_TEAM`。修改 App Group 后，需要刷新包含该权限的描述文件。未签名 IPA 的构建不会检查 Apple 端授权，签名变更后应额外执行以下检查：

```sh
xcodebuild -project DeepSeekHarnessMobile.xcodeproj \
  -scheme DeepSeekHarnessMobile -configuration Release \
  -destination 'generic/platform=iOS' \
  -allowProvisioningUpdates -disableAutomaticPackageResolution build
```

此命令使用通用真机目标验证签名，不注册设备；指定某台真机运行时，该设备还必须已注册到对应开发团队。验证产物时检查主应用及所有扩展的签名团队，并确认主应用和两个分享扩展的签名权限与描述文件包含同一个 App Group。

签名构建通过不代表 App Store 分发校验通过。扩展标识变更后必须重新 Archive，并在 Organizer 中对新归档执行 Validate App；旧归档不会随项目设置变更。检查归档内每个 `PlugIns/*.appex/Info.plist` 的 `CFBundleIdentifier`，必须为主应用标识加 `.` 和不含句点的唯一后缀，同时确认扩展版本、构建号与主应用一致。

## 验证

- Android：`IncomingShareDeviceTest` 验证文件复制、ClipData 去重、进程重建恢复、消费清理及拒绝私有 file URI。
- Android 面板：`ShareDestinationsTest` 验证跨工作区最近会话排序、子 Agent 排除与工作区内搜索；`IncomingShareSheetDeviceTest` 验证工作区切换、预览返回、新建目标和禁用态，并生成深浅色截图；`IncomingShareConversationDeviceTest` 验证创建请求使用面板所选工作区、返回新会话 ID、保留原草稿且不自动发送。
- iOS：`ShareInboxTests` 验证分享隔离、消费清理、文件路径边界、最近三会话排序、默认入口不复用旧会话，以及默认工作区创建、引用 cell 真实渲染和不自动发送。使用项目同名测试 Scheme 运行。
- `testSystemAcceptsDeclaredMessageIntent` 需使用正常签名的模拟器宿主；禁用签名时，模拟器 Spotlight 可能无法注册应用并返回 `IntentsErrorDomain 1901 / CSIndexErrorDomain -1003`。该测试必须单独确认系统实际接受上报。
- iOS 前台恢复：`GatewayControlRecoveryTests` 使用真实本地 WebSocket 和分享导航页面，验证深链先于前台恢复到达、健康探测延迟、后台旧连接中断三个时序，检查新建请求只发送一次且不弹出连接丢失错误。
- 附件恢复：共享层 `SharedFileUploadStoreTest` 验证完成凭证保留、各上传阶段中断重启、旧回调隔离、跨会话失效及消息重试标识；iOS `NativeFileUploadIntegrationTests`、Android `IncomingShareConversationDeviceTest` 验证控制器实际使用私有文件恢复，且附件恢复不会自动发送消息。
- 项目现有部分 iOS 测试使用 iOS 17 API，但 target 最低版本仍为 16.1；测试验证可通过命令行临时设置 `IPHONEOS_DEPLOYMENT_TARGET=17.0`，产品最低版本保持 16.1。

## 输入框粘贴附件

Android 和 iOS 的输入框支持从系统剪贴板粘贴图片及文件。只在用户执行粘贴时读取内容，不在打开会话时读取剪贴板。图片复用现有压缩及缩略图草稿，文件复制到应用私有目录后复用附件上传卡片；不会自动发送消息。纯文字及普通链接继续插入当前选区，混合内容中的文字与附件分别处理。

Android 使用状态式 `BasicTextField` 与 `contentReceiver`，兼容原生粘贴菜单、Ctrl+V 及输入法富内容提交；未消费的文字交还原生编辑器。保留项目统一菜单、命令高亮、焦点节点及外部草稿同步。iOS 在原生 `UITextView` 的粘贴入口接收 `NSItemProvider`，在临时文件回调结束前复制文件，导入完成或失败后清理中转目录；异步导入完成时校验目标会话，避免切换会话后串入附件。

仍遵守草稿附件数量、大小和网关能力限制。剪贴板提供方必须实际提供可访问的图片或文件数据／URI；仅复制文件名或文件路径文字时按普通文字粘贴，不读取该路径。

文本 MIME/UTType 不等于复制了正文：`.log`、`.txt`、`.md`、`.csv`、`.json` 等文件仍可能声明为文本。iOS 优先识别文件 URL、原位文件表示及带文件名的数据 provider，再区分普通文字；Android 优先消费 `content://` 文件 URI，即使同一项附带文件全文也不能把全文插入输入框。只复制文件中的文字或文件名字符串时保持普通文字粘贴。
