import { common } from '@kit.AbilityKit';
import { BrandBackground } from '../components/BrandBackground';
import { BrandProbeStats } from '../core/BrandProbeStats';

@Entry
@Component
struct BrandProbe {
  @State private active: boolean = true;
  @State private motion: boolean = false;
  @State private foreground: boolean = true;
  @State private summary: string = '';
  private context?: common.UIAbilityContext;
  private backgroundStart: number = -1;
  private backgroundEnd: number = -1;
  private onForeground: () => void = () => { this.backgroundEnd = BrandProbeStats.draws; this.foreground = true; };
  private onBackground: () => void = () => { this.backgroundStart = BrandProbeStats.draws; this.foreground = false; };
  aboutToAppear(): void {
    this.context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    this.context.eventHub.on('gatewayForeground', this.onForeground); this.context.eventHub.on('gatewayBackground', this.onBackground);
  }
  aboutToDisappear(): void {
    this.context?.eventHub.off('gatewayForeground', this.onForeground); this.context?.eventHub.off('gatewayBackground', this.onBackground);
  }
  private capture(): void {
    this.summary = JSON.stringify({ time: Date.now(), draws: BrandProbeStats.draws, totalMs: BrandProbeStats.totalMs,
      maxMs: BrandProbeStats.maxMs, backgroundStart: this.backgroundStart, backgroundEnd: this.backgroundEnd });
  }
  build() {
    Stack() {
      BrandBackground({ active: this.active && this.foreground, reduceMotion: this.motion })
      Column({ space: 16 }) {
        Text('原生背景专项 · 无网络').fontColor('#FFFFFF').fontSize(22)
        Button('页面活动').id('probe-active').onClick(() => this.active = !this.active)
        Button('减少动态').id('probe-motion').onClick(() => this.motion = !this.motion)
        Button('记录计数').id('probe-capture').onClick(() => this.capture())
        Text(this.summary).id('probe-stats').fontSize(10).fontColor('#FFFFFF').height(70).width('100%')
      }.padding(20).width('100%')
    }.width('100%').height('100%')
  }
}
