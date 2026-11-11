import { LengthMetricsUnit, window } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { taskpool } from '@kit.ArkTS';
import { prepareBrandFlow } from '../core/BrandFlowWorker';
import { BRAND_PIXELS, BRAND_WIDE_PIXELS } from '../core/BrandPixels';

interface Pixel { x: number; y: number; luminance: number; edge: number; light: number; }

@Component
export struct BrandBackground {
  @Prop @Watch('restart') active: boolean = true;
  @Prop @Watch('restart') reduceMotion: boolean = false;
  private context: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private pixels: Pixel[] = [];
  private canvasWidth: number = 0;
  private canvasHeight: number = 0;
  private tick: number = -1;
  private phase: number = 0;
  private ready: boolean = false;
  private alive: boolean = false;
  private windowRevision: number = 0;
  private hostWindow?: window.Window;
  private topInset: number = 0;
  private bottomInset: number = 0;
  private systemBottom: number = 0;
  private navigationBottom: number = 0;
  private avoidChanged: (value: window.AvoidAreaOptions) => void = value => this.avoidArea(value.type, value.area);
  aboutToAppear(): void {
    this.alive = true; const ticket = ++this.windowRevision; const context = this.getUIContext().getHostContext(); if (!context) { return; }
    window.getLastWindow(context as common.UIAbilityContext).then(host => {
      if (!this.alive || ticket !== this.windowRevision) { return; } this.hostWindow = host; host.on('avoidAreaChange', this.avoidChanged);
      this.avoidArea(window.AvoidAreaType.TYPE_SYSTEM, host.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM));
      this.avoidArea(window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR, host.getWindowAvoidArea(window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR));
    }).catch(() => {});
  }
  private avoidArea(type: window.AvoidAreaType, area: window.AvoidArea): void {
    let top = this.topInset;
    if (type === window.AvoidAreaType.TYPE_SYSTEM) { top = this.getUIContext().px2vp(area.topRect.height); this.systemBottom = this.getUIContext().px2vp(area.bottomRect.height); }
    else if (type === window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR) { this.navigationBottom = this.getUIContext().px2vp(area.bottomRect.height); }
    else { return; }
    const bottom = Math.max(this.systemBottom, this.navigationBottom);
    if (top === this.topInset && bottom === this.bottomInset) { return; }
    this.topInset = top; this.bottomInset = bottom; this.flowRevision++; this.flow?.close(); this.flow = undefined;
    if (this.ready && this.active) { this.draw(); this.updateFlow(); }
  }

  private flow?: ImageBitmap = undefined;
  private flowBusy: boolean = false;
  private flowRevision: number = 0;
  private flowDue: number = 0;
  aboutToDisappear(): void { this.alive = false; this.windowRevision++; try { this.hostWindow?.off('avoidAreaChange', this.avoidChanged); } catch (_) {} this.hostWindow = undefined; this.ready = false; this.flowRevision++; clearInterval(this.tick); this.flow?.close(); this.flow = undefined; }
  private async updateFlow(): Promise<void> {
    if (!this.ready || !this.active || this.flowBusy || this.canvasWidth <= 0 || this.canvasHeight <= 0) { return; }
    const aspect = this.canvasWidth / (this.canvasHeight + this.topInset + this.bottomInset); const width = 96; const height = Math.max(1,Math.min(256,Math.round(width/aspect))); const revision = this.flowRevision;
    this.flowBusy = true; this.flowDue = Date.now()+1250;
    try {
      const buffer = await taskpool.execute(new taskpool.Task(prepareBrandFlow,width,height,aspect,this.phase,this.canvasWidth >= 600)) as ArrayBuffer;
      if (!this.ready || revision !== this.flowRevision) { return; }
      const canvas = new OffscreenCanvas(width,height,LengthMetricsUnit.PX); const context = canvas.getContext('2d');
      context.putImageData(new ImageData(width,height,new Uint8ClampedArray(buffer),LengthMetricsUnit.PX),0,0);
      const next = canvas.transferToImageBitmap(); this.flow?.close(); this.flow = next; if (this.active) { this.draw(); }
    } catch (_) { this.flowDue = Date.now()+5000; } finally { this.flowBusy = false; if (this.ready && this.active && revision !== this.flowRevision) { this.updateFlow(); } }
  }
  private restart(): void {
    clearInterval(this.tick); this.tick = -1;
    if (!this.ready || !this.active) { return; }
    this.draw(); if (!this.flow) { this.updateFlow(); }
    if (this.active && !this.reduceMotion) {
      let last = Date.now();
      this.tick = setInterval(() => { const now = Date.now(); this.phase = (this.phase + (now-last)/1000)%4096; last = now; this.draw(); if (Date.now() >= this.flowDue) { this.updateFlow(); } }, 80);
    }
  }
  private sample(): void {
    const source = this.canvasWidth >= 600 ? BRAND_WIDE_PIXELS : BRAND_PIXELS;
    this.pixels = source.map((point: number[]): Pixel => ({ x: point[0], y: point[1], luminance: point[2], edge: point[3],
      light: Math.max(0, 1 - Math.hypot(point[0] - .30, point[1] - .28) / .92) }));
  }
  private smoothstep(a: number, b: number, value: number): number { const x = Math.max(0,Math.min(1,(value-a)/(b-a))); return x*x*(3-2*x); }
  private draw(): void {
    const ctx = this.context; const w = this.canvasWidth; const h = this.canvasHeight + this.topInset + this.bottomInset;
    if (w <= 0 || h <= 0) { return; }
    ctx.clearRect(0, 0, w, this.canvasHeight); ctx.save(); ctx.translate(0, -this.topInset);
    if (this.flow) { ctx.imageSmoothingEnabled = true; ctx.drawImage(this.flow,0,0,w,h); }
    else { const glow = ctx.createRadialGradient(w*.46,h*.28,0,w*.46,h*.28,Math.max(w,h)*.82); glow.addColorStop(0,'#234B79'); glow.addColorStop(.48,'#101F3B'); glow.addColorStop(1,'#010510'); ctx.fillStyle=glow; ctx.fillRect(0,0,w,h); }
    ctx.strokeStyle = 'rgba(255,255,255,0.043)'; ctx.lineWidth = .55;
    const spacing = w >= 600 ? Math.min(64,Math.max(42,Math.min(w,h)/16)) : 42;
    for (let x = 0; x < w; x += spacing) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke(); }
    for (let y = 0; y < h; y += spacing) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); }
    for (let row=0; row<=Math.ceil(h/spacing); row++) { for (let column=0; column<=Math.ceil(w/spacing); column++) {
      if ((row*11+column*7)%13===0) { ctx.fillStyle='rgba(255,255,255,0.085)'; ctx.fillRect(column*spacing-1.1,row*spacing-1.1,2.2,2.2); }
    } }
    // 基准网格层自56%开始向Navy收敛；它位于鲸鱼之前，不能只靠最后的黑色遮罩代替。
    const gridVeil=ctx.createLinearGradient(0,0,0,h); gridVeil.addColorStop(0,'rgba(6,23,43,0)'); gridVeil.addColorStop(.56,'rgba(6,23,43,0)'); gridVeil.addColorStop(1,'#06172B'); ctx.fillStyle=gridVeil; ctx.fillRect(0,0,w,h);
    const wide = w >= 600; const sampleSize = wide ? 100 : 72;
    const size = wide ? Math.min(Math.max(w*1.15,h*.48),h*1.12)*.82 : Math.max(w*1.15,h*.48);
    const progress=Math.max(0,Math.min(1,(w/h-.65)/.75)); const cx=w*(wide ? .55+.05*progress : .55); const cy=h*(wide ? .34+.12*progress : .34);
    const baseDot=wide ? Math.max(.8,Math.min(2.4,size/360)) : Math.max(1.45,size/220)*(60/72);
    const t=this.reduceMotion ? 0 : this.phase; const stagger=size/sampleSize*.14;
    const staticPixel=this.getUIContext().px2vp(1);
    this.pixels.forEach((pixel: Pixel) => {
      const edge=.35+.65*pixel.edge; const column=pixel.x*sampleSize; const row=pixel.y*sampleSize;
      const dx=Math.sin(t*.50+pixel.x*5+pixel.y*3)*(this.reduceMotion ? 2.8*staticPixel : 4)*edge;
      const dy=Math.cos(t*.42+pixel.x*4-pixel.y*3)*(this.reduceMotion ? 2.6*staticPixel : 4)*edge;
      const sx=Math.sin(row*1.05+column*.35+t*.50)*stagger;
      const sy=Math.sin(column*.90-row*Math.PI/2+t*.42)*stagger*.7;
      const tail=Math.sin(t*1.10-pixel.x*7)*(this.reduceMotion ? 7*staticPixel : 10)*this.smoothstep(.54,.94,pixel.x);
      const shimmer=.90+.10*Math.sin(t*1.5+pixel.x*15+pixel.y*9);
      ctx.globalAlpha=(.10+pixel.luminance*.38)*shimmer*.42;
      ctx.fillStyle='rgb('+Math.round((.73+.15*pixel.light)*255)+','+Math.round((.79+.12*pixel.light)*255)+','+Math.round((.92+.08*pixel.light)*255)+')';
      const dot=baseDot*(.72+pixel.luminance*.58); const x=cx+(pixel.x-.5)*size*1.08+dx+sx; const y=cy+(pixel.y-.5)*size+dy+sy+tail;
      ctx.beginPath(); ctx.roundRect(x-dot/2,y-dot/2,dot,dot,dot*.18); ctx.fill();
    });
    ctx.globalAlpha = 1; const veil = ctx.createLinearGradient(0,0,0,h); veil.addColorStop(0,'rgba(6,23,43,0)'); veil.addColorStop(.58,'rgba(6,23,43,0.12)'); veil.addColorStop(1,'rgba(0,0,0,0.58)'); ctx.fillStyle=veil; ctx.fillRect(0,0,w,h); ctx.restore();
  }
  build() {
    Canvas(this.context).width('100%').height('100%')
      .onReady(() => { this.ready = true; this.sample(); this.restart(); })
      .onAreaChange((_before: Area, area: Area) => { if (this.canvasWidth === Number(area.width) && this.canvasHeight === Number(area.height)) { return; } this.canvasWidth = Number(area.width); this.canvasHeight = Number(area.height); this.sample(); this.flowRevision++; this.flow?.close(); this.flow = undefined; if (this.active) { this.draw(); this.updateFlow(); } })
      .hitTestBehavior(HitTestMode.None).accessibilityLevel('no')
  }
}
