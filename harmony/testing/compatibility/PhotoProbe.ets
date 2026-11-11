import { common } from '@kit.AbilityKit';
import { taskpool } from '@kit.ArkTS';
import { fileIo as fs } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import { preparePhoto } from '../core/PhotoWorker';

interface PhotoResult { size: number; sha256: string; width: number; height: number; }
@Entry
@Component
struct Index {
  @State private lines: string[] = [];
  aboutToAppear(): void { this.run(); }
  private line(value: string): void { this.lines = this.lines.concat([value]); }
  private ensure(value: boolean, message: string): void { if (!value) { throw new Error(message); } }
  private async prepare(name: string): Promise<PhotoResult> {
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    const bytes = await context.resourceManager.getRawFileContent('orientation-fixtures/' + name + '.jpg');
    const source = context.cacheDir + '/' + name + '-source.jpg'; const destination = context.cacheDir + '/' + name + '-normalized.jpg';
    const file = fs.openSync(source, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
    try { fs.writeSync(file.fd, bytes.buffer); } finally { fs.closeSync(file); }
    const result = JSON.parse(await taskpool.execute(preparePhoto, source, destination) as string) as PhotoResult;
    this.ensure(result.size === fs.statSync(destination).size && result.sha256.length === 64 && result.size <= 1048576, name + ' digest/size');
    return result;
  }
  private color(buffer: Uint8Array, width: number, x: number, y: number): string {
    const offset = (y * width + x) * 4; const r = buffer[offset]; const g = buffer[offset+1]; const b = buffer[offset+2];
    if (r > 150 && g > 150 && b < 100) { return 'Y'; }
    if (r > g * 1.5 && r > b * 1.5) { return 'R'; }
    if (g > r * 1.5 && g > b * 1.5) { return 'G'; }
    if (b > r * 1.5 && b > g * 1.5) { return 'B'; }
    return '?';
  }
  private async run(): Promise<void> {
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const expected = ['RGBY','GRYB','YBGR','BYRG','RBGY','BRYG','YGBR','GYRB'];
      for (let value = 1; value <= 8; value++) {
        const name = 'exif-' + value; const result = await this.prepare(name); const rotated = value >= 5;
        this.ensure(result.width === (rotated ? 64 : 96) && result.height === (rotated ? 96 : 64), name + ' dimensions');
        const source = image.createImageSource(context.cacheDir + '/' + name + '-normalized.jpg');
        try {
          const pixel = await source.createPixelMap({ desiredPixelFormat: image.PixelMapFormat.RGBA_8888 });
          try {
            const buffer = new ArrayBuffer(result.width * result.height * 4); await pixel.readPixelsToBuffer(buffer); const bytes = new Uint8Array(buffer);
            const a = Math.floor(result.width/4); const b = Math.floor(result.width*3/4); const c = Math.floor(result.height/4); const d = Math.floor(result.height*3/4);
            const colors = this.color(bytes,result.width,a,c)+this.color(bytes,result.width,b,c)+this.color(bytes,result.width,a,d)+this.color(bytes,result.width,b,d);
            this.ensure(colors === expected[value-1], name + ' corners=' + colors + ' expected=' + expected[value-1]);
          } finally { await pixel.release(); }
        } finally { await source.release(); }
        this.line('PASS EXIF ' + value + ' · orientation / corners / digest');
      }
      const large = await this.prepare('large-rotated'); this.ensure(large.width <= 2048 && large.height <= 2048 && large.height > large.width,'large bounded rotation');
      this.line('PASS large · 2048px / JPEG / <=1MiB'); this.line('DONE PHOTO probe');
    } catch (error) { this.line('FAIL ' + (error instanceof Error ? error.message : 'photo sdk')); }
  }
  build() {
    Column({ space: 14 }) { ForEach(this.lines, (line: string) => { Text(line).fontSize(14).fontColor(line.startsWith('FAIL') ? '#DD2222' : '#111111').width('100%') }) }
      .padding(20).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
