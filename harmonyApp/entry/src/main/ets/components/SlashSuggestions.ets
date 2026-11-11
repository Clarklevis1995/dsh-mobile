import { Appearance } from '../core/Appearance';
import { Command, SlashData, SlashGroup, SlashItem, SlashOption, emptySlash } from '../core/Models';

@Component
export struct SlashSuggestions {
  @ObjectLink a: Appearance;
  @Prop data: SlashData = emptySlash();
  @Prop sessionId: string = '';
  onAction: (command: Command) => void = () => {};
  onReconnect: () => void = () => {};
  private choose(id: string, option: boolean): void { const command = new Command(option ? 'slash-option' : 'slash-pick'); command.sessionId = this.sessionId; command.itemId = id; this.onAction(command); }
  private close(): void { const command = new Command('slash-dismiss'); command.sessionId = this.sessionId; this.onAction(command); }
  build() {
    Column({ space: 6 }) {
      Row() {
        Text(this.data.optionsTitle ? '/' + this.data.optionsTitle : this.a.t('命令与技能')).fontSize(this.a.font(14)).fontColor(this.a.fg).layoutWeight(1)
        Button(this.a.t('关闭')).id('slash-dismiss').height(44).fontSize(this.a.font(12)).fontColor(this.a.muted).backgroundColor(Color.Transparent).onClick(() => this.close())
      }.width('100%')
      if (this.data.error) { Text(this.data.error).id('slash-error').fontSize(this.a.font(13)).fontColor('#D65D64').width('100%') }
      if (this.data.uncertain) { Button(this.a.t('重新连接')).id('slash-reconnect').height(44).onClick(this.onReconnect) }
      if (this.data.loading || this.data.optionsLoading || this.data.selecting || this.data.executing) { LoadingProgress().width(24).height(24).color(this.a.accent) }
      Scroll() {
        Column({ space: 6 }) {
          if (this.data.optionsTitle) {
            ForEach(this.data.options, (option: SlashOption) => {
              Button() { Column({ space: 3 }) { Text((option.selected ? '✓ ' : '') + option.label).fontSize(this.a.font(14)).fontColor(this.a.fg).width('100%'); if (option.detail || option.description) { Text(option.detail || option.description).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%') } }.padding(10).width('100%') }
                .id('slash-option-' + option.id).width('100%').backgroundColor(this.a.surface).constraintSize({ minHeight: 44 })
                .enabled(!this.data.selecting && !this.data.uncertain && !this.data.executing).onClick(() => this.choose(option.id, true))
            }, (option: SlashOption) => JSON.stringify(option))
          } else {
            ForEach(this.data.groups, (group: SlashGroup) => {
              Column({ space: 4 }) {
                Text(group.title).fontSize(this.a.font(11)).fontColor(this.a.muted).width('100%')
                ForEach(group.items, (item: SlashItem) => {
                  Button() { Column({ space: 3 }) { Text('/' + item.name).fontSize(this.a.font(14)).fontColor(this.a.fg).width('100%'); Text(item.description).fontSize(this.a.font(12)).fontColor(this.a.muted).maxLines(2).width('100%') }.padding(10).width('100%') }
                    .id('slash-command-' + item.id).width('100%').constraintSize({ minHeight: 44 }).backgroundColor(this.a.surface)
                    .enabled(!this.data.uncertain && !this.data.executing && !this.data.selecting).onClick(() => this.choose(item.id, false))
                }, (item: SlashItem) => item.id)
              }.width('100%')
            }, (group: SlashGroup) => JSON.stringify(group))
            if (!this.data.groups.length && !this.data.loading) { Text(this.a.t('没有匹配命令')).fontSize(this.a.font(13)).fontColor(this.a.muted).padding(12) }
          }
        }.width('100%')
      }.width('100%').constraintSize({ maxHeight: 220 })
    }.padding(12).width('100%').backgroundColor(this.a.surface).borderRadius(16)
  }
}
