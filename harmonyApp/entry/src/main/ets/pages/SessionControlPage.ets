import { Appearance } from '../core/Appearance';
import { Command, SharedState, ControlData, ControlMetric, ModelGroup, ModelItem, ModelSelection, ReasoningEffort, PermissionOption, AgentPreset, emptyControls, SessionFeatureData, emptyFeatures } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct SessionControlPage {
  @ObjectLink a: Appearance;
  @Prop @Watch('visibleChanged') active: boolean = true;
  @Prop @Watch('visibleChanged') resumeRevision: number = 0;
  private visibleChanged(): void { if (this.active) { this.refresh(); } }
  @Prop state: SharedState;
  @Prop @Watch('connectionChanged') connected: boolean = false;
  @Prop features: SessionFeatureData = emptyFeatures();
  @Prop data: ControlData = emptyControls();
  @Prop isDefault: boolean = false;
  onPresetChange: (id: string) => void = () => {};
  onBack: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  aboutToAppear(): void { this.refresh(); }
  private connectionChanged(): void { if (this.connected) { this.refresh(); } }
  private refresh(): void { if (!this.active || !this.connected) { return; } const command = new Command(this.isDefault ? 'controls-defaults' : 'controls-open'); command.sessionId = this.isDefault ? undefined : this.state.selectedSessionId; this.onAction(command); }
  private current(): ControlData { return this.isDefault ? !this.data.sessionId ? this.data : emptyControls() : this.data.sessionId === this.state.selectedSessionId ? this.data : emptyControls(); }
  private selection(): ModelSelection | undefined { return this.isDefault ? this.current().defaultModel : this.current().models?.current; }
  private canRequest(kind: string): boolean {
    const loading = this.current().loading;
    return this.active && this.connected && !loading.includes(kind) &&
      (!['select-model', 'save-default-model'].includes(kind) || !loading.includes('models')) &&
      (kind !== 'permission' || !loading.includes('permission-options'));
  }
  private selectModel(provider: string, model: string, effort?: string): void {
    const command = new Command(this.isDefault ? 'controls-save-model' : 'controls-model');
    command.sessionId = this.isDefault ? undefined : this.state.selectedSessionId; command.provider = provider; command.model = model; command.reasoningEffort = effort; this.onAction(command);
  }
  private permission(value: string): void {
    const command = new Command(this.isDefault ? 'controls-default' : 'controls-permission');
    command.sessionId = this.isDefault ? undefined : this.state.selectedSessionId; command.target = 'permission'; command.value = value; this.onAction(command);
  }
  private sessionAgent(id: string): void { const command = new Command('agent-select'); command.sessionId = this.state.selectedSessionId; command.value = id; this.onAction(command); }
  private agentRefresh(): void { const command = new Command('agent-refresh'); command.sessionId = this.state.selectedSessionId; this.onAction(command); }
  private currentAgent(): SessionFeatureData { return this.features.sessionId === this.state.selectedSessionId ? this.features : emptyFeatures(); }
  private preset(id: string): void { this.onPresetChange(id); }
  private selected(model: ModelItem, group: ModelGroup): boolean { return this.selection()?.provider === group.id && this.selection()?.model === model.id; }
  @Builder private heading(label: string) { Text(this.a.t(label)).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%').margin({ top: 14, bottom: 4 }) }
  @Builder private metric(label: string, value: string) {
    Row() { Text(this.a.t(label)).fontSize(this.a.font(14)).fontColor(this.a.fg).layoutWeight(1); Text(value).fontSize(this.a.font(14)).fontColor(this.a.muted) }.width('100%').padding({ top: 8, bottom: 8 })
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t(this.isDefault ? '默认配置' : '会话设置'), onBack: this.onBack })
      Scroll() {
        Column({ space: 8 }) {
          if (!this.connected) { Text(this.a.t('已断开')).fontColor(this.a.muted).fontSize(this.a.font(14)).width('100%') }
          if (this.current().error) { Text(this.current().error).id('control-error').fontColor('#D65D64').fontSize(this.a.font(14)).width('100%') }
          if (this.current().loading.length) { LoadingProgress().id('control-loading').width(24).height(24).color(this.a.accent) }
          if (!this.isDefault && this.currentAgent().agent.supported) {
            this.heading('会话 Agent')
            if (this.currentAgent().agent.loading || this.currentAgent().agent.saving) { LoadingProgress().width(24).height(24).color(this.a.accent) }
            if (this.currentAgent().error) { Text(this.a.t(this.currentAgent().error)).fontSize(this.a.font(13)).fontColor('#D65D64').width('100%') }
            Text((this.currentAgent().agent.preset ?? '—') + (this.currentAgent().agent.locked ? ' · ' + this.a.t('已锁定') : ''))
              .id('session-agent-current').fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%')
            ForEach(this.currentAgent().agent.presets, (preset: AgentPreset) => {
              Button((this.currentAgent().agent.preset === preset.id ? '✓ ' : '') + (preset.name || preset.id)).id('session-agent-' + preset.id)
                .fontSize(this.a.font(15)).fontColor(this.a.fg).backgroundColor(this.a.surface).width('100%').height(48)
                .enabled(this.connected && this.currentAgent().agent.canSelect && !preset.broken).onClick(() => this.sessionAgent(preset.id))
            }, (preset: AgentPreset) => JSON.stringify(preset))
            if (!this.currentAgent().agent.known && !this.currentAgent().agent.loading) { Button(this.a.t('刷新 Agent')).id('session-agent-refresh').height(44).enabled(this.connected).onClick(() => this.agentRefresh()) }
          }
          this.heading('模型与思考强度')
          if (!this.isDefault) {
            Text(this.a.english ? 'The host also tries to save this model and reasoning choice as the default for new sessions. Other running sessions keep their configuration.' : '切换模型或思考等级后，主机会尝试同步为新会话默认值。其他已启动会话保持原配置。')
              .id('session-model-default-note').fontSize(this.a.font(12)).lineHeight(this.a.font(18)).fontColor(this.a.muted).width('100%')
          }

          if (!this.current().models?.groups.length && !this.current().loading.includes('models')) { Text(this.a.t('主机未提供可选模型')).fontColor(this.a.muted).fontSize(this.a.font(14)).width('100%') }
          ForEach(this.current().models?.groups ?? [], (group: ModelGroup) => {
            Column({ space: 6 }) {
              Text(group.name).fontColor(this.a.muted).fontSize(this.a.font(12)).width('100%')
              ForEach(group.models, (model: ModelItem) => {
                Column({ space: 4 }) {
                  Button((this.selected(model, group) ? '✓ ' : '') + model.name).id('control-model-' + group.id + '-' + model.id)
                    .fontSize(this.a.font(15)).fontColor(this.selected(model, group) ? this.a.accent : this.a.fg).backgroundColor(this.a.surface)
                    .width('100%').height(48).enabled(this.canRequest(this.isDefault ? 'save-default-model' : 'select-model') && (this.isDefault || this.current().models?.routable === true))
                    .onClick(() => this.selectModel(group.id, model.id, model.reasoning?.defaultEffort))
                  if (this.selected(model, group) && model.reasoning?.efforts.length) {
                    Flex({ wrap: FlexWrap.Wrap }) {
                      ForEach(model.reasoning.efforts, (effort: ReasoningEffort) => {
                        Button((this.selection()?.reasoningEffort === effort.id ? '✓ ' : '') + effort.name).id('control-effort-' + effort.id)
                          .fontSize(this.a.font(12)).fontColor(this.a.accent).backgroundColor(this.a.bg).height(44).margin(4)
                          .enabled(this.canRequest(this.isDefault ? 'save-default-model' : 'select-model')).onClick(() => this.selectModel(group.id, model.id, effort.id))
                      }, (effort: ReasoningEffort) => effort.id)
                    }.width('100%')
                  }
                }.width('100%')
              }, (model: ModelItem) => model.id)
            }.padding(14).borderRadius(20).backgroundColor(this.a.surface).width('100%')
          }, (group: ModelGroup) => group.id + ":" + JSON.stringify(group.models))
          this.heading(this.isDefault ? '默认权限' : '会话权限')
          ForEach(this.isDefault ? this.current().permissionDefaultOptions : this.current().permissions?.options ?? [], (option: PermissionOption) => {
            Button() {
              Column({ space: 4 }) {
                Text(((this.isDefault ? this.current().permissionDefault : this.current().permissions?.currentValue) === option.value ? '✓ ' : '') + option.name).fontSize(this.a.font(15)).fontColor(this.a.fg).width('100%')
                if (option.description) { Text(option.description).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%') }
              }.padding(14).width('100%')
            }.id('control-permission-' + option.value).width('100%').constraintSize({ minHeight: 48 }).backgroundColor(this.a.surface).borderRadius(20)
              .enabled(this.canRequest(this.isDefault ? 'set-default' : 'permission')).onClick(() => this.permission(option.value))
          }, (option: PermissionOption) => JSON.stringify(option))
          if (this.isDefault) {
            this.heading('默认 Agent')
            ForEach(this.current().presets, (preset: AgentPreset) => {
              Button((this.current().agentPresetDefault === preset.id ? '✓ ' : '') + (preset.name || preset.id)).id('control-preset-' + preset.id)
                .fontSize(this.a.font(15)).fontColor(preset.broken ? this.a.muted : this.a.fg).backgroundColor(this.a.surface).width('100%').height(48)
                .enabled(!preset.broken && this.canRequest('set-default')).onClick(() => this.preset(preset.id))
            }, (preset: AgentPreset) => JSON.stringify(preset))
          } else {
            this.heading('上下文与统计')
            Column() {
              ForEach(this.current().metrics ?? [], (metric: ControlMetric) => { this.metric(metric.label, metric.value) }, (metric: ControlMetric) => metric.id + ":" + metric.value)
            }.padding(16).width('100%').borderRadius(20).backgroundColor(this.a.surface)
          }
          Button(this.a.t('刷新')).id('control-refresh').height(48).width('100%').fontSize(this.a.font(15)).enabled(this.connected).onClick(() => this.refresh())
        }.padding(16).width('100%').constraintSize({ maxWidth: 760 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
