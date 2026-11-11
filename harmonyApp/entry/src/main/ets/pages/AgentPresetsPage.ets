import { Appearance } from '../core/Appearance';
import { AgentPreset, Command, ControlData, emptyControls } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct AgentPresetsPage {
  @ObjectLink a: Appearance;
  @Prop data: ControlData = emptyControls();
  @Prop @Watch('visibleChanged') active: boolean = true;
  @Prop @Watch('visibleChanged') resumeRevision: number = 0;
  @Prop @Watch('connectionChanged') connected: boolean = false;
  onBack: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  onChoose: (id: string) => void = () => {};
  aboutToAppear(): void { this.refresh(); }
  private refresh(): void { if (this.active && this.connected) { this.onAction(new Command('controls-defaults')); } }
  private visibleChanged(): void { if (this.active) { this.refresh(); } }
  private connectionChanged(): void { if (this.connected) { this.refresh(); } }
  private available(): boolean { return this.active && this.connected && !this.current().loading.some(kind => ['agent-presets', 'defaults', 'set-default'].includes(kind)); }
  private inset(preset: AgentPreset): number { return this.current().agentPresetDefault === preset.id ? 14.5 : 15; }
  private current(): ControlData { return !this.data.sessionId ? this.data : emptyControls(); }
  private name(preset: AgentPreset): string {
    switch (preset.id) {
      case 'standard': return this.a.t('标准模式'); case 'code': return this.a.t('PTC 模式');
      case 'minimal': return this.a.t('极简模式'); case 'cordis': return this.a.t('创造模式');
      default: return preset.name || preset.id;
    }
  }
  private description(preset: AgentPreset): string {
    if (preset.description?.trim()) { return preset.description; }
    switch (preset.id) {
      case 'standard': return this.a.t('功能完整的编码 Agent，支持文件编辑、Shell、检索、Skills、计划与工作流。');
      case 'code': return this.a.t('通过 Code Mode SDK 组合多步工具操作。');
      case 'minimal': return this.a.t('精简工具集合，适合轻量、直接的编码任务。');
      case 'cordis': return this.a.t('用于创建和维护自定义 Agent 预设。');
      default: return this.a.t('由 DeepSeek Harness 提供的 Agent 预设。');
    }
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t('Agent 预设'), onBack: this.onBack })
      List({ space: 14 }) {
        ListItem() {
          Column({ space: 7 }) {
            Text(this.a.t('Agent 预设')).id('agent-presets-heading').fontSize(this.a.font(22)).lineHeight(this.a.font(32)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).width('100%')
            Text(this.a.t('预设决定 Agent 使用的工具、提示词与能力。选择后只对新建会话生效。')).fontSize(this.a.font(14)).fontColor(this.a.fg).opacity(.52).lineHeight(this.a.font(20)).width('100%')
          }.width('100%').padding({ bottom: 4 })
        }
        if (!this.connected) { ListItem() { Text(this.a.t('已断开')).id('agent-presets-disconnected').fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%') } }
        if (this.current().error) { ListItem() {
          Column({ space: 8 }) {
            Text(this.current().error).id('agent-presets-error').fontSize(this.a.font(14)).fontColor('#D65D64').width('100%')
            Button(this.a.t('重试')).id('agent-presets-retry').fontColor(this.a.fg).backgroundColor(this.a.surface).height(48).enabled(this.connected).onClick(() => this.refresh())
          }.width('100%')
        } }
        if (!this.current().presets.length) {
          ListItem() {
            Column({ space: 8 }) {
              Text(this.a.t(this.current().loading.includes('agent-presets') ? '正在读取 Agent 预设…' : '没有可用的 Agent 预设，请确认网关支持此能力并保持连接')).id('agent-presets-empty').fontSize(this.a.font(15)).fontColor(this.a.muted)
              if (!this.current().loading.includes('agent-presets') && !this.current().error) {
                Button(this.a.t('重试')).id('agent-presets-empty-retry').fontColor(this.a.fg).backgroundColor(this.a.surface).height(48).enabled(this.connected).onClick(() => this.refresh())
              }
            }.padding(20).width('100%')
          }
        }
        ForEach(this.current().presets, (preset: AgentPreset) => {
          ListItem() {
            Column({ space: 12 }) {
              Row({ space: 8 }) {
                Text(this.name(preset)).fontSize(this.a.font(17)).lineHeight(this.a.font(24)).fontWeight(600).fontColor(this.a.fg).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).flexShrink(1)
                Text(preset.id).fontFamily('monospace').fontSize(this.a.font(12)).lineHeight(this.a.font(24)).fontColor(this.a.dark ? '#85F1F4FA' : '#850E131A').padding({ left: 7, right: 7, top: 3, bottom: 3 }).borderRadius(50).backgroundColor(this.a.dark ? '#12F1F4FA' : '#120E131A').constraintSize({ maxWidth: 120 }).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                Blank().layoutWeight(1)
                if (this.current().agentPresetDefault === preset.id) {
                  Text(this.a.english ? 'Current' : '当前使用').id('agent-preset-current-' + preset.id).fontSize(this.a.font(12)).lineHeight(this.a.font(24)).fontWeight(600).fontColor('#FFFFFF')
                    .padding({ left: 10, right: 10, top: 5, bottom: 5 }).borderRadius(50).backgroundColor('#000000')
                }
              }.width('100%')
              Text(this.description(preset)).fontSize(this.a.font(14)).lineHeight(this.a.font(20)).fontColor(this.a.fg).opacity(.52).width('100%')
              if (preset.broken) { Text(preset.brokenReason || this.a.t('该预设存在配置错误，暂时不能设为默认值')).fontSize(this.a.font(12)).fontColor('#F08A16').width('100%') }
            }.padding({ left: this.inset(preset), right: this.inset(preset), top: this.inset(preset) + 1, bottom: this.inset(preset) }).id('agent-preset-card-' + preset.id).width('100%').backgroundColor(this.a.surface).borderRadius(16)
              .border({ width: this.current().agentPresetDefault === preset.id ? 1.5 : 1, color: this.current().agentPresetDefault === preset.id ? this.a.fg : this.a.dark ? '#2EF1F4FA' : '#2E0E131A' })
              .accessibilityText(this.name(preset) + (this.current().agentPresetDefault === preset.id ? ' · ' + this.a.t('当前默认') : ''))
              .enabled(this.available() && !preset.broken)
              .onClick(() => { if (this.current().agentPresetDefault !== preset.id) { this.onChoose(preset.id); } })
          }
        }, (preset: AgentPreset) => preset.id)
        if (this.current().agentPresetsAuthorable || this.current().agentPresetsHasDocument) { ListItem() { Text('ⓘ  ' + this.a.t(this.current().agentPresetsAuthorable && this.current().agentPresetsHasDocument ? '服务端支持编写自定义预设，并提供预设配置文档。' : this.current().agentPresetsAuthorable ? '服务端支持编写自定义 Agent 预设。' : '服务端提供 Agent 预设配置文档。')).fontSize(this.a.font(12)).fontColor(this.a.muted).padding({ top: 4 }) } }
      }.padding(20).width('100%').constraintSize({ maxWidth: 720 }).layoutWeight(1)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
