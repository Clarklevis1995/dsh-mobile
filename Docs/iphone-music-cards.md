# iPhone Apple Music 卡片

在助手普通正文中，单独一段的 Apple Music 卡片链接会显示歌曲封面、歌名、歌手、专辑和时长。歌曲卡片标为“在 Apple Music 中播放”；专辑、歌单和歌手卡片仍为“在 Apple Music 中打开”。字段按官方实际返回情况展示，封面缺失或加载失败时显示音乐应用图标。生成回答及读取聊天历史不会调用播放器。

点击歌曲卡片后，先请求系统音乐授权，以官方歌曲 URL 的 song ID（或专辑 URL 的 i 参数）查询确切歌曲，再使用 SystemMusicPlayer 替换队列并播放。确认系统播放器正在播放所选歌曲后，才通过 Universal Link 打开 Apple Music；原来另一首歌正在播放不算成功。授权、目录查询或播放失败会提示，不降级为只打开链接，也不自动重试。专辑、歌单与歌手链接没有确定歌曲 ID 时只打开原内容，不猜测一首歌。此操作由用户点击直接触发，不经主机远程工具权限；系统授权、订阅、地区和歌曲可用性仍受 Apple Music 约束。

音乐卡片沿用文件气泡的 320 点表面最大宽度，在正文区域靠左对齐，窄屏按可用空间收缩，大屏不横向铺满。封面缩为 56 点，内边距 12 点，文字间距 3 点；卡片表面之外预留横向 8 点、纵向 4 点给原生玻璃阴影，避免被正文容器边界截断。相邻卡片的 Markdown 底部间距缩为 4 点，连续卡片的表面间距约 12 点。Android 正文列自带 10dp 段落间距，因此音乐卡片上下各留 1dp，连续卡片的表面间距同为 12dp。音乐卡片将原生玻璃背景及阴影强度降至原来的 60%，文字和封面保持不透明；Android 阴影透明度同步降低为原来的 60%。Android 同步这些音乐卡片布局参数；Android 的第三方播放器仍按各应用已有跳转协议执行，不能将 iOS 的 SystemMusicPlayer 控制接口照搬过去。

聊天列表中的阴影还受消息单元格、contentView 和 SwiftUI 宿主三层裁切约束。含实际应用卡片的消息在这三层保持不裁切，并在宿主复用或预热移交时重新应用；普通文本、行内链接和代码示例继续使用原有裁切策略。识别依据为解析后的独立段落链接及卡片目标校验，不以正文是否包含域名猜测。卡片最大宽度、靠左对齐及歌名单行省略保持不变；列表已有的两侧 20 点边距供溢出的阴影自然淡出。

## 数据来源

- 新增 `phone_music_catalog_search` → `music.catalog-search`：搜索 Apple Music 公开歌曲目录，参数 query 必填，limit 默认 10、范围 1～20。返回 items 与 source=apple-music-catalog；条目有 catalog: ID、title、artist、album，以及 MusicKit 提供且通过校验的可选 url。
- 原 `music.library` 个人曲库查询在歌曲、专辑、歌手及歌单条目中附加 MusicKit 提供的可选 url，内部 library: ID 及已有播放接口保持兼容。
- 网关只根据真实结果中的官方 URL 生成 musicLinks，最多 20 条，不根据 library:、catalog: 或旧媒体 persistent ID 拼链接。无官方 URL 的本地导入条目仍显示文字，不能自动假定目录里的同名歌曲就是该条目。
- 旧 music.search 及数字 ID 播放接口保持原行为。公开目录结果不表示歌曲已经添加到用户资料库，也不能将 catalog: ID 传给个人曲库播放工具。

Agent 使用返回的 musicLinks 原始 URL，在回答中单独一段输出 `[歌名 · 歌手](URL)`。普通行内链接和代码示例保留原有格式。歌曲可能以专辑 URL 加 i 参数定位，校验与跳转保留该参数，其他跟踪参数不转发。只接受 HTTPS 的 music.apple.com 内容路径，排除凭据、非内容页面及伪造域名。

## 封面与曲目信息

音乐目录和个人曲库条目新增可选 artworkURL、durationSeconds。封面取自 MusicKit Artwork.url(width:300,height:300)，仅允许 HTTPS 的 mzstatic.com 或其子域；歌曲时长取自 Song.duration，不把未返回的字段补成假值。封面 URL 由客户端异步加载，固定尺寸并裁切，失败不影响点击；不向模型发送图片二进制。

musicLinks 保留无片段的 url，另附 cardURL。Agent 优先原样输出 `[歌名 · 歌手](cardURL)`，不自行拼接封面或元数据。cardURL 的 `dsh-music-card` 片段携带版本为 1、最多 4096 字节的 UTF-8 JSON 元数据（base64url 编码），不包含新的跳转目标。App 校验字段及 URL 后展示，点击时剥离片段，仅从原始官方音乐链接解析歌曲身份和跳转目标，不从展示标题猜测歌曲。历史记录保存的是完整卡片链接，因此不依赖网关内存缓存；旧的普通音乐链接继续使用原有卡片样式。

音乐卡片无封面、歌手、专辑或时长时分别省略对应内容，音乐图标负责封面占位。查看封面需要访问官方图片 CDN；公开歌曲的封面预览测试是可选网络夹具，核心数据与缺失回退测试不依赖网络。

## 权限与运行条件

公开目录搜歌沿用音乐连接器、本机系统授权、主机权限及前台执行要求，新工具需手机显式声明，旧手机不接收新增调用。曲库和公开目录查询不会自动播放、添加收藏或改变歌单。目录网络、账号或 MusicKit 服务失败返回 music_catalog_unavailable，不返回伪空结果；拒绝授权及取消仍分别处理。

MusicCatalogSearchRequest 需要正确配置 App ID 的 MusicKit 服务与可用网络。真机需验证签名配置、所在地区的目录可用性和点击后由 Apple Music 处理链接的行为；模拟器及注入数据测试不代表用户真实曲库或账号已经验收。

参考：[Song.url](https://developer.apple.com/documentation/musickit/song/url)、[MusicCatalogSearchRequest](https://developer.apple.com/documentation/musickit/musiccatalogsearchrequest)、[App ID MusicKit 服务](https://developer.apple.com/help/account/services/musickit)、[仅通过 Universal Link 打开应用](https://developer.apple.com/documentation/uikit/uiapplication/openexternalurloptionskey/universallinksonly)。

## 2026-11-15 真机报告后的播放与目录诊断

- `music.play` 改为等待 `SystemMusicPlayer.shared.play()`，不带 itemId 时沿用当前系统队列，不重新选歌或重置队列。指定旧媒体 ID 时仍先使用原有个人曲库解析，再等待系统播放器执行。
- `music.play` 与 `music.play-library` 在异步调用完成后，最多再等待 1.5 秒观察到 playing 才返回播放成功；持续暂停或系统报错返回 `music_playback_unavailable`。确认只表示观察到系统播放状态，不保证扬声器已经发声。取消仍按取消处理，不自动重放命令。
- 目录失败保留对外错误码 `music_catalog_unavailable`，本机可导出日志新增 `MusicConnector operation=catalog-search`，包含实际 bundle ID、Whaleness 的 MusicAuthorization 状态及最多四层 NSError domain/code。不记录 token、查询词、URL、原始描述和 userInfo；播放系统错误使用同样的安全诊断。
- 系统音乐 App 能搜索，并不能证明 Whaleness 的 MusicKit 服务、自动开发者令牌和授权正常；非法 limit 在网关就可能被拒绝，也不能用于证明请求已到达手机。关闭代理后的相同错误仍需结合底层错误码区分服务配置、授权和网络问题。
- 真机复测：暂停 → 不带 ID 的 play → state，确认成功时为播放中、播放进度延续；接着合法调用 catalog-search，失败后通过 App 的日志导出入口查找上述诊断行。导出前无需提供 Apple Music token。
- 若日志指向 MusicKit 服务或令牌配置，需在 Apple Developer 的 Identifiers 中找到日志里的实际 App ID，在 App Services 开启 MusicKit；Debug 与正式版标识不同的，要分别核对。现有“打开系统权限设置”入口可用于检查本 App 的音乐权限；再次调用授权接口不会强制重置已授予的系统权限。

本地注入测试不访问用户曲库、账号，也不改变真实播放状态；目录服务是否恢复仍须在正确签名的真机版本上复测。
