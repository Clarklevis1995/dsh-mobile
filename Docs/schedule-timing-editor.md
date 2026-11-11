# 定时任务执行时间编辑

Android 和 iOS 使用共享的 `ScheduleTimingEditor` 投影和编码规则，支持与官方 0.2.2-rc.1 外部 UI 一致的单次、每分钟、每小时、每天、每周、每月、自定义，以及重复间隔、秒级时间、IANA 时区、星期多选和月内日期。任务正文仍在紧凑的可滚动输入框内编辑。

## 已有网关协议可以覆盖

`0.2.2-rc.1` 网关的 `schedule-management-v2` 能力下，使用 `schedule-edit` 的 `patch.timing`，并附上原记录的 `expectedUpdatedAt`。无需新增消息类型，也无需修改网关服务端。

- 单次使用 `at`；每天/每周间隔为 1 时使用 `daily` / `weekly`，保留秒及毫秒。
- 其他引导式重复规则使用带 `DTSTART;TZID=...` 的 `rrule`，保留所选开始日期作为间隔锚点。日历规则不换算成固定秒数，跨夏令时由 Host 按当地时刻计算。
- 每月使用 `BYMONTHDAY=<日期>,-1;BYSETPOS=1`，与官方 UI 一样在短月落到月末。
- 自定义支持五字段 Cron 或完整 DTSTART/RRULE。RRULE 的时区直接在 DTSTART 中修改。
- 未修改执行时间时不发送 timing 字段；未识别的高级规则保留原文，单改标题/正文不会重建规则或丢失 COUNT、UNTIL 等条件。
- 验证重复间隔 1–10000、有效日期、秒格式、星期非空、月内日期、设备支持的 IANA 时区及单次任务必须为未来时间。RRULE 为整秒；存在非零毫秒时提示用户明确调整，不静默截断。
- Cron/RRULE 完整语法和下一次执行时间最终由 Host 校验；错误和 CAS 冲突保留编辑草稿。

示例：每两周周一、周三 09:30:45（柏林时间）。

```json
{
  "type": "schedule-edit",
  "requestId": "edit-1",
  "id": "task-1",
  "action": "update",
  "expectedUpdatedAt": "2026-11-06T01:00:00.000Z",
  "patch": {
    "timing": {
      "kind": "rrule",
      "rrule": "DTSTART;TZID=Europe/Berlin:20261109T093045\nRRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE"
    }
  }
}
```

## 验证

执行位置同样通过 `schedule-edit.patch.target` 修改：

- `session`：使用当前实际关联会话；`new_once` 尚未创建的预留 ID 不作为已有会话供选择。
- `new_once` / `new`：分别创建并复用会话、每次创建独立会话；两种模式均显示可搜索的工作区选择器。
- 选中的工作区用绝对路径写入 `target.create.cwd`，不把 `workspaceId` 传给不支持该字段的 Host 接口；“使用默认工作区”省略 cwd。
- 原有 cwd 不在当前目录时保留原路径，不能因列表缺项自动重置。
- 未修改目标时不发送 target；切换新建模式但工作区未变时保留 agentPreset，换工作区时按官方 UI 行为采用默认预设。
- 目标编辑需要 `schedule-management-v2`，旧网关继续支持原有标题、正文和时间编辑。

```json
{"target":{"kind":"new_once","create":{"cwd":"/projects/my-workspace"}}}
```

- `ScheduleTimingEditorTest`：共享编码、往返投影、无损保留、输入边界。
- `ScheduleTargetEditorTest`：执行位置编码、预留会话保护、工作区及预设保留策略。
- Android `ScheduledTaskEditTimingTest`：草稿初始化、单次任务和时区校验。
- Android `ScheduleTimingControlsDeviceTest`：窄屏菜单、星期选择、时区搜索和月度选项。
- iOS `ScheduleTimingEditorTests`：KMP 桥接、实际 schedule-edit/CAS 请求、深浅色窄屏渲染。
- `node scripts/test_schedule_timing_contract.mjs <官方 dsh-util-time/lib/index.js 路径>`：对官方发布包验证五种重复规则、月末、夏令时、隔周和 Cron 语义。

联调时在测试任务上修改规则并重新打开编辑页核对；生产任务无需为验证而触发或修改。
