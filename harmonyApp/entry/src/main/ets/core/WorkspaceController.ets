import { common } from '@kit.AbilityKit';
import { fileIo as fs } from '@kit.CoreFileKit';
import { taskpool, util } from '@kit.ArkTS';
import { GatewayClient } from './GatewayClient';
import { Command, FileIoData, FileCompletionData, LocalFileData, WorkspaceFilesData, emptyWorkspaceFiles } from './Models';
import { writeWorkspaceFile } from './FileWorker';

class DownloadCopy {
  path: string;
  session: string;
  token: string = '';
  retired: boolean = false;
  complete: boolean = false;
  pinned: boolean = false;
  leases: number = 0;
  bytes: number = 0;
  touched: number = Date.now();
  task?: taskpool.Task;
  constructor(path: string, session: string) { this.path = path; this.session = session; }
}

export class WorkspaceController {
  onChange: (data: WorkspaceFilesData) => void = () => {};
  onReady: (file: LocalFileData) => void = () => {};
  private copies: Map<string, DownloadCopy> = new Map();
  private hostId: string;
  private client: GatewayClient;
  private context: common.UIAbilityContext;
  private directory: string = '';
  private selected: string = '';
  private generation: number = -1;
  private alive: boolean = true;
  private data: WorkspaceFilesData = emptyWorkspaceFiles();
  constructor(client: GatewayClient, context: common.UIAbilityContext, hostId: string) {
    this.client = client; this.context = context; this.hostId = hostId;
    client.onWorkspaceFiles = data => { this.scopeChanged(data.sessionId); this.data = data; this.onChange(data); };
    client.onFileIo = (io, data) => this.write(io, data);
    client.onFileDiscard = id => this.discard(id);
    client.onFileCompletion = file => this.completed(file);
  }
  get snapshot(): WorkspaceFilesData { return this.data; }
  scopeChanged(session: string | undefined): void {
    const next = session ?? ''; const generation = this.client.connectionGeneration;
    if (next === this.selected && generation === this.generation) { return; }
    this.selected = next; this.generation = generation;
    this.copies.forEach((copy: DownloadCopy, id: string) => { if (!copy.complete) { this.discard(id); } });
  }
  private discard(id: string): void {
    const copy = this.copies.get(id); if (!copy) { return; }
    copy.retired = true;
    if (copy.leases > 0) { return; }
    this.copies.delete(id);
    if (copy.task) { try { taskpool.cancel(copy.task); } catch (_) {} }
    fs.unlink(copy.path).catch(() => {});
  }
  private async write(io: FileIoData, data?: string): Promise<void> {
    const generation = this.client.connectionGeneration;
    let copy = this.copies.get(io.transferId);
    if (io.operation === 'create' && !copy) {
      if (!this.directory) { this.directory = this.context.cacheDir + '/workspace-downloads/' + util.generateRandomUUID(true); }
      copy = new DownloadCopy(this.directory + '/' + util.generateRandomUUID(true), io.sessionId); copy.bytes = io.size; this.copies.set(io.transferId, copy);
    }
    const command = new Command('files-write-complete'); command.sessionId = io.sessionId; command.requestKey = io.token; command.outcome = 'failed';
    if (!copy) { if (this.alive) { await this.client.action(command); } return; }
    copy.token = io.token;
    try {
      if (io.operation === 'create') {
        try { await fs.mkdir(this.directory, true); }
        catch (_) { if (!fs.statSync(this.directory).isDirectory()) { throw new Error('下载目录不可用'); } }
      }
      if (!this.alive || copy.retired || this.client.selectedSessionId !== io.sessionId || generation !== this.client.connectionGeneration) { return; }
      const task = new taskpool.Task(writeWorkspaceFile, copy.path, io.operation, data ?? '', io.offset, io.length, io.size); copy.task = task;
      await taskpool.execute(task, taskpool.Priority.HIGH);
      command.outcome = 'ok';
    } catch (_) {}
    finally {
      if (copy.token === io.token) { copy.task = undefined; }
      if (copy.retired || !this.alive) { await fs.unlink(copy.path).catch(() => {}); }
    }
    if (this.alive && !copy.retired && generation === this.client.connectionGeneration && this.client.selectedSessionId === io.sessionId) { await this.client.action(command); }
  }
  private completed(file: FileCompletionData): void {
    const copy = this.copies.get(file.transferId);
    if (!copy || copy.retired || !this.alive || file.sessionId !== this.client.selectedSessionId) { return; }
    copy.complete = true; copy.pinned = true; copy.touched = Date.now(); this.evict();
    this.onReady({ hostId: this.hostId, transferId: file.transferId, sessionId: file.sessionId, remotePath: file.path, localPath: copy.path, name: file.name, mediaType: file.mediaType, size: file.size, sha256: file.sha256 });
  }
  acquire(id: string): (() => void) | undefined {
    const copy = this.copies.get(id);
    if (!this.alive || !copy || !copy.complete || copy.retired) { return undefined; }
    copy.leases++; let released = false;
    return () => {
      if (released) { return; } released = true; copy.leases--;
      if (copy.retired) { this.discard(id); } else { this.evict(); }
    };
  }
  release(id: string): void { const copy = this.copies.get(id); if (copy) { copy.pinned = false; copy.touched = Date.now(); } this.evict(); }
  private evict(): void {
    let bytes = 0; this.copies.forEach(copy => bytes += copy.bytes);
    const rows = Array.from(this.copies.entries()).filter(row => row[1].complete && !row[1].pinned && row[1].leases === 0 && !row[1].retired).sort((a, b) => a[1].touched - b[1].touched);
    for (const row of rows) { if (this.copies.size <= 5 && bytes <= 134217728) { break; } bytes -= row[1].bytes; this.discard(row[0]); }
  }
  dispose(): void { this.alive = false; this.copies.forEach((_copy: DownloadCopy, id: string) => this.discard(id)); this.client.onWorkspaceFiles = () => {}; this.client.onFileIo = () => {}; this.client.onFileDiscard = () => {}; this.client.onFileCompletion = () => {}; this.onChange = () => {}; this.onReady = () => {}; }
}
