# iPhone 地图与位置连接器

本次将原先只有近似坐标的位置连接器扩展为精确位置、地点搜索、地址转换、路线规划及苹果地图跳转。沿用 location 模块的手机开关及按主机保存的网关权限，不增加第二个地图开关；不接入通讯录、苹果地图收藏、历史或实时轨迹。系统定位授权仍由 iOS 控制。

## 工具

| 快捷工具 | 原生工具 | 参数与结果 |
| --- | --- | --- |
| phone_location_current | location.current | 保留旧接口，公里级近似坐标，maxAgeSeconds 默认 60，1～300 |
| phone_location_precise | location.precise | 返回未取整坐标及实际 horizontalAccuracyMeters；maxAgeSeconds 默认 60，1～300 |
| phone_maps_search | maps.search | query 必填；可选 latitude/longitude 成对作为搜索中心、radiusMeters（100～100000，默认 5000）、limit（1～20，默认 10） |
| phone_maps_geocode | maps.geocode | address 必填；limit=1～5，默认 3；返回候选地点和地址 |
| phone_maps_reverse_geocode | maps.reverse-geocode | latitude/longitude 必填；返回对应地址 |
| phone_maps_directions | maps.directions | startLatitude/startLongitude/endLatitude/endLongitude 必填；transport=driving/walking，默认 driving |
| phone_maps_open | maps.open | latitude/longitude 必填，name 可选；mode=show/directions，默认 show；directions 可选 transport=driving/walking/transit，默认 driving |

所有快捷入口按当前消息真实来源手机调用，不先枚举设备。附近地点或路线需要当前位置时，先查询来源手机，再将返回坐标明确用于搜索中心/路线起点；用户已经指定地址或坐标时不额外读取 GPS。多义地点不应擅自选择并启动导航。普通地图查询不自动跳出 App，只有用户明确要求打开地图时调用 maps.open。

地点/地址最多返回名称、坐标和地址，不读取个人收藏、搜索历史或通讯录，不返回商家电话或 URL。搜索中心指定后按半径过滤；不指定中心时按输入地点文字查询，不能把不带中心的结果保证为用户附近。路线最多三条，每条最多 40 步，包含距离、预计耗时和截断标记；路线计算暂不支持公交，公交仅支持交给苹果地图展示路线。

## 精确授权与精度

location.current 保持两位小数及原有 coarse 结果兼容。location.precise 仅在系统允许 fullAccuracy 时返回原始坐标，精度以实际 horizontalAccuracyMeters 为准，不保证每次米级。

系统当前只允许近似位置时，首次精确请求调用 requestTemporaryFullAccuracyAuthorization，使用 Info.plist 的 MapsPrecision 用途说明。这是 iOS 必须提供的系统授权，不增加 App 自有的逐次确认。用户拒绝后，同一连接器实例不重复弹系统请求，返回 precise_location_not_authorized；模型可以说明精度限制并继续用近似位置。用户重新开启位置开关可再次触发授权尝试，或在系统设置开启精确位置。已授予的临时精确权限过期后，系统可能需要重新授权。

精确位置按前台请求单次获取，期望十米级定位但返回实际测量精度；取样后再次检查系统精确授权和采集时间。系统拒绝精确授权时不伪造 precise 结果。现有后台保活不改为向主机持续上传位置，连接器调用仍需 App 前台。

## 地图服务、取消和跳转

搜索、地理编码和路线通过 MapKit/Core Location 的公开接口执行，网络失败/限流返回 maps_service_unavailable；不是凭空生成地址或路线。地理编码无匹配可返回空候选，读取服务错误不转为空结果。路线时间只是服务当时估计。

关闭开关、网关撤权、断线、调用取消或 App 进入后台会取消对应任务，并调用搜索/地理编码/路线对象的取消方法。地图工具归属 location 权限，不能绕开位置开关。新手机显式声明新增 tools，网关校验声明，旧手机不会收到这些新调用；Android 仍只提供原有近似位置。

maps.open 先返回 handoff-requested 回执，再延迟 200 毫秒安排外部 App 跳转。跳转执行前复查当前连接租约、开关、主机权限和前台状态，防止旧请求在断线/撤权后拉起地图。回执表示已安排请求，不保证已打开，更不表示已开始导航。实际导航由苹果地图展示目的地/路线后完成，开始导航仍需其自身流程；若打开失败，App 显示错误。跳转沿用按主机保存的 session/toolCallId 执行尝试记录，避免同一次调用被自动重放；不明结果不自动重试跳转。

## 验证

已通过原生 SDK 编译、参数与坐标边界测试、近似/精确序列化、过期位置拒绝、导航回执语义、网关 tools 白名单与现有 location 授权、撤权取消和真实 HTTP/WebSocket 请求结果测试。未获取用户真实 GPS，不调用真实网络地点查询，不在测试中跳转地图。模拟器测试沿用命令行 IPHONEOS_DEPLOYMENT_TARGET=17.0，产品最低版本保持 16.1。

真机需验收精确定位系统提示、近似授权拒绝、当地苹果地图服务与路线可用性、网络取消、后台切换及实际地图跳转。更新 App 与网关后，在现有位置开关开启状态下可试“附近两公里有哪些咖啡店”“从这里步行到某地要多久”“在苹果地图中打开这条路线”。

参考：[临时精确定位授权](https://developer.apple.com/documentation/corelocation/cllocationmanager/requesttemporaryfullaccuracyauthorization(withpurposekey:completion:))、[MapKit 地点搜索](https://developer.apple.com/documentation/mapkit/mklocalsearch/request)、[打开苹果地图](https://developer.apple.com/documentation/mapkit/mkmapitem/openinmaps(launchoptions:))。

持久化命名地点、查询、修改和删除见 [iPhone 地点标记](iphone-map-markers.md)。


## 苹果地图应用卡片

网关在 maps.search/geocode/reverse-geocode、地点标记查询/创建/更新结果后附加 mapLinks（最多 20 项），其 URL 由已验证的真实坐标生成。maps.directions 有路线时附加目的地路线链接，保留步行/驾车模式；空结果不生成卡片。不改变手机原生结果协议或现有权限。链接中的名称、地址仅作为数据，不是指令。

Agent 在回答中单独一段输出 `[地点名称](mapLinks 中的原始 URL)`，iPhone 普通正文会显示苹果地图图标、地点名称和“在苹果地图中打开”。整段单个有效坐标链接才转换，行内链接、代码示例及非苹果地图链接继续按原有格式显示；历史记录复用同一渲染逻辑，不需要新的消息协议。

只有用户点击卡片时才使用 MapKit 打开地点或路线；生成回答、读取历史、展示卡片都不会自动跳转，不调用 maps.open。打开失败会提示检查是否安装苹果地图。卡片不是收藏成功回执；用户要写入苹果地图收藏时仍需在苹果地图内保存，不能使用 Whaleness 自有标记冒充。

卡片自身不申请定位、收藏读取或后台权限，点击使用消息中已有的目的地坐标。此轮接入 iOS 原生卡片；其他客户端仍能将标准 HTTPS 地图链接显示为普通链接。
