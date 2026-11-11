import { Appearance } from '../core/Appearance';

@CustomDialog
export struct ConfirmDialog {
  controller?: CustomDialogController;
  @ObjectLink a: Appearance;
  @Prop title: string = '';
  @Prop message: string = '';
  @Prop confirmLabel: string = '';
  onConfirm: () => void = () => {};
  build() {
    Column({ space: 20 }) {
      Text(this.title).fontSize(this.a.font(19)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).width('100%').textAlign(TextAlign.Center)
      Text(this.message).fontSize(this.a.font(16)).lineHeight(this.a.font(25)).fontColor(this.a.fg).width('100%').textAlign(TextAlign.Center)
      Row({ space: 12 }) {
        Button(this.a.t('取消'), { type: ButtonType.Normal }).fontSize(this.a.font(16)).fontColor(this.a.accent).backgroundColor(Color.Transparent)
          .layoutWeight(1).constraintSize({ minHeight: 48 }).onClick(() => this.controller?.close())
        Button(this.confirmLabel, { type: ButtonType.Normal }).id('confirmation-accept').fontSize(this.a.font(16)).fontColor(this.a.accent).backgroundColor(Color.Transparent)
          .layoutWeight(1).constraintSize({ minHeight: 48 }).onClick(() => { this.controller?.close(); this.onConfirm(); })
      }.width('100%')
    }.padding(24).backgroundColor(this.a.surface).borderRadius(28).width('100%')
  }
}
