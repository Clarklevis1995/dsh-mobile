/** Android HarnessAnimatedBackground 的流场数学；在 TaskPool 低分辨率采样，主线程只缩放位图。 */
class FluidField {
  private fract(value: number): number { return value - Math.floor(value); }
  private mix(a: number, b: number, t: number): number { return a + (b - a) * t; }
  private step(a: number, b: number, value: number): number { const t = Math.max(0, Math.min(1, (value - a) / (b - a))); return t * t * (3 - 2 * t); }
  private hash(x: number, y: number): number { x = this.fract(x * 123.34); y = this.fract(y * 456.21); const dot = x * (x + 45.32) + y * (y + 45.32); return this.fract((x + dot) * (y + dot)); }
  private noise(x: number, y: number): number { const i = Math.floor(x); const j = Math.floor(y); const fx = this.fract(x); const fy = this.fract(y); const ux = fx * fx * (3 - 2 * fx); const uy = fy * fy * (3 - 2 * fy); return this.mix(this.mix(this.hash(i,j), this.hash(i+1,j), ux), this.mix(this.hash(i,j+1), this.hash(i+1,j+1), ux), uy); }
  private fbm(x: number, y: number): number { let v = 0; let a = .56; for (let o = 0; o < 4; o++) { v += a * this.noise(x,y); const nx = (.8*x-.6*y)*2.03+17.17; y = (.6*x+.8*y)*2.03+17.17; x = nx; a *= .46; } return v; }
  private padRibbon(ux: number, uy: number, aspect: number, t: number, dx: number, dy: number): number {
    const ex = Math.max(1,aspect); const ey = Math.max(1,1/aspect); const wx = ux*ex+dx; const wy = uy*ey+dy;
    const cx = Math.max(ex*1.10,ex*.52+.14*ey*ey/ex); const ax = ex*.04; const bx = ex; const by = ey*.56;
    const cy = ((bx-cx)*(bx-cx)-(ax-cx)*(ax-cx)+by*by)/(2*by);
    const radius = Math.hypot(ax-cx,cy)+.020*Math.sin(t*1.5); const d = Math.hypot(wx-cx,wy-cy)-radius;
    const width = this.mix(.070,.0875,this.step(.02,.56,uy));
    const mask = 1-this.step(.65,.90,uy);
    const upper = (Math.exp(-Math.pow(d/width,2))*.55+Math.exp(-Math.pow(d/(width*2),2))*.45)*mask;
    const acx = -ex*.18; const aix = -ex*.05; const aiy = ey*1.02; const bix = ex*1.02; const biy = ey*.80;
    const acy = ((bix-acx)*(bix-acx)-(aix-acx)*(aix-acx)+biy*biy-aiy*aiy)/(2*(biy-aiy));
    const ar = Math.hypot(aix-acx,aiy-acy)+.018*Math.cos(t*1.15); const ad = Math.hypot(wx-acx,wy-acy)-ar;
    const am = this.step(.38,.66,uy)*this.step(0,.12,wy-acy)*this.step(width*2+.02,width*2+.10,d);
    const outer = (Math.exp(-Math.pow(ad/.105,2))*.55+Math.exp(-Math.pow(ad/.189,2))*.45)*am;
    return Math.max(0,Math.min(1,upper*.72+outer*.86));
  }
  render(width: number, height: number, aspect: number, seconds: number, wide: boolean): ArrayBuffer {
    const bytes = new Uint8ClampedArray(width*height*4); const t = seconds*.075; const scale = this.mix(1,1.6,this.step(.65,1.6,aspect));
    const black = [.002,.008,.018]; const deep = [.102,.220,.439]; const ocean = [.125,.290,.494]; const mist = [.325,.553,.790]; const warm = [.933,.847,.667];
    for (let y = 0; y < height; y++) { for (let x = 0; x < width; x++) {
      const ux = (x+.5)/width; const uy = (y+.5)/height;
      const fx = wide ? (ux-.5)*aspect/scale+.30 : ux*aspect; const fy = wide ? (uy-.5)/scale+.5 : uy; const px = fx*2.05-.30; const py = fy*2.05-.08;
      const qx = this.fbm(px*.62,py*.62+t); const qy = this.fbm(px*.62+5.2,py*.62+1.3-t);
      const rx = this.fbm(px*.72+qx*.62+1.7,py*.72+qy*.62+3.1+t*.7); const ry = this.fbm(px*.72+qx*.62+9.2,py*.72+qy*.62+5.7-t*.65);
      const fluid = this.fbm((px+qx*.76+rx*.52)*.72+t*.25,(py+qy*.76+ry*.52)*.72+t*.25); const swirl = this.noise(px*1.25+rx*2.1-t*.42,py*1.25+ry*2.1-t*.42);
      const dx = (qx-.5)*.085+(rx-.5)*.045; const dy = (qy-.5)*.085+(ry-.5)*.045; const wx = ux*aspect+dx; const wy = uy+dy;
      const ra = Math.hypot(wx-aspect*.58,wy-.16); const rb = Math.hypot(wx+aspect*.20,wy-.13);
      const ribbon = wide ? this.padRibbon(ux,uy,aspect,t,dx,dy) : Math.min(1,Math.exp(-Math.pow((ra-(.35+.025*Math.sin(t*1.5)))/.042,2))*.78+Math.exp(-Math.pow((rb-(.83+.020*Math.cos(t*1.15)))/.065,2))*.62);
      const glow = this.step(.90,.18,Math.hypot((ux-.58)*.82,uy-.32)); const vignette = this.step(.95,.23,Math.hypot((ux-.5)*.86,uy-.5)); const offset = (y*width+x)*4;
      for (let c=0;c<3;c++) { let color=this.mix(black[c],deep[c],this.step(.14,.63,fluid)); color=this.mix(color,ocean[c],this.step(.42,.82,fluid+swirl*.18)); color=this.mix(color,mist[c],this.step(.74,.98,swirl)*.18); color=this.mix(color,warm[c],ribbon*(.48+.24*swirl)); color+=deep[c]*glow*.22; color*=.38+.72*vignette; bytes[offset+c]=Math.max(0,color)*255; }
      bytes[offset+3]=255;
    }}
    return bytes.buffer;
  }
}

export function renderBrandFlow(width: number, height: number, aspect: number, seconds: number, wide: boolean): ArrayBuffer { return new FluidField().render(width,height,aspect,seconds,wide); }
