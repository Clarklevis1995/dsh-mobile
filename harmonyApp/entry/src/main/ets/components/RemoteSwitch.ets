import { Appearance } from '../core/Appearance';

/** 受 Host 确认值控制；点击不会先改变开关位置。 */
@Component
export struct RemoteSwitch {
  @ObjectLink a: Appearance;
  @Prop checked: boolean = false;
  @Prop available: boolean = false;
  @Prop label: string = '';
  @Prop controlId: string = '';
  onChoose: (value: boolean) => void = () => {};
  build() {
    Button() {
      Row() { Circle().width(25).height(25).fill('#FFFFFF') }.padding(3).width(51).height(31).borderRadius(16)
        .justifyContent(this.checked ? FlexAlign.End : FlexAlign.Start).backgroundColor(this.checked ? '#34C759' : this.a.dark ? '#39393D' : '#D7D9DE')
    }.id(this.controlId).width(59).height(44).padding(0).backgroundColor(Color.Transparent).enabled(this.available)
      .accessibilityRole(AccessibilityRoleType.SWITCH).accessibilityChecked(this.checked).accessibilityText(this.label)
      .onClick(() => this.onChoose(!this.checked))
  }
}
