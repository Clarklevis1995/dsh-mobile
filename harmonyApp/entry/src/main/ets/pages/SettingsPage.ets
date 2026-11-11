import { MarkdownBody } from '../components/MarkdownBody';
import { MINIMUM_GATEWAY_VERSION, GATEWAY_RELEASE_ROOT, harmonyReleaseNotes } from '../core/ReleaseInfo';
import { SettingsSection } from '../components/SettingsSection';
import { SettingsData } from '../core/SettingsData';
import { SettingsGatewayRow } from '../components/SettingsGatewayRow';
import { FontSizePanel } from '../components/FontSizePanel';
import { PreferenceMenuRow } from '../components/PreferenceMenuRow';
import { Appearance, iconTint } from '../core/Appearance';
import { AppearanceData, Command } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct SettingsPage {
  @Prop notificationsEnabled: boolean = false;
  @Prop notificationStatus: string = '';
  onNotifications: (enabled: boolean) => void = () => {};
  @ObjectLink a: Appearance;
  @ObjectLink snapshot: SettingsData;
  @Prop section: string = 'root';
  @Prop active: boolean = true;
  onOpenSection: (section: string) => void = () => {};
  @Prop version: string = '';
  @Prop buildCode: number = 0;
  @Prop gatewayVersion: string = '';
  onNotes: () => void = () => {};
  onLink: (url: string) => void = () => {};
  onAction: (command: Command) => void = () => {};
  onConnection: () => void = () => {};
  onPing: () => void = () => {};
  aboutToAppear(): void { if (this.section === 'root' && this.snapshot.connected) { this.onAction(new Command('controls-defaults')); this.onAction(new Command('plugins-open')); } }
  onBack: () => void = () => {};
  onSchedules: () => void = () => {};
  onPlugins: () => void = () => {};
  onAgents: () => void = () => {};
  onDefaults: () => void = () => {};
  onPermission: (value: string) => void = () => {};
  onHosts: () => void = () => {};
  onForgetAll: () => void = () => {};
  onChange: (value: AppearanceData) => void = () => {};

  private set(style?: string, language?: string, follow?: boolean, scale?: number, motion?: boolean): void {
    const next = JSON.parse(JSON.stringify(this.a.data)) as AppearanceData;
    if (style !== undefined) { next.style = style; }
    if (language !== undefined) { next.language = language; }
    if (follow !== undefined) { next.fontFollowsSystem = follow; }
    if (scale !== undefined) { next.fontScale = Math.max(0.8, Math.min(1.5, scale)); }
    if (motion !== undefined) { next.reduceMotion = motion; }
    this.onChange(next);
  }
  @Builder private row(title: string, detail: string, id: string, action: () => void, enabled: boolean = true) {
    Row({ space: 8 }) {
      Text(this.a.t(title)).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
      Text(detail).fontSize(this.a.font(14)).fontColor(this.a.fg).opacity(.52).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      Image($rawfile('icons/chevron_right.svg')).width(12).height(12).colorFilter(iconTint(this.a.fg)).opacity(.3)
    }.padding({ left: 16, right: 16, top: 15, bottom: 15 }).width('100%').constraintSize({ minHeight: 56 })
      .backgroundColor(this.a.surface).id(id).opacity(enabled ? 1 : .4).enabled(enabled).onClick(action)
  }
  private title(): string {
    if (this.section === 'notes') { return this.a.english ? 'What’s new' : '本版本新功能'; }
    if (this.section === 'font') { return this.a.t('字体大小'); }
    if (this.section === 'privacy') { return this.a.t('隐私'); }
    if (this.section === 'about') { return this.a.t('关于'); }
    return this.a.t('设置');
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.title(), onBack: this.onBack })
      if (this.section === 'notes') {
        Scroll() {
          MarkdownBody({ a: this.a, text: harmonyReleaseNotes(this.a.english, this.version), prefix: 'release-notes', active: this.active })
            .id('release-notes-content').padding(20).width('100%').constraintSize({ maxWidth: 720 })
        }.layoutWeight(1).width('100%').align(Alignment.Top)
      } else if (this.section === 'font') {
        FontSizePanel({ a: this.a, onFollow: value => this.set(undefined, undefined, value),
          onScale: value => this.set(undefined, undefined, false, value) }).layoutWeight(1)
      } else {
      Scroll() {
        Column({ space: 22 }) {
          if (this.section === 'root') {
            SettingsSection({ a: this.a, title: '新会话默认配置', footer: '与 WebUI 使用同一份部署级设置。修改只影响之后新建的会话，运行中的会话保持启动时的配置。' }) {
              SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'agent', controlId: 'settings-agent-presets', onPress: this.onAgents })
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'model', controlId: 'settings-defaults', onPress: this.onDefaults })
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'permission', controlId: 'settings-default-permission', onPermission: this.onPermission })
            }
            SettingsSection({ a: this.a, title: 'Mobile Gateway' }) {
              SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'endpoint', controlId: 'settings-hosts', onPress: this.onHosts })
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'status', controlId: 'settings-manage-hosts', onPress: this.onHosts })
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'connection', controlId: 'settings-connection', onPress: this.onConnection })
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'ping', controlId: 'settings-ping', onPress: this.onPing })
            }
            if (this.snapshot.hostSnapshot) {
              SettingsSection({ a: this.a, title: 'DSH Host' }) {
                SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'host-version', controlId: 'settings-host-version' })
                Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
                SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'host-sessions', controlId: 'settings-host-sessions' })
                if (this.snapshot.hostSnapshot?.cwd) {
                  Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
                  SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'host-cwd', controlId: 'settings-host-cwd' })
                }
              }
            }
            SettingsSection({ a: this.a, title: '外观' }) {
              PreferenceMenuRow({ a: this.a, kind: 'style', onChoose: value => this.set(value) })
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              PreferenceMenuRow({ a: this.a, kind: 'language', onChoose: value => this.set(undefined, value) })
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              SettingsGatewayRow({ a: this.a, snapshot: this.snapshot, kind: 'font', controlId: 'settings-font', onPress: () => this.onOpenSection('font') })
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              Row() {
                Text(this.a.t('减少动态效果')).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
                Toggle({ type: ToggleType.Switch, isOn: this.a.data.reduceMotion }).id('settings-motion').onChange(value => this.set(undefined, undefined, undefined, undefined, value))
              }.padding(16).backgroundColor(this.a.surface).width('100%').constraintSize({ minHeight: 54 })
            }
            SettingsSection({ a: this.a, title: '本地存储' }) {
              this.row('存储空间', '', 'settings-storage', () => this.onOpenSection('storage'))
            }
            SettingsSection({ a: this.a, title: '主机扩展' }) {
              this.row('插件', '', 'settings-plugins', this.onPlugins)
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              this.row('定时任务', '', 'settings-schedules', this.onSchedules)
            }
            SettingsSection({ a: this.a, title: '关于' }) {
              this.row('当前版本', this.version, 'settings-about', () => this.onOpenSection('about'))
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              this.row(this.a.english ? 'Required gateway version' : '要求网关版本', '≥ ' + MINIMUM_GATEWAY_VERSION, 'settings-required-gateway', () => this.onLink(GATEWAY_RELEASE_ROOT + '/releases'))
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              this.row(this.a.english ? 'Connected gateway version' : '已连接网关版本', this.snapshot.connected ? this.gatewayVersion || this.a.t('未提供') : this.a.t('未连接'), 'settings-gateway-version', () => this.onLink(GATEWAY_RELEASE_ROOT + '/releases'))
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              this.row(this.a.english ? 'What’s new' : '本版本新功能', this.a.english ? 'Release notes' : '查看发布说明', 'settings-release-notes', this.onNotes)
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              this.row(this.a.english ? 'Check for updates' : '检查更新', '', 'settings-updates', () => this.onOpenSection('updates'))
              Divider().strokeWidth(.5).color(this.a.fg).opacity(.12).margin({ left: 16, right: 16 })
              this.row('隐私', '', 'settings-privacy', () => this.onOpenSection('privacy'))
            }
          } else if (this.section === 'privacy') {
            Column({ space: 18 }) {
              Row({ space: 12 }) {
                Text(this.a.t('任务通知')).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
                Toggle({ type: ToggleType.Switch, isOn: this.notificationsEnabled }).id('notifications-enabled').accessibilityText(this.a.t('任务通知')).onChange(this.onNotifications)
              }.width('100%').constraintSize({ minHeight: 48 })
              Text(this.a.t(this.notificationStatus)).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')

              Text(this.a.english ? 'Pairing codes are never saved. Tokens and device identities stay in system secure storage. Conversation data comes from your connected host. Stable history and text drafts are stored in the system encrypted local database, isolated by host. Clear them in Storage settings.' :
                '配对码不会保存。Token 和设备身份保存在系统安全存储中。聊天内容来自你连接的主机，稳定历史和文字草稿按主机隔离，保存在系统加密的本地数据库中，可在存储空间中清理。')
                .fontSize(this.a.font(15)).fontColor(this.a.fg).lineHeight(this.a.font(25)).width('100%')
              Button(this.a.t('删除全部主机')).id('forget-all-hosts').fontColor('#D34C53').backgroundColor(this.a.bg).height(48).width('100%').onClick(this.onForgetAll)
            }.padding(20).backgroundColor(this.a.surface).borderRadius(26).width('100%')
          } else if (this.section === 'about') {
            Column({ space: 16 }) {
              Image($rawfile('branding/wordmark.svg')).width(245).height(58).backgroundColor('#06172B').borderRadius(12)
              Text('Whaleness Harness').fontSize(this.a.font(23)).fontWeight(FontWeight.Bold).fontColor(this.a.fg)
              Text(this.a.t('应用版本') + ' ' + this.version).fontSize(this.a.font(15)).fontColor(this.a.fg)
              Text(this.a.t('构建版本') + ' ' + this.buildCode).fontSize(this.a.font(13)).fontColor(this.a.muted)
              Text('MIT License').fontSize(this.a.font(13)).fontColor(this.a.muted)
            }.padding(24).backgroundColor(this.a.surface).borderRadius(26).width('100%')
          }
        }.padding({ left: 20, right: 20, top: 8, bottom: 30 }).width('100%').constraintSize({ maxWidth: 720 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
      }
    }.width('100%').height('100%').backgroundColor(this.section === 'font' && !this.a.dark ? '#F7F7F8' : this.a.settingsBg)
  }
}
