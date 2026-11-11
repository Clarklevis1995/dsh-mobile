import { Appearance } from '../core/Appearance';
import { AgentPreset, Command, ControlData, emptyControls } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct AgentPresetsPage {
  @ObjectLink a: Appearance;
  @Prop data: ControlData = emptyControls();
  @Prop connected: boolean = false;
  onBack: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  onChoose: (id: string) => void = () => {};
  aboutToAppear(): void { this.onAction(new Command('controls-defaults')); }
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
        ListItem() { Text(this.a.t('预设决定 Agent 使用的工具、提示词与能力。选择后只对新建会话生效，并同步部署级设置。')).fontSize(this.a.font(14)).fontColor(this.a.muted).lineHeight(this.a.font(20)).width('100%') }
        if (this.current().error) { ListItem() { Text(this.current().error).fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%') } }
        if (!this.current().presets.length) {
          ListItem() { Text(this.a.t(this.current().loading.includes('agent-presets') ? '正在读取 Agent 预设…' : '没有可用的 Agent 预设，请确认网关支持此能力并保持连接')).id('agent-presets-empty').fontSize(this.a.font(15)).fontColor(this.a.muted).padding(20) }
        }
        ForEach(this.current().presets, (preset: AgentPreset) => {
          ListItem() {
            Column({ space: 12 }) {
              Row({ space: 8 }) {
                Text(this.name(preset)).fontSize(this.a.font(17)).fontWeight(600).fontColor(this.a.fg).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
                Text(preset.id).fontFamily('monospace').fontSize(this.a.font(12)).fontColor(this.a.muted).padding({ left: 7, right: 7, top: 3, bottom: 3 }).borderRadius(50).backgroundColor(this.a.dark ? '#1AFFFFFF' : '#12000000').constraintSize({ maxWidth: 120 }).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                if (this.current().agentPresetDefault === preset.id) { Text(this.a.t('当前默认')).id('agent-preset-current-' + preset.id).fontSize(this.a.font(12)).fontColor(this.a.accent) }
              }.width('100%')
              Text(this.description(preset)).fontSize(this.a.font(14)).lineHeight(this.a.font(19)).fontColor(this.a.muted).width('100%')
              if (preset.broken) { Text(preset.brokenReason || this.a.t('该预设存在配置错误，暂时不能设为默认值')).fontSize(this.a.font(12)).fontColor('#F08A16').width('100%') }
            }.padding(16).id('agent-preset-card-' + preset.id).width('100%').backgroundColor(this.a.surface).borderRadius(16)
              .border({ width: this.current().agentPresetDefault === preset.id ? 1.5 : 1, color: this.current().agentPresetDefault === preset.id ? this.a.fg : this.a.line })
              .accessibilityText(this.name(preset) + (this.current().agentPresetDefault === preset.id ? ' · ' + this.a.t('当前默认') : ''))
              .enabled(this.connected && !this.current().loading.includes('set-default') && !preset.broken)
              .onClick(() => { if (this.current().agentPresetDefault !== preset.id) { this.onChoose(preset.id); } })
          }
        }, (preset: AgentPreset) => preset.id)
        if (this.current().agentPresetsAuthorable || this.current().agentPresetsHasDocument) { ListItem() { Text(this.a.t(this.current().agentPresetsAuthorable && this.current().agentPresetsHasDocument ? '服务端支持编写自定义预设，并提供预设配置文档。' : this.current().agentPresetsAuthorable ? '服务端支持编写自定义 Agent 预设。' : '服务端提供 Agent 预设配置文档。')).fontSize(this.a.font(12)).fontColor(this.a.muted).padding({ top: 4 }) } }
      }.padding(20).width('100%').constraintSize({ maxWidth: 720 }).layoutWeight(1)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
