import { BusinessError } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { fileIo as fs, fileUri, picker } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { taskpool, util } from '@kit.ArkTS';
import { GatewayClient } from './GatewayClient';
import { Command, UploadData, UploadReadData, emptyUploads } from './Models';
import { preparePhoto } from './PhotoWorker';
import { prepareAttachment, readAttachment } from './FileWorker';

interface PreparedAttachment { size: number; sha256: string; width?: number; height?: number; }

/** 每个 Host 独占；选择器及后台准备结果同时检查会话版本和连接代次。 */
export class FileController {
  onChange: (data: UploadData, preparing: boolean) => void = () => {};
  onError: (message: string) => void = () => {};
  private data: UploadData = emptyUploads();
  private files: Map<string, string> = new Map();
  private directory: string = '';
  private selected: string = '';
  private version: number = 0;
  private busy: boolean = false;
  private alive: boolean = true;
  private client: GatewayClient;
  constructor(client: GatewayClient) {
    this.client = client;
    client.onUploads = data => this.received(data);
    client.onUploadRead = read => this.read(read);
  }
  get snapshot(): UploadData { return this.data; }
  path(id: string): string { const path = this.files.get(id); return path ? 'file://' + path : ''; }
  get preparing(): boolean { return this.busy; }
  private publish(): void { this.onChange(this.data, this.busy); }
  scopeChanged(session: string | undefined): void {
    const next = session ?? ''; if (next === this.selected) { return; }
    this.selected = next; this.version++; this.busy = false; this.clearCopies(); this.publish();
  }
  private received(data: UploadData): void {
    this.scopeChanged(data.sessionId); this.data = data;
    const ids = new Set(data.drafts.map(row => row.id).concat(data.photos.map(row => row.id)));
    this.files.forEach((path: string, id: string) => { if (!ids.has(id)) { this.files.delete(id); fs.unlink(path).catch(() => {}); } });
    this.publish();
  }
  private clearCopies(): void { this.files.forEach((path: string) => fs.unlink(path).catch(() => {})); this.files.clear(); }
  dispose(): void { this.alive = false; this.version++; this.busy = false; this.clearCopies(); this.client.onUploads = () => {}; this.client.onUploadRead = () => {}; this.onChange = () => {}; this.onError = () => {}; }
  private valid(session: string, version: number, generation: number): boolean {
    return this.alive && this.selected === session && this.version === version && this.client.selectedSessionId === session && this.client.connectionGeneration === generation;
  }
  async choose(context: common.UIAbilityContext, images: boolean): Promise<void> {
    if (this.busy || !(images ? this.data.photosAvailable : this.data.available) || !this.selected) { return; }
    const session = this.selected; const version = this.version; const generation = this.client.connectionGeneration;
    this.busy = true; this.publish();
    try {
      const count = Math.max(0, Math.min(images ? 4 : 20, images ? 4 - this.data.photos.length : 20 - this.data.drafts.length)); if (!count) { return; }
      let uris: string[] = [];
      if (images) {
        const result = await new photoAccessHelper.PhotoViewPicker().select({ MIMEType: photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE, maxSelectNumber: count }); uris = result.photoUris;
      } else { uris = await new picker.DocumentViewPicker(context).select({ maxSelectNumber: count }); }
      if (!this.valid(session, version, generation)) { return; }
      if (!this.directory) { this.directory = context.cacheDir + '/attachment-drafts/' + util.generateRandomUUID(true); await fs.mkdir(this.directory, true); }
      for (const uri of uris.slice(0, count)) {
        if (!this.valid(session, version, generation)) { break; }
        const id = util.generateRandomUUID(true); const path = this.directory + '/' + id;
        try {
          let name = new fileUri.FileUri(uri).name;
          if (!name.trim() || name.length > 255 || /[\/\\\x00-\x1F\x7F]/.test(name) || name === '.' || name === '..') { throw new Error('文件名无效'); }
          const task = images ? new taskpool.Task(preparePhoto, uri, path) : new taskpool.Task(prepareAttachment, uri, path);
          const prepared = JSON.parse(await taskpool.execute(task, taskpool.Priority.HIGH) as string) as PreparedAttachment;
          if (!this.valid(session, version, generation)) { await fs.unlink(path).catch(() => {}); continue; }
          this.files.set(id, path);
          if (images) { const dot = name.lastIndexOf('.'); name = (dot > 0 ? name.substring(0, dot) : name).substring(0, 240).replace(/[\uD800-\uDBFF]$/, '') + '.jpg'; }
          const command = new Command(images ? 'image-add' : 'file-add'); command.sessionId = session; command.itemId = id; command.fileName = name; command.byteSize = prepared.size; command.sha256 = prepared.sha256;
          if (images) { command.input = await taskpool.execute(new taskpool.Task(readAttachment, path, 0, prepared.size), taskpool.Priority.HIGH) as string; command.imageWidth = prepared.width ?? 0; command.imageHeight = prepared.height ?? 0; }
          if (!this.valid(session, version, generation)) { this.files.delete(id); await fs.unlink(path).catch(() => {}); continue; }
          await this.client.action(command);
          if (!this.data.drafts.some(row => row.id === id) && !this.data.photos.some(row => row.id === id)) { this.files.delete(id); await fs.unlink(path).catch(() => {}); }
        } catch (_) {
          this.files.delete(id); await fs.unlink(path).catch(() => {});
          if (this.valid(session, version, generation)) { this.onError('文件准备失败，请检查文件大小或重新选择'); }
        }
      }
    } catch (failure) {
      const code = (failure as BusinessError).code;
      if (this.valid(session, version, generation) && code !== 13900042 && code !== 16000050) { this.onError('无法选择文件，请检查访问权限或重试'); }
    } finally { if (this.alive && version === this.version) { this.busy = false; this.publish(); } }
  }
  private async read(read: UploadReadData): Promise<void> {
    const generation = this.client.connectionGeneration; const version = this.version; const path = this.files.get(read.id);
    const command = new Command(path ? 'file-chunk' : 'file-read-failed'); command.sessionId = read.sessionId; command.requestKey = read.token;
    try { if (path) { command.text = await taskpool.execute(new taskpool.Task(readAttachment, path, read.offset, read.length), taskpool.Priority.HIGH) as string; command.byteCount = read.length; } }
    catch (_) { command.op = 'file-read-failed'; command.text = ''; }
    if (this.valid(read.sessionId, version, generation)) { await this.client.action(command); }
  }
}
