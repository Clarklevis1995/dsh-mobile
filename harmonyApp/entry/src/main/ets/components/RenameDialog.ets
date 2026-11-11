import { Appearance } from '../core/Appearance';
@CustomDialog
export struct RenameDialog {
  controller?: CustomDialogController;
  @ObjectLink a: Appearance;
  @Prop initial: string = '';
  @State value: string = '';
  onSave: (title: string) => void = () => {};
  aboutToAppear(): void { this.value = this.initial; }
  build() {
    Column({ space: 18 }) {
      Text(this.a.t('重命名')).fontSize(this.a.font(19)).fontWeight(FontWeight.Bold).fontColor(this.a.fg)
      TextInput({ text: this.value, placeholder: this.a.t('会话名称') }).id('session-rename-input').maxLength(200)
        .fontSize(this.a.font(16)).fontColor(this.a.fg).backgroundColor(this.a.bg).onChange(value => this.value = value)
      Row({ space: 12 }) {
        Button(this.a.t('取消')).fontSize(this.a.font(16)).fontColor(this.a.muted).backgroundColor(this.a.bg).onClick(() => this.controller?.close())
        Button(this.a.t('保存')).id('session-rename-save').fontSize(this.a.font(16)).enabled(this.value.trim().length > 0).onClick(() => { this.onSave(this.value.trim()); this.controller?.close(); })
      }
    }.padding(24).backgroundColor(this.a.surface).borderRadius(28).width('100%')
  }
}
