import { FontSizePanel } from '../components/FontSizePanel';
import { PreferenceMenuRow } from '../components/PreferenceMenuRow';
import { Appearance, iconTint } from '../core/Appearance';
import { AppearanceData } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct SettingsPage {
  @Prop notificationsEnabled: boolean = false;
  @Prop notificationStatus: string = '';
  onNotifications: (enabled: boolean) => void = () => {};
  @ObjectLink a: Appearance;
  @Prop section: string = 'root';
  onOpenSection: (section: string) => void = () => {};
  @Prop version: string = '';
  @Prop buildCode: number = 0;
  @Prop hostName: string = '';
  @Prop status: string = '';
  onBack: () => void = () => {};
  onSchedules: () => void = () => {};
  onPlugins: () => void = () => {};
  onAgents: () => void = () => {};
  onDefaults: () => void = () => {};
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
  @Builder private row(title: string, detail: string, id: string, action: () => void) {
    Row({ space: 12 }) {
      Text(this.a.t(title)).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
      Text(detail).fontSize(this.a.font(14)).fontColor(this.a.muted).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      Image($rawfile('icons/chevron_right.svg')).width(16).height(16).colorFilter(iconTint(this.a.muted))
    }.padding({ left: 18, right: 18, top: 18, bottom: 18 }).width('100%').constraintSize({ minHeight: 56 })
      .backgroundColor(this.a.surface).id(id).onClick(action)
  }
  private title(): string {
    if (this.section === 'font') { return this.a.t('字体大小'); }
    if (this.section === 'privacy') { return this.a.t('隐私'); }
    if (this.section === 'about') { return this.a.t('关于'); }
    return this.a.t('设置');
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.title(), onBack: this.onBack })
      if (this.section === 'font') {
        FontSizePanel({ a: this.a, onFollow: value => this.set(undefined, undefined, value),
          onScale: value => this.set(undefined, undefined, false, value) }).layoutWeight(1)
      } else {
      Scroll() {
        Column({ space: 20 }) {
          if (this.section === 'root') {
            Text('Mobile Gateway').fontSize(this.a.font(14)).fontWeight(FontWeight.Bold).fontColor(this.a.muted).width('100%').padding({ left: 18 })
            Column() {
              this.row('主机', this.hostName || this.a.t('选择主机'), 'settings-hosts', this.onHosts)
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              this.row('主机管理', this.a.t(this.status), 'settings-manage-hosts', this.onHosts)
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              this.row('默认配置', '', 'settings-defaults', this.onDefaults)
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              this.row('Agent 预设', '', 'settings-agent-presets', this.onAgents)
            }.borderRadius(26).clip(true).width('100%')
            Text(this.a.t('外观')).fontSize(this.a.font(14)).fontWeight(FontWeight.Bold).fontColor(this.a.muted).width('100%').padding({ left: 18 })
            Column() {
              PreferenceMenuRow({ a: this.a, kind: 'style', onChoose: value => this.set(value) })
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              PreferenceMenuRow({ a: this.a, kind: 'language', onChoose: value => this.set(undefined, value) })
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              this.row('字号', this.a.data.fontFollowsSystem ? this.a.t('系统') : Math.round(this.a.data.fontScale * 100) + '%', 'settings-font', () => this.onOpenSection('font'))
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              Row() {
                Text(this.a.t('减少动态效果')).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
                Toggle({ type: ToggleType.Switch, isOn: this.a.data.reduceMotion }).id('settings-motion')
                  .onChange(value => this.set(undefined, undefined, undefined, undefined, value))
              }.padding(18).backgroundColor(this.a.surface).width('100%')
            }.borderRadius(26).clip(true).width('100%')
            Column() {
              this.row('存储空间', '', 'settings-storage', () => this.onOpenSection('storage'))
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              this.row('隐私', '', 'settings-privacy', () => this.onOpenSection('privacy'))
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              this.row('关于', this.version, 'settings-about', () => this.onOpenSection('about'))
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              this.row('插件', '', 'settings-plugins', this.onPlugins)
              Divider().color(this.a.line).margin({ left: 18, right: 18 })
              this.row('定时任务', '', 'settings-schedules', this.onSchedules)
            }.borderRadius(26).clip(true).width('100%')
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
        }.padding(20).width('100%').constraintSize({ maxWidth: 720 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
      }
    }.width('100%').height('100%').backgroundColor(this.section === 'font' && !this.a.dark ? '#F7F7F8' : this.a.settingsBg)
  }
}
