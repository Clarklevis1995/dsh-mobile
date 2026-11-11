import { Appearance } from '../core/Appearance';
import { Command, ControlData, ModelGroup, ModelItem, ReasoningEffort, emptyControls } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';

/** 部署默认模型独立页面；请求和回执继续使用共享 Control Store。 */
@Component
export struct DefaultModelPage {
  @ObjectLink a: Appearance;
  @Prop data: ControlData = emptyControls();
  @Prop connected: boolean = false;
  onBack: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  onChoose: (provider: string, model: string, effort?: string) => void = () => {};
  aboutToAppear(): void { this.onAction(new Command('controls-defaults')); }
  private current(): ControlData { return this.data.sessionId ? emptyControls() : this.data; }
  private selected(group: ModelGroup, model: ModelItem): boolean { return this.current().defaultModel?.provider === group.id && this.current().defaultModel?.model === model.id; }
  private available(): boolean { return this.connected && !this.current().loading.includes('save-default-model'); }
  private choose(group: ModelGroup, model: ModelItem): void {
    const current = this.current().defaultModel?.reasoningEffort;
    this.onChoose(group.id, model.id, model.reasoning?.efforts.some(row => row.id === current) ? current : model.reasoning?.defaultEffort);
  }
  private inset(group: ModelGroup, model: ModelItem): number { return this.selected(group, model) ? 14.5 : 15; }
  @Builder private modelCard(group: ModelGroup, model: ModelItem) {
    Column({ space: 12 }) {
      Button({ type: ButtonType.Normal }) {
        Row({ space: 8 }) {
          Text(model.name).fontSize(this.a.font(17)).lineHeight(this.a.font(24)).fontWeight(600).fontColor(this.a.fg).layoutWeight(1).textAlign(TextAlign.Start)
          if (this.selected(group, model)) {
            Text(this.a.english ? 'Current' : '当前使用').id('default-model-current-' + group.id + '-' + model.id)
              .fontSize(this.a.font(12)).lineHeight(this.a.font(24)).fontWeight(600).fontColor('#FFFFFF').padding({ left: 10, right: 10, top: 5, bottom: 5 }).borderRadius(50).backgroundColor('#000000')
          }
        }.width('100%').constraintSize({ minHeight: 24 })
      }.id('control-model-' + group.id + '-' + model.id).padding(0).width('100%').backgroundColor(Color.Transparent).borderRadius(0)
        .enabled(this.available()).onClick(() => this.choose(group, model))
      if (model.reasoning?.efforts.length) {
        Flex({ wrap: FlexWrap.Wrap, alignItems: ItemAlign.Center }) {
          Text(this.a.english ? 'Reasoning level' : '思考等级').fontSize(this.a.font(12)).fontColor(this.a.fg).opacity(.52).margin({ right: 8 })
          ForEach(model.reasoning?.efforts ?? [], (effort: ReasoningEffort) => {
            Button(effort.name).id('default-effort-' + group.id + '-' + model.id + '-' + effort.id).fontSize(this.a.font(12)).fontWeight(500)
              .fontColor(this.selected(group, model) && this.current().defaultModel?.reasoningEffort === effort.id ? this.a.surface : this.a.fg)
              .backgroundColor(this.selected(group, model) && this.current().defaultModel?.reasoningEffort === effort.id ? this.a.fg : this.a.dark ? '#14F1F4FA' : '#140E131A')
              .padding({ left: 10, right: 10, top: 5, bottom: 5 }).height(34).margin({ right: 8 })
              .enabled(this.available()).onClick(() => this.onChoose(group.id, model.id, effort.id))
          }, (effort: ReasoningEffort) => effort.id)
        }.width('100%')
      }
    }.padding({ left: this.inset(group, model), right: this.inset(group, model), top: this.inset(group, model) + 1, bottom: this.inset(group, model) }).id('default-model-card-' + group.id + '-' + model.id).width('100%').borderRadius(16).backgroundColor(this.a.surface)
      .border({ width: this.selected(group, model) ? 1.5 : 1, color: this.selected(group, model) ? this.a.fg : this.a.dark ? '#2EF1F4FA' : '#2E0E131A' })
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t('默认模型'), onBack: this.onBack })
      Scroll() {
        Column({ space: 20 }) {
          Column({ space: 7 }) {
            Text(this.a.t('默认模型')).id('default-model-heading').fontSize(this.a.font(22)).lineHeight(this.a.font(32)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).width('100%')
            Text(this.a.english ? 'Set the default model and reasoning level for new conversations. This updates deployment settings shared with WebUI. Running conversations keep their startup configuration.' : '为之后新建的会话设置默认模型与思考等级。这会更新部署级设置，同步影响 WebUI，运行中的会话保持启动时的配置。')
              .fontSize(this.a.font(14)).lineHeight(this.a.font(20)).fontColor(this.a.fg).opacity(.52).width('100%')
          }.width('100%').padding({ bottom: 4 })
          if (!this.connected) { Text(this.a.t('已断开')).fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%') }
          if (this.current().error) { Text(this.current().error).id('control-error').fontColor('#D65D64').fontSize(this.a.font(14)).width('100%') }
          if (this.current().loading.length) { LoadingProgress().id('control-loading').width(24).height(24).color(this.a.accent) }
          if (!this.current().models?.groups.length && !this.current().loading.includes('models')) {
            Column({ space: 8 }) {
              Text(this.a.english ? 'No models available' : '暂无可用模型').fontSize(this.a.font(17)).fontColor(this.a.fg)
              Text(this.a.english ? 'Check your gateway connection and retry.' : '未能读取到模型列表，请检查网关连接后重试。').fontSize(this.a.font(14)).fontColor(this.a.fg).opacity(.52)
              Button(this.a.t('重试')).id('control-refresh').height(48).enabled(this.connected).onClick(() => this.onAction(new Command('controls-defaults')))
            }.padding(24).width('100%').backgroundColor(this.a.surface).borderRadius(16)
          }
          ForEach(this.current().models?.groups ?? [], (group: ModelGroup) => {
            Column({ space: 10 }) {
              Text(group.name).fontSize(this.a.font(14)).lineHeight(this.a.font(24)).fontWeight(600).fontColor(this.a.fg).opacity(.52).width('100%')
              ForEach(group.models, (model: ModelItem) => { this.modelCard(group, model) }, (model: ModelItem) => model.id)
            }.width('100%')
          }, (group: ModelGroup) => group.id + ':' + JSON.stringify(group.models))
        }.padding(20).width('100%').constraintSize({ maxWidth: 720 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
