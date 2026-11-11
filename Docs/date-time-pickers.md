# Android 公共日期与时间选择器

实现位于 `androidApp/src/main/kotlin/com/clarklevis/dsh/android/ui/CalendarPicker.kt` 和 `DateTimePickers.kt`。

- `DshCalendar`：共用日历、月份翻页、年月滚轮、周日开头的星期、今天与选中态。调用方可配置时区、首尾日期、选中色和行高。
- `DshDatePickerField`：日期胶囊入口与锚定日历浮层，沿用 DshMenu 的边界、毛玻璃及关闭行为。
- `DshNumberWheel`：共用懒加载、中心吸附、透视渐隐、轻触定位、触感与辅助功能数值调整。跨刻度使用 `SegmentTick`，由 AndroidX 兼容旧系统并遵循系统触感设置；首帧与相同刻度重绘不震动。滚轮不循环，首尾剩余手势不误关面板。
- `DshClockPicker`：共用时／分／秒选择与毫秒保留开关，取消不回填。
- 多列滚轮的列间距为 12dp，选中胶囊另有两侧各 2dp 留白；时／分／秒标题使用 12sp、16sp 行高与 `#8E8E93`，标题和数字滚轮间距 12dp。年月选择使用相同列间距。
- `DshPickerSheet`、`DshPickerHeader`：共用面板高度、安全区与胶囊操作栏。任务使用内缩玻璃面板；日志使用白色系统风格面板与拖动条，两端均适配深色。

定时任务以任务 IANA 时区解释 ISO 日期，日期选择不会先转换为设备时区。日历浮层使用中性选中态，与 iOS 的紧凑日期控件一致。

紧凑日历的日期高亮在原点击范围内向内留白 4dp，避免“今天”和“选中日期”相邻时圆形贴合；文字位置、星期列和点击范围不变。日志全宽日历维持原有圆形尺寸。

日志页的 `LogDateTimeSheet` 仅负责范围和草稿适配：日期限制在本机留存范围内，选中日期使用蓝色，保留范围提示。时间将 `logHoursOnDay` 生成的整点时间戳映射到共用滚轮，确认后仍归一到小时；保持夏令时边界和左闭右开导出规则，不增加分钟或秒数选项。

不再保留日志专属日历／滚轮实现，也不再使用 Android 的 DatePickerDialog、TimePickerDialog 或 NumberPicker。

验证覆盖：闰日、月份与日期范围、年月滚轮、跨时区日期、取消回滚、1～10000 间隔边界、时分秒、日志整小时回填、日志导出与分享拦截，以及深浅色截图。
