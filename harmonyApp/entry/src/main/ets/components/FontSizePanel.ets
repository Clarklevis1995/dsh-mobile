import { Appearance } from '../core/Appearance';

@Component
export struct FontSizePanel {
  @ObjectLink a: Appearance;
  onFollow: (value: boolean) => void = () => {};
  onScale: (value: number) => void = () => {};
  build() {
    Column({ space: 24 }) {
      Scroll() {
        Column({ space: 28 }) {
          Row() {
            Blank()
            Text(this.a.english ? 'Preview text size' : '预览文字大小').fontSize(this.a.font(17)).fontColor(this.a.fg)
              .padding({ left: 18, right: 18, top: 14, bottom: 14 }).backgroundColor(this.a.dark ? '#1C2D49' : '#EDF2FF')
              .borderRadius({ topLeft: 24, topRight: 24, bottomLeft: 24, bottomRight: 6 })
          }.width('100%')
          Text(this.a.english ? 'Drag the slider to adjust the text size.' : '你可以拖动滑块来调整字体大小。')
            .fontSize(this.a.font(17)).lineHeight(this.a.font(28)).fontColor(this.a.fg).width('100%')
          Text(this.a.english ? 'The adjusted size applies to every page, including conversations, buttons and code.' : '调整后的字体大小会应用到所有页面，包括对话、按钮和代码。')
            .fontSize(this.a.font(17)).lineHeight(this.a.font(28)).fontColor(this.a.fg).width('100%')
        }.padding({ left: 20, right: 20, top: 32, bottom: 32 }).width('100%')
      }.layoutWeight(1).width('100%').align(Alignment.Top).backgroundColor(this.a.surface).borderRadius(20).clip(true)
      Column({ space: 16 }) {
        Row() {
          Text(this.a.t('跟随系统')).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
          Toggle({ type: ToggleType.Switch, isOn: this.a.data.fontFollowsSystem }).id('font-follow-system')
            .onChange(this.onFollow)
        }.width('100%')
        Divider().color(this.a.line)
        Row() {
          Text('A').fontSize(this.a.font(14)).fontColor(this.a.muted)
          Blank()
          Text(this.a.data.fontFollowsSystem ? this.a.t('跟随系统') : this.a.data.fontScale === 1 ? this.a.t('标准') : Math.round(this.a.data.fontScale * 100) + '%')
            .id('font-scale-value').fontSize(this.a.font(14)).fontColor(this.a.muted)
          Blank()
          Text('A').fontSize(this.a.font(22)).fontColor(this.a.muted)
        }.width('100%')
        Slider({ value: this.a.data.fontScale * 100, min: 80, max: 150, step: 5 }).id('font-scale').enabled(!this.a.data.fontFollowsSystem)
          .trackThickness(2).trackColor(this.a.line).selectedColor(this.a.line).blockColor(this.a.surface).blockSize({ width: 28, height: 28 }).showSteps(true)
          .onChange((value: number, mode: SliderChangeMode) => { if (mode === SliderChangeMode.End || mode === SliderChangeMode.Click) { this.onScale(value / 100); } })
        Button(this.a.t('恢复默认'), { type: ButtonType.Normal }).id('font-scale-reset').enabled(!this.a.data.fontFollowsSystem)
          .fontSize(this.a.font(13)).fontColor(this.a.muted).backgroundColor(Color.Transparent).onClick(() => this.onScale(1))
      }.padding({ left: 12, right: 12 }).width('100%')
    }.padding({ left: 20, right: 20, top: 16, bottom: 20 }).width('100%').height('100%').constraintSize({ maxWidth: 1040 })
  }
}
