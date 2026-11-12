# 通用地图卡片与平台适配器

## 用户流程

点击聊天中的地图卡片后，先展示 Bottom Sheet，再选择已安装的地图应用。没有安装任何支持的应用时仍可选择网页地图。点击卡片选择应用后的默认动作是打开“从当前位置到目的地”的路线。普通地点卡片默认驾车；原有路线卡片保留步行、公交或驾车方式。供应商协议底层仍支持查看地点，但卡片入口不再只标点。由目标地图确认或开始导航，不将成功打开应用表述为已开始导航。

- Android：高德、百度、谷歌。
- iOS：苹果、高德、百度、谷歌。苹果地图卸载后也不显示。
- 腾讯地图按本次用户决定暂不接入，没有占位项或 Key 提示。
- 每次打开面板、从外部应用返回时重新检测安装状态；点击时再次处理卸载、停用等打开失败。
- 不下载插件代码。插件是随 App 编译的供应商适配器，运行时按安装情况筛选。

## 协议 v1

网关 `mapLinks` 在原有 `url` 之外提供 `cardURL` 和结构化 `destination`，Agent 优先原样输出独立段落 `[地点名称](cardURL)`。`url` 继续保留原来的苹果/谷歌地址，兼容其他消费者。

```json
{
  "v": 1,
  "latitude": 31.2304,
  "longitude": 121.4737,
  "coordinateSystem": "wgs84",
  "name": "上海博物馆",
  "mode": "directions",
  "transport": "walking"
}
```

`cardURL` 将以上字段编码为 `whaleness-map://place?v=1&latitude=...&longitude=...&coordinateSystem=wgs84&name=...&mode=directions&transport=walking`。名称按 UTF-8 百分号编码，禁止拼接未编码的用户文本。

v1 仅接受显式 WGS-84，不把 GCJ-02、BD-09 当作 WGS-84。接入新的数据源时必须先确认坐标系，不能只更换声明。高德 URI 使用 `dev=1`，百度使用 `coord_type=wgs84`，由供应商处理转换；App 不引入近似反向坐标转换。历史苹果、谷歌坐标链接继续按原坐标语义识别，不能反向猜测链接中的非标准坐标。

解析逻辑放在 KMP `SharedMapLinks`：坐标范围和有限数检查、字段白名单、协议版本、重复参数、URL 凭据、端口、片段及非法 UTF-8 检查。仅支持可完整验证的坐标链接；短链、名称检索链接和其他第三方任意 scheme 不提升为卡片。旧苹果地点/路线链接与谷歌地点/路线链接均可在两端转成同一模型。

## 适配层

KMP `SharedMapLinks.openURL` 集中维护供应商的 URI 和参数差异；平台注册表只负责应用身份、安装查询和打开行为。

- Android：`AndroidMapAppPlugin` 使用固定包名 + 显式 Intent，Manifest `<queries>` 仅声明这三个包名；消息不能传入包名或组件。Bottom Sheet 使用原生 Compose ModalBottomSheet，接入 DshMenuHost。
- iOS：`IOSMapAppPlugin` 使用 `canOpenURL` 检测，Info.plist 声明 maps / iosamap / baidumap / comgooglemaps 查询白名单；原生 SwiftUI sheet 显示结果，`UIApplication.open` 回调失败时留在面板供重选。
- iOS 面板以内容实际尺寸更新高度，首帧按应用行数估算，不再默认占用 500 点。使用 `onGeometryChange` 直接读取排版后的内容高度；系统负责底部安全区，内容超过可用高度时由 ScrollView 滚动。已在真实系统 Sheet 中验证 0、1、4 个应用；单应用旧实现会停在约 534 点，新实现收紧到约 300 点（包含安全区）。
- 网页入口保留路线模式和出行方式，不再把路线降级成地点检索。打开网页地图可能由系统交给关联的地图 App。

Android 的 `maps.open` 只返回 `status=ready` 与目的地数据，网关附加 `mapLinks`；不安排 Intent 或浏览器跳转，用户点击卡片后再选择应用。`ready` 不表示应用已打开或导航已开始。iOS 与旧版 Android 的 `handoff-requested` 回执继续兼容。手机和网关需一起更新，以支持 `ready` 回执与卡片输出。

## 验证与真机范围

自动检查包括协议往返、特殊字符、历史链接、拒绝不可信 URL、供应商 scheme/坐标系/路线参数、Android 安装筛选/面板弹出与返回、iOS 插件筛选和卡片渲染。网关检查通用卡片在两平台一致且旧链接保留。

真机需分别安装各地图，验收地点、驾车/步行/公交路线、境内与境外目的地、应用卸载后重选。模拟器和 URI 参数测试不能代替供应商当前版本的实际调起测试。

## 官方协议

- [高德 Android 路线](https://lbs.amap.com/api/amap-mobile/guide/android/route)
- [高德 iOS 路线](https://developer.amap.com/api/amap-mobile/guide/ios/route)
- [百度 Android](https://lbsyun.baidu.com/docs/webapi?title=mapadjustment%2Furi%2Fandriod)
- [百度 iOS](https://lbsyun.baidu.com/docs/webapi?title=mapadjustment%2Furi%2Fios)
- [Google Maps iOS](https://developers.google.com/maps/documentation/urls/ios-urlscheme)
