import { Appearance, iconTint } from '../core/Appearance';

@Component
export struct CircleIcon {
  @Prop icon: string = 'settings';
  @Prop diameter: number = 48;
  @Prop glass: boolean = false;
  @Prop outlined: boolean = false;
  @Prop lifted: boolean = false;
  @Prop label: string = '';
  @Prop ink: string = '#FFFFFF';
  @Prop fill: string = '#253C58';
  @Prop controlId: string = '';
  @Prop available: boolean = true;
  onPress: () => void = () => {};
  build() {
    Button({ type: ButtonType.Circle }) {
      // 实心 SVG 使用 fillColor；给折线 SVG 填充会错误闭合路径。
      if (['settings', 'gateway_auth', 'session_rename'].includes(this.icon)) {
        Image($rawfile('icons/' + this.icon + '.svg')).width(23).height(23).fillColor(this.ink)
      } else {
        Image($rawfile('icons/' + this.icon + '.svg')).width(23).height(23).colorFilter(iconTint(this.ink))
      }
    }.id(this.controlId).accessibilityText(this.label).width(this.diameter).height(this.diameter)
      .backgroundColor(this.glass ? Color.Transparent : this.fill).backgroundEffect({ radius: this.glass ? 4 : 0, saturation: this.glass ? .92 : 1, brightness: this.glass ? 1.005 : 1, color: this.glass ? '#0DFFFFFF' : Color.Transparent })
      .shadow({ radius: this.lifted ? 12 : 0, color: '#13000000', offsetY: 4 })
      .border({ width: this.glass ? .8 : this.lifted || this.outlined ? .7 : 0, color: this.glass ? '#40FFFFFF' : this.ink === '#F1F4FA' ? '#22F1F4FA' : '#0E0E131A', radius: this.diameter / 2 }).enabled(this.available).onClick(this.onPress)
  }
}

@Component
export struct PageHeader {
  @ObjectLink a: Appearance;
  @Prop title: string = '';
  onBack: () => void = () => {};
  build() {
    Row() {
      CircleIcon({ icon: 'back_chevron', diameter: 46, outlined: true, label: this.a.t('返回'), controlId: 'page-back', ink: this.a.fg, fill: this.a.surface, onPress: this.onBack })
      Text(this.title).fontSize(this.a.font(17)).fontWeight(600).fontColor(this.a.fg).layoutWeight(1).textAlign(TextAlign.Center)
      Blank().width(46)
    }.width('100%').height(64).padding({ left: 12, right: 12 })
  }
}

@Component
export struct ErrorBanner {
  @ObjectLink a: Appearance;
  @Prop text: string = '';
  onRetry: () => void = () => {};
  build() {
    Row({ space: 12 }) {
      Text(this.text).fontSize(this.a.font(13)).fontColor(this.a.dark ? '#FFB1B1' : '#B42323').layoutWeight(1).copyOption(CopyOptions.LocalDevice)
      Button(this.a.t('重试')).fontSize(this.a.font(13)).fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(this.onRetry)
    }.padding(12).borderRadius(16).backgroundColor(this.a.dark ? '#3A202A' : '#FFEEEE').width('100%')
  }
}
