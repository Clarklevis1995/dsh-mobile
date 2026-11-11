import { fileIo as fs } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { taskpool, util } from '@kit.ArkTS';
import { GatewayClient } from './GatewayClient';
import { AttachmentData, Command, GatewayImageData, ImageAssetData } from './Models';
import { cacheMessageImage } from './FileWorker';

class ImageEntry {
  session: string;
  image: GatewayImageData;
  key: string = '';
  original: string = '';
  thumbnail: string = '';
  loading: boolean = true;
  error: string = '';
  touched: number = Date.now();
  constructor(session: string, image: GatewayImageData) { this.session = session; this.image = image; }
}

/** 元数据来自 Native 的引用校验；ArkTS 只缓存私有图片文件和调度串行读取。 */
export class ImageCache {
  onChange: () => void = () => {};
  private client: GatewayClient;
  private context: common.UIAbilityContext;
  private entries: Map<string, ImageEntry> = new Map();
  private queue: ImageEntry[] = [];
  private active?: ImageEntry;
  private selected: string = '';
  private generation: number = -1;
  private epoch: number = 0;
  private directory: string = '';
  private alive: boolean = true;
  private pinned: Set<string> = new Set();
  constructor(client: GatewayClient, context: common.UIAbilityContext) {
    this.client = client; this.context = context; client.onAttachment = (value, data) => this.received(value, data);
  }
  private address(session: string, id: string): string { return JSON.stringify([session, id]); }
  scopeChanged(session: string | undefined): void {
    const next = session ?? ''; const generation = this.client.connectionGeneration;
    if (next === this.selected && generation === this.generation) { return; }
    this.selected = next; this.generation = generation; this.epoch++; this.active = undefined; this.queue = [];
    this.entries.forEach(entry => { if (entry.loading) { entry.loading = false; entry.error = '图片读取已中断，请重试'; } }); this.onChange();
  }
  snapshot(): Record<string, ImageAssetData> {
    const result: Record<string, ImageAssetData> = {};
    this.entries.forEach(entry => { if (entry.session === this.selected) { result['asset:' + entry.image.attachmentId] = { path: entry.original ? 'file://' + entry.original : '', thumbnail: entry.thumbnail ? 'file://' + entry.thumbnail : '', loading: entry.loading, error: entry.error }; } });
    return result;
  }
  require(image: GatewayImageData, retry: boolean = false): void {
    this.scopeChanged(this.client.selectedSessionId);
    if (!this.selected || !this.client.connected) { return; }
    const key = this.address(this.selected, image.attachmentId); const existing = this.entries.get(key);
    if (existing && (!existing.error || !retry)) { existing.touched = Date.now(); return; }
    const entry = new ImageEntry(this.selected, image); this.entries.set(key, entry); this.queue.push(entry); this.onChange(); this.pump();
  }
  private async pump(): Promise<void> {
    if (!this.alive || this.active || !this.queue.length) { return; }
    const entry = this.queue.shift()!;
    if (entry.session !== this.client.selectedSessionId) { entry.loading = false; return; }
    this.active = entry; const epoch = this.epoch;
    const command = new Command('attachment-open'); command.sessionId = entry.session; command.itemId = entry.image.attachmentId;
    try { await this.client.action(command); } catch (_) {}
    if (epoch === this.epoch && this.active === entry && !entry.key) { this.failed(entry, '当前图片暂不可读取，请重试'); }
  }
  private failed(entry: ImageEntry, message: string): void { entry.loading = false; entry.error = message; if (this.active === entry) { this.active = undefined; } this.onChange(); this.pump(); }
  private async received(value: AttachmentData, data?: string): Promise<void> {
    const entry = this.active;
    if (!entry || entry.session !== value.sessionId || entry.image.attachmentId !== value.image.attachmentId) { return; }
    if (value.status === 'loading') { entry.key = value.key; return; }
    if (value.key !== entry.key) { return; }
    if (value.status !== 'ready' || !data) { this.failed(entry, value.error ?? '图片读取失败，请重试'); return; }
    const epoch = this.epoch;
    if (!this.directory) { this.directory = this.context.cacheDir + '/message-images/' + util.generateRandomUUID(true); }
    const base = this.directory + '/' + util.generateRandomUUID(true); const thumbnail = base + '.jpg';
    try {
      await fs.mkdir(this.directory, true).catch(() => {});
      await taskpool.execute(new taskpool.Task(cacheMessageImage, data, value.image.bytes, base, thumbnail), taskpool.Priority.HIGH);
      if (!this.alive || epoch !== this.epoch) { await fs.unlink(base).catch(() => {}); await fs.unlink(thumbnail).catch(() => {}); return; }
      entry.original = base; entry.thumbnail = thumbnail; entry.loading = false; entry.error = ''; entry.touched = Date.now();
      this.active = undefined; this.evict(); this.onChange(); this.pump();
    } catch (_) {
      await fs.unlink(base).catch(() => {}); await fs.unlink(thumbnail).catch(() => {});
      if (epoch === this.epoch && this.alive) { this.failed(entry, '图片解码或保存失败，请重试'); }
    }
  }
  pin(ids: string[]): string[] {
    this.pinned = new Set(ids.map(id => this.address(this.selected, id)));
    return ids.map(id => { const entry = this.entries.get(this.address(this.selected, id)); if (entry?.original) { entry.touched = Date.now(); return 'file://' + entry.original; } return ''; }).filter(path => !!path);
  }
  releasePreview(): void { this.pinned.clear(); this.evict(); }
  private evict(): void {
    let bytes = 0; this.entries.forEach(entry => { if (entry.original) { bytes += entry.image.bytes + 1048576; } });
    const rows = Array.from(this.entries.entries()).filter(row => !row[1].loading && !this.pinned.has(row[0])).sort((left, right) => left[1].touched - right[1].touched);
    for (const row of rows) {
      if (this.entries.size <= 32 && bytes <= 67108864) { break; }
      this.entries.delete(row[0]); const entry = row[1]; bytes -= entry.original ? entry.image.bytes + 1048576 : 0;
      if (entry.original) { fs.unlink(entry.original).catch(() => {}); } if (entry.thumbnail) { fs.unlink(entry.thumbnail).catch(() => {}); }
    }
  }
  dispose(): void { this.alive = false; this.epoch++; this.client.onAttachment = () => {}; this.queue = []; this.active = undefined; this.entries.forEach(entry => { if (entry.original) { fs.unlink(entry.original).catch(() => {}); } if (entry.thumbnail) { fs.unlink(entry.thumbnail).catch(() => {}); } }); this.entries.clear(); this.onChange = () => {}; }
}
