import { Appearance } from '../core/Appearance';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct ZoomPhoto {
  @Prop source: string = '';
  @State private zoomLevel: number = 1;
  @State private x: number = 0;
  @State private y: number = 0;
  private initialScale: number = 1;
  private initialX: number = 0;
  private initialY: number = 0;
  build() {
    Stack() {
      Image(this.source).width('100%').height('100%').objectFit(ImageFit.Contain).scale({ x: this.zoomLevel, y: this.zoomLevel }).translate({ x: this.x, y: this.y })
    }.width('100%').height('100%').clip(true).accessibilityText('图片预览')
      .gesture(GestureGroup(GestureMode.Parallel,
        PinchGesture().onActionStart(() => this.initialScale = this.zoomLevel).onActionUpdate(event => this.zoomLevel = Math.max(1, Math.min(5, this.initialScale * event.scale)))
          .onActionEnd(() => { if (this.zoomLevel <= 1) { this.x = 0; this.y = 0; } }),
        PanGesture().tag('photo-pan').onActionStart(() => { this.initialX = this.x; this.initialY = this.y; }).onActionUpdate(event => { if (this.zoomLevel > 1) { this.x = this.initialX + event.offsetX; this.y = this.initialY + event.offsetY; } }),
        TapGesture({ count: 2 }).onAction(() => { this.zoomLevel = this.zoomLevel > 1 ? 1 : 2; this.x = 0; this.y = 0; })
      )).onGestureJudgeBegin((info: GestureInfo) => info.tag === 'photo-pan' && this.zoomLevel <= 1 ? GestureJudgeResult.REJECT : GestureJudgeResult.CONTINUE)
  }
}

@Component
export struct PhotoPreviewPage {
  @ObjectLink a: Appearance;
  @Prop sources: string[] = [];
  @Prop initialIndex: number = 0;
  @State private index: number = 0;
  onBack: () => void = () => {};
  aboutToAppear(): void { this.index = Math.max(0, Math.min(this.initialIndex, this.sources.length - 1)); }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t('图片') + ' ' + (this.index + 1).toString() + ' / ' + this.sources.length.toString(), onBack: this.onBack })
      Swiper() {
        ForEach(this.sources, (source: string) => { ZoomPhoto({ source: source }) }, (source: string) => source)
      }.id('photo-preview').index(this.index).loop(false).indicator(this.sources.length > 1).onChange(value => this.index = value).layoutWeight(1).width('100%')
    }.width('100%').height('100%').backgroundColor(this.a.bg)
  }
}
