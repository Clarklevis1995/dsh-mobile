"""只读实际NotificationKit通知列表；生产通知过滤、发布与导航全部不替换。"""
def instrument(app):
    p=app/'entry/src/main/ets/pages/Index.ets';s=p.read_text()
    if "id('test-notification-inspect')" in s:return
    s="import { notificationManager } from '@kit.NotificationKit';\n"+s
    s=s.replace('@Entry\n', 'interface TestNotice { id: number; title: string; text: string; }\n@Entry\n', 1)
    s=s.replace('  @State private startupPhase:',"  @State private noticeInspection: string = '[]';\n  private async inspectTestNotices(): Promise<void> {\n    const rows = await notificationManager.getActiveNotifications();\n    this.noticeInspection = JSON.stringify(rows.filter(row => row.label === 'agent-events').map((row): TestNotice => ({ id: row.id ?? -1, title: row.content.normal?.title ?? '', text: row.content.normal?.text ?? '' })));\n  }\n  @State private startupPhase:",1)
    marker="    Column() {\n      if (this.errorText)";assert marker in s
    s=s.replace(marker,"    Column() {\n      Text(this.noticeInspection).id('test-notification-inspect').fontSize(1).height(10).width('100%').onClick(() => this.inspectTestNotices())\n      if (this.errorText)",1);p.write_text(s)
