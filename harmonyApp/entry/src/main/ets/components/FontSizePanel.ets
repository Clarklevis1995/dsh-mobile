import { Appearance } from '../core/Appearance';
import { MarkdownBody } from './MarkdownBody';

@Component
export struct FontSizePanel {
  @ObjectLink a: Appearance;
  @State private compact: boolean = false;
  onFollow: (value: boolean) => void = () => {};
  onScale: (value: number) => void = () => {};
  @Builder private preview() {
    Scroll() {
      Column({ space: 28 }) {
        Row() {
          Blank()
          Text(this.a.english ? 'Preview text size' : '预览文字大小').fontSize(this.a.font(17)).lineHeight(this.a.font(24)).fontColor(this.a.fg)
            .padding({ left: 18, right: 18, top: 14, bottom: 14 }).backgroundColor(this.a.dark ? '#1C2D49' : '#EDF2FF')
            .borderRadius({ topLeft: 24, topRight: 24, bottomLeft: 24, bottomRight: 6 })
        }.width('100%')
        Text(this.a.english ? 'Drag the slider to adjust the text size.' : '你可以拖动滑块来调整字体大小。')
          .fontSize(this.a.font(17)).lineHeight(this.a.font(28)).fontColor(this.a.fg).width('100%')
        MarkdownBody({ a: this.a, prefix: 'font-preview-markdown', text: this.a.english ? 'The adjusted size applies to every page, including conversations, buttons and `code`.' : '调整后的字体大小会应用到所有页面，包括对话、按钮和 `代码`。' })
      }.padding({ left: 20, right: 20, top: 32, bottom: 32 }).width('100%')
    }.width('100%').height('100%').align(Alignment.Top).backgroundColor(this.a.surface).borderRadius(20).clip(true)
  }
  @Builder private controls() {
    Column({ space: 16 }) {
      Row() {
        Text(this.a.t('跟随系统')).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.a.data.fontFollowsSystem }).id('font-follow-system').accessibilityText(this.a.t('跟随系统字体大小'))
          .onChange(this.onFollow)
      }.width('100%').constraintSize({ minHeight: 48 })
      Divider().color((this.a.dark ? '#1ACAC4D0' : '#1A49454F'))
      Column({ space: 8 }) {
        Row() {
          Text('A').fontSize(this.a.font(14)).fontColor((this.a.dark ? '#CAC4D0' : '#49454F'))
          Blank()
          Text(this.a.data.fontFollowsSystem ? this.a.t('跟随系统') : this.a.data.fontScale === 1 ? this.a.t('标准') : Math.round(this.a.data.fontScale * 100) + '%')
            .id('font-scale-value').fontSize(this.a.font(14)).fontColor((this.a.dark ? '#CAC4D0' : '#49454F'))
          Blank()
          Text('A').fontSize(this.a.font(22)).fontColor((this.a.dark ? '#CAC4D0' : '#49454F'))
        }.width('100%')
        Slider({ value: this.a.data.fontScale * 100, min: 80, max: 150, step: 5 }).id('font-scale').height(48).enabled(!this.a.data.fontFollowsSystem)
          .trackThickness(2).trackColor((this.a.dark ? '#38CAC4D0' : '#3849454F')).selectedColor((this.a.dark ? '#38CAC4D0' : '#3849454F')).blockColor(this.a.surface).blockSize({ width: 28, height: 28 }).showSteps(true)
          .onChange((value: number, mode: SliderChangeMode) => { if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) { this.onScale(value / 100); } })
        Button(this.a.t('恢复默认'), { type: ButtonType.Normal }).id('font-scale-reset').height(48).enabled(!this.a.data.fontFollowsSystem)
          .fontSize(this.a.font(13)).fontColor((this.a.dark ? '#CAC4D0' : '#49454F')).backgroundColor(Color.Transparent).onClick(() => this.onScale(1))
      }.padding({ top: 12 }).width('100%')
    }.padding({ left: 12, right: 12 }).width('100%')
  }
  build() {
    Column() {
      if (this.compact) {
        Scroll() {
          Column({ space: 24 }) { Column() { this.preview() }.height(260).width('100%'); this.controls() }.padding({ left: 20, right: 20, top: 16, bottom: 20 }).width('100%')
        }.width('100%').height('100%').align(Alignment.Top)
      } else {
        Column({ space: 24 }) { Column() { this.preview() }.layoutWeight(1).width('100%'); this.controls() }
          .padding({ left: 20, right: 20, top: 16, bottom: 20 }).width('100%').height('100%')
      }
    }.width('100%').height('100%').constraintSize({ maxWidth: 1040 }).onAreaChange((_old: Area, value: Area) => { this.compact = Number(value.height) < 500; })
  }
}
