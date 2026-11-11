import { Appearance, iconTint } from '../core/Appearance';

/** 菜单锚点放在独立组件的 build 中，避免嵌套 Builder 的状态／事件上下文丢失。 */
@Component
export struct PreferenceMenuRow {
  @ObjectLink a: Appearance;
  @Prop kind: string = 'style';
  onChoose: (value: string) => void = () => {};
  private current(): string { return this.kind === 'style' ? this.a.data.style : this.a.data.language; }
  private values(): string[] { return this.kind === 'style' ? ['system', 'light', 'dark'] : ['system', 'zh', 'en']; }
  private label(value: string): string {
    return this.kind === 'style' ? value === 'system' ? this.a.t('跟随系统') : this.a.option(value) : value === 'system' ? this.a.t('跟随系统') : value === 'zh' ? '简体中文' : 'English';
  }
  @Builder private optionsMenu() {
    Menu() {
      ForEach(this.values(), (value: string) => {
        MenuItem({ content: this.label(value) }).id((this.kind === 'style' ? 'appearance-' : 'language-') + value)
          .selected(this.current() === value).selectIcon(true)
          .contentFont({ size: this.a.font(15) }).contentFontColor(this.a.fg).onClick(() => this.onChoose(value))
      }, (value: string) => value)
    }.width(208).backgroundColor(this.a.surface).borderRadius(26)
  }
  build() {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 12 }) {
        Text(this.a.t(this.kind === 'style' ? '界面' : '语言')).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
        Text(this.label(this.current())).fontSize(this.a.font(14)).fontColor(this.a.muted).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Image($rawfile('icons/chevron_right.svg')).width(16).height(16).colorFilter(iconTint(this.a.muted))
      }.padding(16).width('100%').constraintSize({ minHeight: 54 })
    }.padding(0).width('100%').borderRadius(0).backgroundColor(this.a.surface)
      .id(this.kind === 'style' ? 'settings-style' : 'settings-language').bindMenu(this.optionsMenu)
  }
}
