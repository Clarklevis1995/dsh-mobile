import { LengthMetricsUnit } from '@kit.ArkUI';
import { taskpool } from '@kit.ArkTS';
import { prepareBrandFlow } from '../core/BrandFlowWorker';
import { BRAND_PIXELS } from '../core/BrandPixels';

interface Pixel { x: number; y: number; weight: number; }

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

  private flow?: ImageBitmap = undefined;
  private flowBusy: boolean = false;
  private flowRevision: number = 0;
  private flowDue: number = 0;
  aboutToDisappear(): void { this.ready = false; this.flowRevision++; clearInterval(this.tick); this.flow?.close(); this.flow = undefined; }
  private async updateFlow(): Promise<void> {
    if (!this.ready || this.flowBusy || this.canvasWidth <= 0 || this.canvasHeight <= 0) { return; }
    const aspect = this.canvasWidth / this.canvasHeight; const width = 96; const height = Math.max(1,Math.min(256,Math.round(width/aspect))); const revision = this.flowRevision;
    this.flowBusy = true; this.flowDue = Date.now()+1250;
    try {
      const buffer = await taskpool.execute(new taskpool.Task(prepareBrandFlow,width,height,aspect,this.phase,this.canvasWidth >= 600)) as ArrayBuffer;
      if (!this.ready || revision !== this.flowRevision) { return; }
      const canvas = new OffscreenCanvas(width,height,LengthMetricsUnit.PX); const context = canvas.getContext('2d');
      context.putImageData(new ImageData(width,height,new Uint8ClampedArray(buffer),LengthMetricsUnit.PX),0,0);
      const next = canvas.transferToImageBitmap(); this.flow?.close(); this.flow = next; this.draw();
    } catch (_) { this.flowDue = Date.now()+5000; } finally { this.flowBusy = false; if (this.ready && revision !== this.flowRevision) { this.updateFlow(); } }
  }
  private restart(): void {
    clearInterval(this.tick); this.tick = -1;
    if (!this.ready) { return; }
    this.draw(); if (!this.flow) { this.updateFlow(); }
    if (this.active && !this.reduceMotion) {
      this.tick = setInterval(() => { this.phase += .08; this.draw(); if (Date.now() >= this.flowDue) { this.updateFlow(); } }, 80);
    }
  }
  private sample(): void {
    this.pixels = BRAND_PIXELS.map((point: number[]): Pixel => {
      const noise = Math.sin(point[0] * 779.388 + point[1] * 4693.98) * 43758.5453;
      return { x: point[0], y: point[1], weight: 0.25 + 0.75 * (noise - Math.floor(noise)) };
    });
  }
  private draw(): void {
    const ctx = this.context; const w = this.canvasWidth; const h = this.canvasHeight;
    if (w <= 0 || h <= 0) { return; }
    ctx.clearRect(0, 0, w, h);
    if (this.flow) { ctx.imageSmoothingEnabled = true; ctx.drawImage(this.flow,0,0,w,h); }
    else { const glow = ctx.createRadialGradient(w*.46,h*.28,0,w*.46,h*.28,Math.max(w,h)*.82); glow.addColorStop(0,'#234B79'); glow.addColorStop(.48,'#101F3B'); glow.addColorStop(1,'#010510'); ctx.fillStyle=glow; ctx.fillRect(0,0,w,h); }
    ctx.strokeStyle = 'rgba(255,255,255,0.043)'; ctx.lineWidth = .55;
    const spacing = w >= 600 ? Math.min(64,Math.max(42,Math.min(w,h)/16)) : 42;
    for (let x = 0; x < w; x += spacing) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke(); }
    for (let y = 0; y < h; y += spacing) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke(); }
    const wide = w >= 600; const size = wide ? Math.min(Math.max(w*1.15,h*.48),h*1.12)*.82 : Math.max(w*1.15,h*.48); const left = w*.55-size*1.08*.5;
    const top = h*.34-size*.5;
    this.pixels.forEach((pixel: Pixel) => {
      const movement = this.reduceMotion ? 0 : Math.sin(this.phase + pixel.x * 4) * 4;
      ctx.globalAlpha = (.10+pixel.weight*.38)*.42;
      ctx.fillStyle = '#8FB5E5';
      ctx.fillRect(left + pixel.x * size * 1.08, top + pixel.y * size + movement, Math.max(1, size / 220), Math.max(1, size / 220));
    });
    ctx.globalAlpha = 1; const veil = ctx.createLinearGradient(0,0,0,h); veil.addColorStop(0,'rgba(6,23,43,0)'); veil.addColorStop(.58,'rgba(6,23,43,0.12)'); veil.addColorStop(1,'rgba(0,0,0,0.58)'); ctx.fillStyle=veil; ctx.fillRect(0,0,w,h);
  }
  build() {
    Canvas(this.context).width('100%').height('100%')
      .onReady(() => { this.ready = true; this.sample(); this.restart(); })
      .onAreaChange((_before: Area, area: Area) => { if (this.canvasWidth === Number(area.width) && this.canvasHeight === Number(area.height)) { return; } this.canvasWidth = Number(area.width); this.canvasHeight = Number(area.height); this.flowRevision++; this.flow?.close(); this.flow = undefined; this.draw(); this.updateFlow(); })
      .hitTestBehavior(HitTestMode.None).accessibilityLevel('no')
  }
}
