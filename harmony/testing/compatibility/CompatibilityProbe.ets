import { common } from '@kit.AbilityKit';
import { fileIo, picker } from '@kit.CoreFileKit';
import { relationalStore } from '@kit.ArkData';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { media } from '@kit.MediaKit';
import { notificationManager } from '@kit.NotificationKit';
import { scanBarcode, scanCore } from '@kit.ScanKit';
import { Markdown } from '@lidary/markdown';
import { Credential, CredentialStore } from '../core/CredentialStore';
import { PreferenceStore } from '../core/PreferenceStore';
import { HostRepository } from '../core/HostRepository';

@Entry
@ComponentV2
struct CompatibilityProbe {
  @Local lines: string[] = [];
  private context?: common.UIAbilityContext;
  aboutToAppear(): void { this.context = this.getUIContext().getHostContext() as common.UIAbilityContext; this.run(); }
  private add(value: string): void { this.lines = this.lines.concat([value]); }
  private async run(): Promise<void> {
    if (!this.context) { return; }
    try {
      const path = this.context.cacheDir + '/compatibility.txt';
      const file = fileIo.openSync(path, fileIo.OpenMode.CREATE | fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC);
      try { fileIo.writeSync(file.fd, 'harmony-file-probe'); } finally { fileIo.closeSync(file); }
      if (fileIo.readTextSync(path) !== 'harmony-file-probe') { throw new Error('文件读回不一致'); }
      this.add('PASS File I/O');
      const store = await relationalStore.getRdbStore(this.context, { name: 'compatibility.db', securityLevel: relationalStore.SecurityLevel.S1 });
      await store.executeSql('CREATE TABLE IF NOT EXISTS probe (id INTEGER PRIMARY KEY, value TEXT NOT NULL)');
      await store.executeSql('INSERT OR REPLACE INTO probe VALUES (1, ?)', ['harmony-rdb-probe']);
      const rows = await store.querySql('SELECT value FROM probe WHERE id=1');
      try { if (!rows.goToFirstRow() || rows.getString(0) !== 'harmony-rdb-probe') { throw new Error('RDB 读回不一致'); } }
      finally { rows.close(); }
      await store.close(); this.add('PASS RDB CRUD');
      const credential = new Credential(); credential.endpoint = 'ws://127.0.0.1:18783/ws/mobile';
      credential.token = 'compatibility-only-token'; credential.deviceId = 'compatibility-only-device'; credential.gatewayName = 'compatibility-host';
      const preferences = new PreferenceStore(); await preferences.open(this.context);
      // 重启样例不重新种入旧凭据；迁移应当只发生一次。
      if (!(await preferences.read()).legacyMigrated) { await new CredentialStore().save(credential); }
      const repository = new HostRepository(preferences); await repository.load();
      if (!repository.profiles.some(row => row.localId === 'legacy-host')) { throw new Error('迁移资料缺失'); }
      const saved = await new CredentialStore('legacy-host').load();
      if (saved?.token !== credential.token || (await new CredentialStore().load()) !== undefined) { throw new Error('Asset 迁移未完成'); }
      const restored = new HostRepository(preferences); await restored.load();
      if (restored.profiles.length !== repository.profiles.length) { throw new Error('迁移不幂等'); }
      this.add('PASS Asset migration + Preferences reload');
      const recorder = await media.createAVRecorder(); this.add('PASS AVRecorder factory: ' + recorder.state); await recorder.release();
      this.add('PASS Notification query: ' + await notificationManager.isNotificationEnabled());
      this.add('READY picker / ScanKit interaction');
    } catch (error) { this.add('FAIL ' + (error as Error).message); }
  }
  private async photo(): Promise<void> {
    try { const value = await new photoAccessHelper.PhotoViewPicker().select({ MIMEType: photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE, maxSelectNumber: 1 }); this.add('PHOTO returned ' + value.photoUris.length); }
    catch (_) { this.add('PHOTO cancelled/unavailable'); }
  }
  private async file(): Promise<void> {
    if (!this.context) { return; }
    try { const value = await new picker.DocumentViewPicker(this.context).select({ maxSelectNumber: 1 }); this.add('FILE returned ' + value.length); }
    catch (_) { this.add('FILE cancelled/unavailable'); }
  }
  private async scan(): Promise<void> {
    if (!this.context) { return; }
    try { const value = await scanBarcode.startScanForResult(this.context, { scanTypes: [scanCore.ScanType.ALL], enableMultiMode: false }); this.add('SCAN returned type ' + value.scanType); }
    catch (_) { this.add('SCAN cancelled/unavailable'); }
  }
  build() {
    Column({ space: 12 }) {
      Text('鸿蒙兼容性最小样例').fontSize(22).fontWeight(FontWeight.Bold)
      ForEach(this.lines, (line: string) => { Text(line).fontSize(13).width('100%') }, (line: string) => line)
      Row({ space: 12 }) {
        Button('图片选择').id('probe-photo').onClick(() => this.photo())
        Button('文件选择').id('probe-file').onClick(() => this.file())
        Button('扫码').id('probe-scan').onClick(() => this.scan())
      }
      Markdown({ content: '# Native Markdown\n\n**粗体** 与 [链接](https://example.com)\n\n```kotlin\nval sample = 1\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |' }).layoutWeight(1)
    }.padding(20).width('100%').height('100%').backgroundColor('#F9FAFC')
  }
}
