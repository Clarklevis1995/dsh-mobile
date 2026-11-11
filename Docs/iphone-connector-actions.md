# iPhone 连接器操作

本次增加新建提醒事项、创建日历日程和 iOS 26+ AlarmKit 一次性闹钟。App 和网关需同时更新。提醒事项、日历沿用现有连接器权限；闹钟新增独立开关及系统授权。通讯录仍不接入。已开启的连接器不逐次确认，模型仅在用户明确要求创建/取消时执行，查询不能顺便创建。

## 工具与参数

所有快捷工具按实际发送消息的手机路由，无需枚举设备；timeZone 默认使用该消息的 clientTimeZone。桌面没有手机来源时需显式设备 ID 与时区。

| 快捷工具 | 原生工具 | 参数 |
| --- | --- | --- |
| phone_reminders_create | reminders.create | 必填 operationId、title、timeZone；可选 dueDate、notes、priority、notify |
| phone_calendar_create | calendar.create | 必填 operationId、title、start、end、timeZone；可选 notes、location、allDay、alertMinutesBefore |
| phone_alarm_create | alarms.create | 必填 operationId、title、fireDate、timeZone |
| phone_alarms_list | alarms.list | 无参数；列出当前主机登记的 Whaleness 闹钟 |
| phone_alarm_cancel | alarms.cancel | 必填 operationId、alarmId；只允许取消当前主机登记的闹钟 |

operationId 必须是本次用户操作生成的 UUID；重试原样复用，不可因结果不明而生成新 ID。title 最多 500、notes 最多 2000、location 最多 500 个 UTF-16 代码单元。日期时间必须带 UTC 偏移，时区为 IANA 名称。

提醒事项写入系统默认提醒列表。dueDate 可省略，省略时 notify 默认 false；提供日期时默认 true，到点通知由 EKAlarm 实现。notify=true 必须有日期。priority 允许 0、1、5、9。返回真实记录 ID、标题、日期、优先级及 created 状态。现有范围查询仍最多 8 天，不新增无日期事项的全量读取。

日历写入系统默认可写日历，不新增参会人、不发邀请，不编辑/删除既有日程。start/end 间隔最多 31×24 小时；全天日程需按指定时区零点传入，end 不包含在日程日期内。alertMinutesBefore 允许 0～10080 分钟；不传则不主动添加 EKAlarm。返回实际记录 ID 和日期，不返回参与人信息。

闹钟使用 AlarmKit 的 fixed schedule，fireDate 必须为未来时刻，调度成功后返回 created。本期只做一次性闹钟，不含重复、贪睡和倒计时，不修改苹果“时钟”App 的闹钟。没有倒计时，因此不需要额外实现倒计时 Widget。列表和取消均限定当前主机通过 Whaleness 创建的闹钟；不能管理其他主机或“时钟”App 的闹钟。iOS 26 以下开关禁用，Android 不声明此能力，不以普通通知冒充闹钟。

## 持久化与取消语义

iOS 按主机、会话、工具和 operationId 保存执行回执：先原子保存 attempted，再调用系统，成功后原子保存结果。记录位于 App 私有 Application Support，使用文件保护。重连、App 重启后复用同一操作会返回原结果；参数不同返回 operation_conflict；结果未确认的尝试返回 outcome_unknown，不再次执行。执行记录无法保存时不会调用系统写入。重试仍须通过本机开关、网关权限及当前系统授权。

闹钟 ID 由主机、会话和操作稳定生成，系统调度前保存归属信息。即使创建过程被中断，也可将该归属信息与 AlarmManager 的实际闹钟列表核对，并明确取消。系统保存后即使任务已取消，仍保存成功回执，避免重连后重复创建。

主机对已发出的写入若遇到断线、超时、撤权或无效回包，返回 outcome_unknown，因为系统可能已保存。此时不能换新 operationId 自动重试。关闭连接器只禁止后续调用，不删除已经创建的提醒/日程，也不自动取消已调度闹钟；取消闹钟需要明确调用取消工具。首次调用需要前台 App 接收；调度成功后的到点提醒/响铃由系统负责，不依赖 Agent/WebSocket 持续运行。

每个注册模块新增可选 tools 白名单，新版 iOS 明确声明这些写入能力。旧 App 未声明 tools 时仅按原有读取/音乐工具处理；新网关不会向旧手机派发新写入，也不在其来源上下文中列为可用。协议仍为 device-tools-v1；新增闹钟模块要求同时更新 App 和网关。

## 音乐现有能力

| 工具 | 当前行为 |
| --- | --- |
| music.search | 按歌名包含匹配搜索已授权音乐资料库，最多 20 首，返回标题、歌手和 itemId |
| music.play | 指定搜索返回的 itemId 播放，或不传 ID 继续当前歌曲 |
| music.pause | 暂停系统音乐播放器 |
| music.seek | 跳到指定秒数，不超过当前歌曲时长 |
| music.state | 读取系统播放器的状态和当前秒数 |

新增的个人曲库浏览与播放见下节。未实现 Apple Music 在线目录搜索、歌单编辑、上一首/下一首、音量控制或其他音乐 App 控制。播放还受设备资料库、系统播放器状态和可播放资格影响；发出播放请求不等于已听到声音。Android 暂未提供音乐连接器。

## 验证与真机验收

已用模拟操作验证参数校验、持久去重、参数冲突、系统调用前保存失败、取消后回执保存、主机隔离及不明闹钟归属恢复；不写用户真实日历，不调度真实闹钟。网关测试覆盖参数/结果边界、旧设备工具声明、断线与撤权不明结果、实际来源绑定和真实 WebSocket 写入请求/回包。

真机需验收系统授权、默认可写日历/列表的实际落库、到点提醒、锁屏响铃和用户关闭系统授权后的表现。现有 iOS 测试源码含未标注可用性的 iOS 17 trait API，本轮模拟器测试使用命令行 IPHONEOS_DEPLOYMENT_TARGET=17.0；产品最低版本仍为 16.1。AlarmKit 已验证以弱链接方式加载，旧系统在可用性判断后禁用。

Apple 参考：[EventKit 创建事件与提醒](https://developer.apple.com/documentation/eventkit/creating-events-and-reminders)、[AlarmKit 系统调度](https://developer.apple.com/documentation/AlarmKit/scheduling-an-alarm-with-alarmkit)、[AlarmKit 固定时间闹钟](https://developer.apple.com/documentation/alarmkit/alarm/schedule-swift.enum/fixed(_:))。

## Apple Music 个人资料库补充

音乐连接器申请的是系统“媒体与 Apple Music”资料库权限。手机开关、对应主机的网关权限、iOS 系统授权需同时满足。这个授权不包含其他音乐 App 的私有曲库，不等于已加入个人资料库之外的整个 Apple Music 在线目录。

旧版 `music.search` 只按歌名匹配，无法在不知道歌名时浏览资料库；旧实现还把 MPMediaQuery 返回 nil（读取错误）转换为空数组。本轮将 nil 明确返回 music_library_unavailable，保留数字 persistentID 的旧搜索/播放兼容路径；来源名称改为 authorized-media-library，不再把所有 MediaPlayer 条目误称为仅本地文件，系统已同步的云端条目也可能存在。

新版 `phone_music_library` / `music.library` 使用 MusicKit 的 MusicLibraryRequest。kind 支持 songs、albums、artists、playlists，默认 songs；query 可省略，也可筛选文本；offset 默认 0、最多 10000，limit 默认 20、最大 50。明确 includeOnlyDownloadedContent=false，不限定已下载音频。但根据苹果文档，iOS 资料库请求读取设备上的个人资料库副本，不会自动到网络补齐尚未同步的资料库，因此账号及系统资料库同步状态仍会影响返回结果。

返回的 hasMore/nextOffset 用于分页；达到 offset 上限时 pagingLimitReached=true、nextOffset=null，不能宣称已读完资料库。空页只报告 empty-in-requested-scope，不代表整个曲库为空。系统授权拒绝返回 permission_denied，其他读取错误返回 music_library_unavailable，不能将它们改写成没有歌曲。

新返回的歌曲、专辑、歌单 ID 为不透明的 library: 前缀值，应交给 `phone_music_play_library` / `music.play-library` 播放，并保留对应 kind；不能用旧 music.play 的数字 ID 接口播放。歌手条目用于浏览，不能直接播放。MusicKit 在个人资料库按 ID 解析后设置系统音乐播放器队列，不会因未找到条目而自动搜索在线目录。不新增歌单编辑、曲库写入或音频文件导出权限。暂停、继续、跳转及状态读取沿用系统播放器。

两个新工具需手机显式声明，新网关不会发给旧版 App。App 与网关需同时更新。模拟器使用假的资料库提供器验证参数、权限撤销、分页和错误语义，不读取用户真实曲库，也不能据此证明真机 Apple Music 账号或实际播放成功。真机需核对：系统“媒体与 Apple Music”授权、音乐 App 正在使用的账号及资料库同步；涉及 Apple Music 服务播放时，对应 App ID 的 MusicKit 服务也需按苹果开发者配置启用。

参考：[MusicLibraryRequest](https://developer.apple.com/documentation/musickit/musiclibraryrequest)、[iOS 资料库读取设备副本](https://developer.apple.com/videos/play/wwdc2022/110347/?time=947)、[MusicKit App ID 服务配置](https://developer.apple.com/documentation/musickit/explore-more-content-with-musickit)。
