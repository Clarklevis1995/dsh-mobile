import { Appearance, iconTint } from '../core/Appearance';
import { SettingsData } from '../core/SettingsData';

/** 动态值必须在组件内部读取，避免 Builder 普通参数截取首次快照。 */
@Component
export struct SettingsGatewayRow {
  @ObjectLink a: Appearance;
  @ObjectLink snapshot: SettingsData;
  @Prop kind: string = '';
  @Prop controlId: string = '';
  onPress: () => void = () => {};
  private title(): string {
    if (this.kind === 'host-version') { return this.a.t('版本'); }
    if (this.kind === 'host-sessions') { return this.a.t('已连接会话'); }
    if (this.kind === 'host-cwd') { return 'cwd'; }
    if (this.kind === 'font') { return this.a.t('字体大小'); }
    if (this.kind === 'agent') { return this.a.t('Agent 预设'); }
    if (this.kind === 'model') { return this.a.t('默认模型'); }
    if (this.kind === 'permission') { return this.a.t('权限'); }
    if (this.kind === 'endpoint') { return this.snapshot.endpoint || this.a.t('尚未配置主机'); }
    if (this.kind === 'status') { return this.a.t(this.snapshot.status); }
    if (this.kind === 'connection') { return this.a.t(this.snapshot.connected ? '断开连接' : '连接'); }
    return this.a.t('Ping 网关');
  }
  private detail(): string {
    if (this.kind === 'font') { return this.a.data.fontFollowsSystem ? this.a.t('跟随系统') : Math.round(this.a.data.fontScale * 100).toString() + '%'; }
    if (this.kind === 'host-version') { return this.snapshot.hostSnapshot?.dshVersion?.trim() || this.snapshot.hostSnapshot?.version?.trim() || this.a.t('未提供'); }
    if (this.kind === 'host-sessions') { return this.snapshot.hostSnapshot?.attachedSessions?.toString() ?? this.a.t('未提供'); }
    if (this.kind === 'host-cwd') { return this.snapshot.hostSnapshot?.cwd ?? ''; }
    const data = this.snapshot.controls;
    if (this.kind === 'agent') { return data.presets.find(row => row.id === data.agentPresetDefault)?.name ?? data.agentPresetDefault ?? this.a.t('未读取'); }
    if (this.kind === 'model') { return data.models?.groups.find(group => group.id === data.defaultModel?.provider)?.models.find(model => model.id === data.defaultModel?.model)?.name ?? data.defaultModel?.model ?? this.a.t('未读取'); }
    if (this.kind === 'permission') { return data.permissionDefaultOptions.find(row => row.value === data.permissionDefault)?.name ?? data.permissionDefault ?? this.a.t('未读取'); }
    return this.kind === 'status' ? this.snapshot.hostName : '';
  }
  private canPress(): boolean { return !['agent', 'model', 'permission', 'ping'].includes(this.kind) || this.snapshot.connected; }
  build() {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 12 }) {
        Text(this.title()).fontSize(this.a.font(16)).fontColor(['connection', 'ping'].includes(this.kind) ? this.a.accent : this.a.fg).layoutWeight(1).textAlign(TextAlign.Start)
        if (this.detail()) { Text(this.detail()).fontSize(this.a.font(14)).fontColor(this.a.muted).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }) }
        if (!['connection', 'ping', 'host-version', 'host-sessions', 'host-cwd'].includes(this.kind)) { Image($rawfile('icons/chevron_right.svg')).width(16).height(16).colorFilter(iconTint(this.a.muted)) }
      }.padding({ left: 16, right: 16, top: 15, bottom: 15 }).width('100%').constraintSize({ minHeight: 54 })
    }.padding(0).width('100%').backgroundColor(this.a.surface).borderRadius(0).id(this.controlId).opacity(this.canPress() ? 1 : .4).enabled(this.canPress()).onClick(this.onPress)
  }
}
