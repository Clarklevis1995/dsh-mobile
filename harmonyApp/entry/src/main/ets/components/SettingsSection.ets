import { Appearance } from '../core/Appearance';

@Component
export struct SettingsSection {
  @ObjectLink a: Appearance;
  @Prop title: string = '';
  @Prop footer: string = '';
  @BuilderParam content: () => void;
  build() {
    Column({ space: 8 }) {
      Text(this.a.t(this.title)).fontSize(this.a.font(16)).lineHeight(this.a.font(24)).fontWeight(600).fontColor(this.a.fg).opacity(.48).width('100%').padding({ left: 20 })
      Column() { this.content() }.backgroundColor(this.a.surface).borderRadius(22).clip(true).width('100%')
      if (this.footer) { Text(this.a.t(this.footer)).fontSize(this.a.font(12)).lineHeight(this.a.font(18)).fontColor(this.a.fg).opacity(.52).width('100%').padding({ left: 20, right: 20 }) }
    }.width('100%')
  }
}
