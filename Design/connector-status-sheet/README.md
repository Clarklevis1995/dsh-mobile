# 连接器状态与能力面板设计

[Figma 完整流程](https://www.figma.com/design/0qYyEvoG07J77OKPPnseTp?node-id=23-977) · [两端面板并排预览](https://www.figma.com/design/0qYyEvoG07J77OKPPnseTp?node-id=24-318)

## 交互

1. 状态页保留网关注册状态、已确认数量、最近确认时间和刷新/重新注册按钮。
2. “网关已登记的能力”改成独立的一行入口，不再在状态页内展开连接器列表。
3. 点击入口打开 Bottom Sheet，逐项显示平台图标、连接器名称、已登记能力数和当前权限状态。
4. 点击连接器进入同一面板内的详情层级，返回只回到连接器列表；关闭或点遮罩返回状态页，不叠加第二张 Sheet。
5. 面板标题和拖动条固定，列表在可用高度内滚动，底部保留系统安全区。实际实施时核验持续拖动时背景与标题同步。

## 平台与素材

- iOS：复用 App 的 ConnectorCalendar、ConnectorReminders、ConnectorLocation、ConnectorPhotos、ConnectorMusic、ConnectorHealth 六份系统应用素材；闹钟复用 SF Symbols 的 alarm.fill 语义图形。
- Android：复用项目已接入的六款本机能力矢量图标；不增加健康，也不将 Whaleness 提醒或本机音频库标为第三方云端服务。
- 两端统一中性灰 #F2F2F7、白色分组、顶部圆角 32、44 点 Logo、66 点连接器行、左右 20 点留白；不使用 Material 紫色。
- 能力详情复用 Figma iOS 27 标准 Row 组件，中文使用与既有设计一致的 Noto Sans SC，数字/英文使用 SF Pro。

## 数据语义

- 示例 iOS 为 7 类、28 项能力；Android 为 6 类、22 项能力，均取自当前实现的最大可用能力清单。运行时必须使用网关实际返回的 modules/tools 和数量，不写死。
- “网关已注册”仅说明登记成功。手机未开启、主机未允许、系统未授权、设备不支持或网关未接入须分别显示，不能统一显示已允许。
- 详情的绿色勾选表示示例音乐模块已允许；未开启的模块详情应改为相应的禁用状态，不冒充可执行。
- 新版 Android 的地图入口只生成卡片，跳转仍由用户点击卡片选择应用后触发。

设计稿为可编辑静态画面，共六个画面；两端应用已接入对应交互。

## 应用实现

- iOS：`ConnectorsView.swift` 的状态页保留能力入口，使用原生 Sheet 展示列表和详情，按内容数量与辅助字号计算高度，超出可用高度时滚动。
- Android：`ConnectorCapabilitiesSheet.kt` 使用原生 ModalBottomSheet，中性灰面板、平台矢量图标，标题固定，内容独立滚动；列表边界截住剩余拖动和惯性，避免带动面板。
- 所有数量与工具名称取自当前连接的网关快照。详情持续观察本机开关和主机权限，未允许时显示灰色状态；断线或登记失效时关闭面板，移除旧入口。
- 只读展示登记状态，不会请求系统授权、修改主机权限或触发任何工具调用。

## 验证

- iOS：6 项注册/真实系统面板检查和 1 项连接器入口 UI 流程通过。
- Android：3 项设备回归通过，覆盖能力入口、平台图标、详情/系统返回、实时撤权、断线移除、持续按住上下拖动及大字号长列表连续滑动。
- `ios-capabilities-*.png` 与 `android-capabilities-*.png` 为模拟器实际截图，包含明暗模式和撤权状态。

## 文件

- preview.png：两端 Bottom Sheet 并排预览。
- full-flow.png：两端各自的状态入口、连接器列表、音乐能力详情。
- figma-design.js：生成画面的 Figma Plugin API 脚本；依赖当前文件中既有图标组件和颜色令牌。
