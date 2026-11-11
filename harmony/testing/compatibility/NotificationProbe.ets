import { common } from '@kit.AbilityKit';
import { notificationManager } from '@kit.NotificationKit';
import { AgentNotifications } from '../core/AgentNotifications';
import { parseNotificationTarget } from '../core/NotificationTarget';

@Entry
@Component
struct Index {
  @State private permitted: boolean = false;
  @State private status: string = '';
  @State private lines: string[] = [];
  private notifications?: AgentNotifications;
  private context?: common.UIAbilityContext;
  private target: () => void = () => this.receivedTarget();
  aboutToAppear(): void {
    this.context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.notifications = new AgentNotifications(this.context); this.notifications.knownHost = id => id === 'probe-host';
    this.notifications.onChange = (enabled, status) => { this.permitted = enabled; this.status = status; };
    this.notifications.open(); this.context.eventHub.on('notificationTarget', this.target);
  }
  aboutToDisappear(): void { this.context?.eventHub.off('notificationTarget', this.target); }
  private receivedTarget(): void {
    const raw = AppStorage.get<string>('whaleness.notification-target'); const target = raw ? parseNotificationTarget(raw) : undefined;
    if (target?.host === 'probe-host' && target.session === 'probe-session') { this.lines = this.lines.concat(['PASS actual WantAgent click target']); }
    else { this.lines = this.lines.concat(['FAIL invalid notification target']); }
  }
  private async refresh(): Promise<void> {
    try {
      const rows = await notificationManager.getActiveNotifications(); const own = rows.filter(row => row.label === 'agent-events');
      if (own.some(row => row.content.normal?.title === '任务已完成' && row.content.normal?.text === '点击进入对应主机和会话查看')) {
        if (!this.lines.includes('PASS actual SDK notification privacy')) { this.lines = this.lines.concat(['PASS actual SDK notification privacy']); }
      }
    } catch (_) { this.lines = this.lines.concat(['FAIL actual SDK notification read']); }
  }
  build() {
    Column({ space: 16 }) {
      Text('系统通知 SDK 专项').fontSize(20)
      Text(this.permitted ? 'notification-enabled' : 'notification-disabled').id('probe-notification-status')
      Text(this.status).fontSize(13)
      Button('申请通知权限').id('probe-notification-enable').onClick(() => this.notifications?.setEnabled(true))
      Button('发布确定性自测通知').id('probe-notification-publish').enabled(this.permitted).onClick(() => this.notifications?.accept('probe-host', [{ key: 'ended:probe-session:1', sessionId: 'probe-session', kind: 'ended', failed: false }], false))
      Button('读取本应用通知').id('probe-notification-refresh').onClick(() => this.refresh())
      ForEach(this.lines, (line: string) => { Text(line).fontSize(14).fontColor(line.startsWith('FAIL') ? '#DD2222' : '#111111') })
    }.padding(20).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
