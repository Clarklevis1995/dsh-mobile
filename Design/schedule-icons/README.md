# 定时任务图标

- 来源：Lucide 0.468.0 `calendar-clock`，https://lucide.dev/icons/calendar-clock 。
- 原始 SVG：https://unpkg.com/lucide-static@0.468.0/icons/calendar-clock.svg 。
- 图形为日历与时钟，表示安排自动化任务。
- Android 通过 SDK Svg2Vector 转换，currentColor 使用黑色占位并由主题 tint 着色；iOS 使用同一 SVG 模板图像。
- 许可见 LICENSE；原始 SVG SHA-256：`7d0886060d87d9aa86483526620468f8e3855202d7708d0fb3c44c871b9fa19c`。

## 垃圾桶

- `trash.svg` 为项目自绘的统一资源，包含桶盖、提手和三条竖线。
- iOS `ScheduleTrash.imageset/trash.svg` 与此文件逐字节一致；Android `ic_schedule_trash.xml` 使用相同路径。
- 两端画布均为 24×24，线宽 1.7，圆头、圆角连接；可见轮廓约 15.7×19.7。
- 定时任务更多菜单和卡片删除入口统一使用 24 点／dp，不使用系统 SF Symbols 或其他形似的替代图标。
- 图标继承按钮语义颜色；禁用态由平台菜单按原有规则降低透明度。

## 查看运行轨迹

- `history.svg` 为项目统一的时钟回转箭头轮廓，两端同路径、线宽 1.7，画布与显示尺寸均为 24×24。
- `chevron-right.svg` 与 Android 既有 `ic_chevron_right.xml` 的路径一致，iOS 使用同源 `ScheduleChevronRight`，这一行两端显示尺寸均为 20×20。
- 入口文本统一为 15 点／sp 常规字重，图标与文本间隔 12，行高至少 48，垂直内边距 8，不增加行首缩进。

## 关联会话

- iOS 使用 `bubble.left.and.bubble.right` Regular；Android 使用同名符号经 Figma `SF Pro Regular` 17pt 转轮廓导出的 `conversation-ios.svg`，不再使用形似的手绘路径。
- 导出来源：既有图标文件 https://www.figma.com/design/Dvs7ruDuthX1vtF1rDQyFc ，临时导出节点已移除。
- Android `ic_schedule_conversation.xml` 保留相同填充路径，24×19 视口，显示为 24×19dp，图文间距 8dp，文字 17sp。
- 运行轨迹底栏使用中性灰毛玻璃覆盖到导航栏安全区，滚动内容仅从模糊材质下透出；旧系统或减少特效时使用不透明同色底栏，避免正文穿透按钮。

## 编辑铅笔

- iOS 侧滑编辑使用原生 `pencil` Regular，默认 17pt 字体规格。Android 使用同名符号经 Figma 的 `SF Pro Regular` 17pt 转轮廓导出的 `pencil-ios.svg`，为实心斜铅笔，不带底部横线。
- 导出来源：既有图标文件 https://www.figma.com/design/Dvs7ruDuthX1vtF1rDQyFc ，临时导出节点已移除。
- Android `ic_schedule_edit.xml` 保留同一填充路径，14×14 视口与显示尺寸；在 50dp 编辑按钮中居中显示，使用白色 tint。
