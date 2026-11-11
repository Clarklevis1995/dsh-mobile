import { generateBarcode, detectBarcode, scanCore } from '@kit.ScanKit';
import { image } from '@kit.ImageKit';
import { common } from '@kit.AbilityKit';
import { fileIo as fs, fileUri } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';
import { Engine } from 'libwhaleness_bridge.so';

interface ScanFixture { label: string; value: string; valid: boolean; }
interface ScanValidation { ok: boolean; }
@Entry
@Component
struct Index {
  @State private lines: string[] = [];
  private phase: string = 'fixtures';
  aboutToAppear(): void { this.run(); }
  private async run(): Promise<void> {
    if (!canIUse('SystemCapability.Multimedia.Scan.ScanBarcode') || !canIUse('SystemCapability.Multimedia.Scan.GenerateBarcode')) {
      this.lines = ['SKIP ScanKit system capability is unavailable', 'DONE SCAN probe']; return;
    }
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    const validator = new Engine(); const path = context.cacheDir + '/owned-qr-probe.png';
    try {
      const raw = await context.resourceManager.getRawFileContent('scan-fixtures.json');
      const rows = JSON.parse(new util.TextDecoder().decodeWithStream(raw)) as ScanFixture[];
      let index = 0;
      for (const row of rows) {
        this.phase = 'generate ' + row.label;
        try { const generated = await generateBarcode.createBarcode(row.value, { scanType: scanCore.ScanType.QR_CODE, width: 768, height: 768, margin: 12 }); await generated.release(); this.lines = this.lines.concat(['PASS actual generate ' + row.label]); }
        catch (error) { const message = error instanceof Error ? error.message : (error as BusinessError).message ?? ''; if (!message.includes('Emulator is not supported')) { throw new Error(message); } this.lines = this.lines.concat(['SKIP generate only: Emulator is not supported']); }
        const bytes = await context.resourceManager.getRawFileContent('scan-images/qr-' + index++ + '.png');
        const source = image.createImageSource(bytes.buffer);
        const pixel = await source.createPixelMap(); await source.release();
        const packer = image.createImagePacker();
        try {
          this.phase = 'pack ' + row.label;
          const png = await packer.packing(pixel, { format: 'image/png', quality: 100 });
          const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
          try { if (fs.writeSync(file.fd, png) !== png.byteLength) { throw new Error('incomplete fixture'); } } finally { fs.closeSync(file); }
        } finally { await packer.release(); await pixel.release(); }
        this.phase = 'decode ' + row.label;
        const decoded = await detectBarcode.decode({ uri: fileUri.getUriFromPath(path) }, { scanTypes: [scanCore.ScanType.QR_CODE] });
        if (!decoded.some(result => result.originalValue === row.value)) { throw new Error('scan mismatch: ' + row.label); }
        const valid = JSON.parse(validator.dispatch(JSON.stringify({ op: 'prepare', input: row.value, nowMillis: Date.now() }))) as ScanValidation;
        if (valid.ok !== row.valid) { throw new Error('native validation mismatch: ' + row.label); }
        this.lines = this.lines.concat(['PASS actual QR decode + Native ' + row.label]);
      }
      this.lines = this.lines.concat(['DONE SCAN probe']);
    } catch (error) { this.lines = this.lines.concat(['FAIL ' + this.phase + ' ' + ((error as BusinessError).code ?? '') + ' ' + (error instanceof Error ? error.message : (error as BusinessError).message ?? 'scan SDK')]); }
    finally { validator.close(); await fs.unlink(path).catch(() => {}); }
  }
  build() {
    Column({ space: 16 }) {
      Text('ScanKit image decode / Native pairing validation').fontSize(17)
      ForEach(this.lines, (line: string) => { Text(line).fontSize(14).fontColor(line.startsWith('FAIL') ? '#DD2222' : '#111111') })
    }.padding(20).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
