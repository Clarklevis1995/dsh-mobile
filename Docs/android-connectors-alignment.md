# Android 与 iOS 连接器对齐

## 对齐范围

两端保持同样的侧栏「连接器」入口、带线插头图标、分组开关、系统权限入口和独立注册状态页。外层只保留一个状态 item 与指示灯，不重复展示同步说明。开关按当前主机保存，手机上的开启/关闭补丁同步对应设备的网关权限，已开启的连接器不逐次确认。

绿色只表示收到了网关有效的登记清单；灰色表示断开，橙色表示注册中、未确认或旧网关不支持，红色表示登记失败、超时或已连接但没有登记。详情页展示网关确认的工具数量、确认时间及可折叠的模块/工具清单，支持只读刷新和重新注册。页面在前台每 10 秒检查一次；只读刷新不换租约、不取消正在执行的调用。

网关用 `platform=android` 区分来源，在 Agent 上下文中只解释 Android 实现；来源设备仍由已鉴权消息绑定，不能切换到其他手机。共享 Runtime 放行 `device-tools-status` 控制帧，沿用现有 device-tools-v1 协议。

## 平台能力

| 模块 | Android 实现 | 边界 |
| --- | --- | --- |
| 日历 | Calendar Provider：查询忙闲、创建日程和提前提醒 | 需系统读权限；写入另外需要写权限及可写日历。全天日程按日历提供器要求以 UTC 日期保存。不读取参与人、不发邀请。 |
| 提醒事项 | 当前主机的 Whaleness 本机提醒，支持创建、查询、可选通知 | Android 没有通用的 Apple Reminders 等价资料库。不读取厂商待办；列表标明 whaleness-host-reminders。普通提醒使用非精确系统调度，可能延迟。 |
| 位置/地图 | 近似位置；获得精确授权后提供精确位置；系统 Geocoder 地址解析、反查；地图跳转；应用内地点标记的增删改查 | 不含后台定位。Geocoder 缺失时不登记解析工具；未接入地图服务 Key，因此不登记 POI 搜索和路线计算。地图卡片交给系统地图处理，缺少处理器时可用网页。地点标记不等于第三方地图收藏。 |
| 照片 | 系统选择器选一张照片，返回移除元数据的缩略图 | 不声称可以扫描第三方相册私有数据。保持两端既有 768px、64KiB JPEG 协议。 |
| 音乐 | MediaStore 本机歌曲搜索/浏览；通过音乐应用打开歌曲；用户开启通知访问后读取及控制活跃 MediaSession | 共享音频不等于 QQ/网易云私人云端曲库。曲库目前仅 songs；其他 kind 明确返回不支持。暂停、继续、跳转取决于播放器声明的动作，没有活跃播放器不报设备离线。未注册 Apple Music 在线目录或第三方歌单编辑能力。 |
| 闹钟 | `alarms.open-create` / `phone_alarm_open_create` 用系统时钟 Intent 设置闹钟，默认 `EXTRA_SKIP_UI=true` 请求跳过中间确认 | 厂商时钟可能仍显示界面，不能保证静默。通用接口没有可靠的创建成功回执，因此只回 handoff-requested、skipUIRequested=true，不复用 iOS AlarmKit 的「已创建」语义。只接受设备下一次该时分、秒为 00，不支持任意未来日期，也不声明查询/取消别的时钟 App 闹钟。网关继续兼容旧 App 的 requiresConfirmation=true 回包。 |

第三方音乐平台的官方 Skills/API 属于独立的账号连接器，需要平台应用凭据和用户授权。本轮不把未授权的平台能力注册为已接入；网易云/QQ 的云端搜索和歌单管理可以在后续通过主机侧官方接口实现。

## 音乐、地图卡片

Android Markdown 中独立成段的受支持链接渲染为应用卡片，保留前后正文；行内链接、代码和列表中的链接维持原有格式。

- 本机歌曲卡片由网关依据已校验的 MediaStore ID 生成 `whaleness-music://local/songs/<id>`，支持实际返回的封面、歌手、专辑、时长。封面缺失或读取失败时用 Android 音乐图标。不把音频二进制发给主机。
- 同时识别合法的 Apple Music、网易云、QQ 音乐内容链接，点击交给系统处理；这只代表卡片跳转支持，不代表已经取得平台账号权限。
- 本机封面严格限定 MediaStore albumart URI；网络封面限定官方 CDN、HTTPS、大小及图像尺寸，禁止重定向到任意地址。
- Android 地点结果生成 Google Maps 坐标分享链接；点击卡片优先使用通用 geo Intent，交给已安装地图应用。兼容历史苹果地图坐标卡片，并在 Android 上转换为地图跳转。
- 手机先保存执行回执并发送工具结果，再打开音乐/地图/时钟，避免目标 Activity 导致 ON_PAUSE 把已经完成的调用取消。跳转请求不等于播放、导航或闹钟创建成功。

## 写入与重连

日历、提醒、地点标记按主机、会话、工具及 operationId 保存原子执行回执；参数冲突明确拒绝，不确定结果不自动重做。重启和重连后复用同一 operationId 返回历史结果。外部应用跳转与音乐控制按会话/工具调用去重，不自动重放不确定的操作。

撤销权限、切后台、换租约和断线会停止当前调用。已经开始的系统写入仍保存回执，并把不确定结果报为 outcome_unknown。普通重新登记只读取主机现存权限，不重放已经发出的开启补丁，避免覆盖用户在网关上的撤权；离线尚未发出的开关修改可以在 App 重启后继续同步。

本次没有加入联系人，也不以网页深链接冒充第三方 App 私有接口。

## 构建与验收

使用 Debug 包，与正式版共存；无需卸载正式版。App 与移动网关都需要更新，网关代码更新后需要重启服务，手机重新连接。老网关会过滤不认识的新工具，手机状态页可以看到实际接入的交集。

自动化覆盖：登记确认/缺失/超时、只读状态刷新、主机权限撤销、离线补丁持久化与不重放、重复调用去重、照片返回时机、写入回执与主机隔离、地点标记版本、Whaleness 提醒、真实 MediaStore 查询、卡片边界和页面展开折叠，以及 AppGraph→Runtime→控制帧→状态回包整条链路。测试只操作临时夹具和模拟器，不向用户真实日历、健康库或音乐账号写入数据。

仍需真机验收：厂商日历可写账户、QQ/网易云的 MediaSession 行为、地图默认处理器、系统时钟是否支持跳过界面及实际响铃、待办通知在厂商省电策略下的送达。用户强制停止 App 后，Android 可能暂停本应用提醒，重新打开 App 后恢复；已经在系统时钟中成功创建的闹钟由时钟 App 调度。

参考：[Android 日历提供器](https://developer.android.com/identity/providers/calendar-provider)、[通用系统 Intent](https://developer.android.com/guide/components/intents-common)、[MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager)。

### 本轮验证结果

- Android Debug APK 和仪器测试 APK 构建成功；模拟器专项测试 18/18 通过。
- 网关 Android 专项与既有 iOS 连接器测试合计 38/38 通过。
- 共享层在 iOS Simulator Arm64 运行 4/4 通过，覆盖数值协议版本的解码、工具归属及状态请求的控制通道。
- `git diff --check` 通过。
- 全量 Android Lint 未通过：现有 CalendarPicker、LogExportPickers 的 Locale 读取，以及 TeamMemberMenuDeviceTest 的未 remember 状态，共 7 个错误；本次新增连接器文件无 Lint 错误。全项目本地化守卫仍有 68 项既有问题，集中于未完成本地化的 iOS 等文件，本次 Android 连接器文件无新增问题。未通过修改基线或关闭规则隐藏这些失败。
- 项目未配置 ktlint / detekt，当前环境也未安装这两个命令；本轮使用编译器、Android Lint、仪器测试及既有本地化守卫进行检查。

调试包：`androidApp/build/outputs/apk/debug/androidApp-arm64-v8a-debug.apk`。包名为 `com.clarklevis.dsh.android.dev`，与正式版共存。其他 ABI 及 universal Debug 包也已由相同构建生成。本轮未发布正式版，也未自动重启用户正在运行的网关。

### 连接器页面样式与导航修正

连接器页和状态页使用与分组设置页一致的中性背景：浅色 #F2F2F7、白色卡片，深色黑底、#1C1C1E 卡片；正文颜色显式跟随主题，避免 Material 默认表面色偏紫或深色模式继承黑色文字。开关复用项目的 DshSwitch（51×31dp、27dp 白色滑块、无描边轨道），与其他设置页一致。

连接器页面 `connectors` 和状态页 `connectors/status` 是主 NavHost 的独立目的地，使用 NavController.navigate / popBackStack，继承产品统一转场。删除原本的局部 detail 布尔切换和 BackHandler；返回后由导航目的地恢复主页面状态。两个页面分别按自己的导航生命周期轮询注册状态。

该轮 UI 专项测试覆盖实际导航栈入栈、顶部返回按钮、系统返回分发器、返回后的开关状态、展开折叠、实际渲染背景色、开关尺寸、深色文字可读性，以及原有应用卡片回归。

### Android 健康能力移除

按用户要求，Android 不再提供健康连接器：删除本机入口、注册模块、读取实现、Health Connect 权限和用途说明 Activity。旧版本保存的健康开关会被清理，并在连接时发送关闭该主机健康权限的补丁。iOS 与共享协议继续保留健康能力。
