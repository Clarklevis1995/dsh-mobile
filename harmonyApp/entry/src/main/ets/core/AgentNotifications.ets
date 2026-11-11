import { AgentNotice, NotificationTarget } from './NotificationTypes';
import { notificationManager } from '@kit.NotificationKit';
import { common, wantAgent } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';

interface SeenNotice { key: string; id: number; host: string; }

/** 本地通知仅在进程仍接收事件时工作；不冒充进程终止后的远程推送。 */
export class AgentNotifications {
  knownHost: (host: string) => boolean = () => false;
  onChange: (enabled: boolean, status: string) => void = () => {};
  translate: (value: string) => string = value => value;
  private context: common.UIAbilityContext;
  private handle?: preferences.Preferences;
  private enabled: boolean = false;
  private recent: SeenNotice[] = [];
  private nextId: number = 1;
  private revision: number = 0;
  private writeQueue: Promise<void> = Promise.resolve();
  constructor(context: common.UIAbilityContext) { this.context = context; }
  async open(): Promise<void> {
    try {
      this.handle = await preferences.getPreferences(this.context, { name: 'whaleness.notifications.v1' });
      this.enabled = await this.handle.get('enabled', false) === true;
      const text = await this.handle.get('seen', '[]');
      if (typeof text === 'string') {
        try { const rows = JSON.parse(text) as SeenNotice[]; if (Array.isArray(rows)) { this.recent = rows.filter(row => typeof row.key === 'string' && row.key.length < 2500 && typeof row.host === 'string' && Number.isSafeInteger(row.id)).slice(-256); } } catch (_) {}
      }
      this.nextId = this.recent.reduce((max: number, row: SeenNotice) => Math.max(max, row.id), 0) + 1;
      const permitted = await notificationManager.isNotificationEnabled();
      this.onChange(this.enabled, permitted ? '通知仅在后台连接仍存活时提供提醒，回到前台会同步最新状态' : '系统尚未允许通知，可点击开关申请授权');
    } catch (_) { this.enabled = false; this.onChange(false, '系统通知暂不可用，请使用应用内待处理列表'); }
  }
  async setEnabled(enabled: boolean): Promise<void> {
    const ticket = ++this.revision;
    try {
      if (!this.handle) { throw new Error('notification-preferences-unavailable'); }
      if (enabled) {
        await notificationManager.requestEnableNotification(this.context);
        if (!await notificationManager.isNotificationEnabled()) { throw new Error('not-permitted'); }
      }
      if (ticket !== this.revision) { return; }
      this.enabled = enabled; await this.handle?.put('enabled', enabled); await this.handle?.flush();
      if (!enabled) { await Promise.all(this.recent.map(row => notificationManager.cancel(row.id, 'agent-events').catch(() => {}))); }
      this.onChange(enabled, enabled ? '通知不包含聊天正文；后台连接可能被系统暂停，回到前台会同步状态' : '已关闭任务通知，可继续使用应用内待处理列表');
    } catch (_) { if (ticket === this.revision) { this.enabled = false; this.onChange(false, '未获得通知权限，请在系统设置中检查，应用内待处理列表仍可用'); } }
  }
  accept(host: string, notices: AgentNotice[], foreground: boolean): void {
    notices.forEach(notice => {
      const key = host + ':' + notice.key;
      if (this.recent.some(row => row.key === key)) { return; }
      if (this.nextId > 2147480000) { this.nextId = 1; }
      const row: SeenNotice = { key: key, id: this.nextId++, host: host }; this.recent.push(row);
      if (this.recent.length > 256) { const old = this.recent.shift(); if (old) { notificationManager.cancel(old.id, 'agent-events').catch(() => {}); } }
      this.persist();
      if (this.enabled && !foreground) { this.publish(row, notice, this.revision); }
    });
  }
  private persist(): void {
    const text = JSON.stringify(this.recent);
    this.writeQueue = this.writeQueue.then(async () => { await this.handle?.put('seen', text); await this.handle?.flush(); }).catch(() => { this.onChange(this.enabled, '通知去重记录保存失败，请检查本地存储'); });
  }
  private async publish(row: SeenNotice, notice: AgentNotice, ticket: number): Promise<void> {
    try {
      if (!this.knownHost(row.host) || ticket !== this.revision || !this.enabled || !await notificationManager.isNotificationEnabled()) { return; }
      const target: NotificationTarget = { host: row.host, session: notice.sessionId };
      const agent = await wantAgent.getWantAgent({ wants: [{ bundleName: this.context.abilityInfo.bundleName, abilityName: 'EntryAbility',
        parameters: { whalenessNotificationTarget: JSON.stringify(target) } }], actionType: wantAgent.OperationType.START_ABILITY,
        requestCode: row.id, wantAgentFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG] });
      if (!this.knownHost(row.host) || ticket !== this.revision || !this.enabled) { return; }
      const title = notice.kind === 'ended' ? (notice.failed ? '任务已结束' : '任务已完成') : '有事项需要处理';
      const request: notificationManager.NotificationRequest = { id: row.id, label: 'agent-events',
        content: { notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: { title: this.translate(title), text: this.translate('点击进入对应主机和会话查看'), additionalText: 'Whaleness' } }, wantAgent: agent,
        isUnremovable: false, autoDeletedTime: Date.now() + 86400000 };
      await notificationManager.publish(request);
      if (!this.knownHost(row.host) || ticket !== this.revision || !this.enabled) { await notificationManager.cancel(row.id, 'agent-events').catch(() => {}); }
    } catch (_) { this.onChange(this.enabled, '通知未能显示，可在应用内查看任务与待处理事项'); }
  }
  async removeHost(host: string): Promise<void> {
    const removed = this.recent.filter(row => row.host === host); this.recent = this.recent.filter(row => row.host !== host); this.persist();
    await Promise.all(removed.map(row => notificationManager.cancel(row.id, 'agent-events').catch(() => {})));
  }
}
