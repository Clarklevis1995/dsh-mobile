# 定时任务 Android / iOS 对齐记录

范围：当前定时任务模块的列表、导航操作、筛选、编辑、执行位置、时间规则和运行轨迹。以现有 iOS 设计为基准。

| 区域 | 两端一致的行为和应用内布局 |
| --- | --- |
| 导航与搜索 | 居中标题、独立搜索和三点更多按钮；搜索框位于导航栏下方，关闭时清空关键词 |
| 状态栏 | 已开启、已暂停、已结束、全部；默认全部，32 点高的分段控件，左右留白 20；用户切换 Tab 时立即反馈一次轻量选择触感，重复点击当前项、刷新和状态恢复不反馈，遵循系统触感设置 |
| 更多菜单 | 添加定时任务在上，红色垃圾桶及删除已结束任务在下；删除二次确认，离线/忙碌时禁用 |
| 列表 | 加载、错误重试、空状态、无匹配提示、分页和下拉刷新 |
| 卡片 | 圆角 28，标题 21、正文 16、摘要最多三行；日期行展开详情；详情标签宽 92、字号 14 |
| 卡片操作 | 左滑编辑/删除，右滑执行记录/立即执行/暂停或恢复；卡片外不再显示额外操作行 |
| 任务状态 | 共用 ScheduleTaskPresentation，暂停单独显示且允许编辑，结束与未知状态不允许编辑 |
| 编辑 | 固定取消/保存栏，可滚动表单，填充圆角输入框，正文区域 176 高、内部滚动；保存中锁定编辑及关闭 |
| 执行位置 | 当前实际关联会话、新建并复用、每次新建；新建模式均提供可搜索的工作区 Bottom Sheet |
| 时间 | 共用规则投影与编码；单次/分钟/小时/日/周/月/自定义、间隔、秒、星期及月内日期 |
| 选择器 | 时间、重复间隔、工作区和时区点击后弹出 Bottom Sheet；时间和间隔确认才写入，取消不修改；间隔滚轮范围 1–10000；时区支持搜索 |
| 设置项与焦点 | 执行时间普通设置行高统一为 52 点，分隔线不增加行距；点击非编辑区域收键盘，输入区点击和选区保留焦点，按钮仍正常响应 |
| 选择菜单对齐 | 执行位置和重复菜单沿用设置页的选择组件；选中勾位于左侧，未选中项预留同宽空间，文字列对齐 |
| 轨迹 | Bottom Sheet 标题和关闭、记录摘要与展开、分页、失败重试、保留范围提示、关联会话入口 |
| 业务边界 | 未修改的 timing/target 不重发；CAS 校验、失败保留草稿、默认工作区不误用当前浏览的工作区 |
| 配色 | 定时任务单独使用系统白/黑背景及灰色分组底色，两端均支持深浅色 |

Android 菜单继续使用项目 DshMenu 系列；独立窗口使用 DshMenuHost，Bottom Sheet 使用 DshMenuColumnHost。

## 验证依据

- 共享 ScheduleTimingEditorTest、ScheduleTargetEditorTest、ScheduleTaskPresentationTest：规则、执行目标和任务状态。
- Android ScheduledTaskEditTimingTest / MobileScheduledTaskTest：字段回显与时间边界。
- Android ScheduleTimingControlsDeviceTest：新建模式、工作区/时区搜索、秒轮盘、星期、月度规则。
- Android ScheduledTaskParityDeviceTest：暂停卡片双向侧滑、动作后展开、深浅色真实渲染。
- 两端 ScheduledTasksToolbar / AdaptiveLayoutDeviceTest：窄屏四项 Tab、搜索、更多菜单。
- iOS ScheduleTimingEditorTests、ScheduledTaskCardPresentationTests、ScheduledTaskHistoryTests：请求、卡片展开、深浅色和轨迹分页。

这些检查使用模拟任务和开发版，不触发生产任务。系统状态栏、字体栅格、原生日期控件和 iOS Liquid Glass 属于系统渲染，不能把跨平台截图宣称为逐像素完全相同。
