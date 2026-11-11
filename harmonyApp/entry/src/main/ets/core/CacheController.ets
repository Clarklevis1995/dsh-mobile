import { GatewayClient } from './GatewayClient';
import { DraftStore } from './DraftStore';
import { CacheExportData, Command, SharedState } from './Models';
import { LocalDatabase, SavedDraft } from './LocalDatabase';

/** 命名空间使用本地主机 ID；磁盘结果不能覆盖新草稿或已到达的网络快照。 */
export class CacheController {
  onDraft: (session: string, text: string) => void = () => {};
  onStatus: (message: string) => void = () => {};
  private host: string;
  private db: LocalDatabase;
  private client: GatewayClient;
  private drafts: DraftStore;
  private epoch: number | undefined = undefined;
  private starting?: Promise<void>;
  private alive: boolean = true;
  private disabled: boolean = false;
  private windowLoads: Map<string, Promise<void>> = new Map();
  private draftLoads: Map<string, Promise<void>> = new Map();
  private clearRevision: number = 0;
  private draftClearRevision: number = 0;
  private pendingDrafts: Map<string, SavedDraft> = new Map();
  private writingSession: string = '';
  private writingDrafts: boolean = false;
  private selected: string = '';
  private generation: number = -1;
  private lastHistory: number = -1;
  private lastCatalog: number = -1;
  private wasGenerating: boolean = false;
  private lastPending: string | undefined = undefined;
  private timer: number = -1;
  private catalog?: string;
  private retryTimer: number = -1;
  private retryDelay: number = 1000;
  constructor(host: string, db: LocalDatabase, client: GatewayClient, drafts: DraftStore) {
    this.host = host; this.db = db; this.client = client; this.drafts = drafts;
    drafts.onChanged = (session, text, revision) => { this.pendingDrafts.set(session, { sessionId: session, text: text, revision: revision }); this.flushDrafts(); };
    client.onPrepared = preserved => this.restore(preserved);
    client.onCache = data => this.persist(data);
  }
  private initialize(): Promise<void> {
    if (!this.starting) { this.starting = this.db.register(this.host).then(epoch => { this.epoch = epoch; this.disabled = epoch === undefined; }).catch(() => { this.disabled = true; this.onStatus('本地缓存暂不可用，请在存储设置中检查'); }); }
    return this.starting;
  }
  private async restore(preserved: boolean): Promise<void> {
    await this.initialize(); if (!this.alive || this.disabled) { return; }
    if (!preserved) { this.windowLoads.clear(); this.selected = ''; this.lastHistory = -1; this.lastCatalog = -1; }
    const generation = this.client.connectionGeneration;
    try {
      const catalog = await this.db.catalog(this.host);
      if (!this.alive || generation !== this.client.connectionGeneration) { return; }
      if (catalog) { const command = new Command('cache-import-catalog'); command.input = catalog; await this.client.action(command); }
      const session = this.client.selectedSessionId;
      if (session) { await this.loadDraft(session); await this.loadWindow(session); }
    } catch (_) { if (this.alive) { this.onStatus('离线缓存读取失败，联网同步不受影响'); } }
  }
  stateChanged(state: SharedState): void {
    const generation = this.client.connectionGeneration;
    if (generation !== this.generation) {
      this.generation = generation; this.windowLoads.clear(); this.lastHistory = -1; this.lastCatalog = -1; this.drafts.invalidateSubmissions();
    }
    if (this.lastPending && state.pendingMessageSessionId !== this.lastPending) { this.drafts.failed(this.lastPending); }
    this.lastPending = state.pendingMessageSessionId;
    const selected = state.selectedSessionId ?? '';
    if (selected !== this.selected) {
      this.selected = selected;
      if (selected) { this.loadDraft(selected); this.loadWindow(selected); }
      this.drafts.prune(selected, 16, Array.from(this.pendingDrafts.keys()).concat([this.writingSession])).forEach(session => this.draftLoads.delete(session));
    }
    const changed = state.historyRevision !== this.lastHistory || (state.catalogRevision ?? 0) !== this.lastCatalog || (!state.generating && this.wasGenerating);
    this.lastHistory = state.historyRevision; this.lastCatalog = state.catalogRevision ?? 0; this.wasGenerating = state.generating;
    if (changed && state.connected && !state.generating && !this.disabled) {
      clearTimeout(this.timer); const generation = this.generation;
      this.timer = setTimeout(() => { if (this.alive && generation === this.client.connectionGeneration) { this.client.action(new Command('cache-export')).catch(() => {}); } }, 450);
    }
  }
  private loadDraft(session: string): Promise<void> {
    if (this.drafts.has(session)) { return Promise.resolve(); }
    const pending = this.draftLoads.get(session); if (pending) { return pending; }
    const ticket = this.clearRevision;
    const action = async (): Promise<void> => {
      await this.initialize(); if (this.disabled || !this.alive || ticket !== this.clearRevision) { return; }
      const value = await this.db.draft(this.host, session);
      if (!this.alive || ticket !== this.clearRevision) { return; }
      if (value && this.drafts.restore(session, value.text, value.revision)) { this.onDraft(session, value.text); }
    };
    const task = action().catch(() => { if (this.alive) { this.onStatus('部分草稿读取失败，请检查本地存储'); } });
    this.draftLoads.set(session, task); task.finally(() => { if (this.draftLoads.get(session) === task) { this.draftLoads.delete(session); } }); return task;
  }
  private loadWindow(session: string): Promise<void> {
    const pending = this.windowLoads.get(session); if (pending) { return pending; }
    const generation = this.client.connectionGeneration; const ticket = this.clearRevision;
    const action = async (): Promise<void> => {
      await this.initialize(); if (!this.alive || this.disabled) { return; }
      const json = await this.db.window(this.host, session);
      if (json && this.alive && ticket === this.clearRevision && generation === this.client.connectionGeneration && session === this.client.selectedSessionId) { const command = new Command('cache-import-window'); command.sessionId = session; command.input = json; await this.client.action(command); }
    };
    const task = action().catch(() => { if (this.alive) { this.onStatus('历史缓存读取失败，请连接网关重试'); } });
    this.windowLoads.set(session, task); task.finally(() => { if (this.windowLoads.get(session) === task) { this.windowLoads.delete(session); } }); return task;
  }
  private async flushDrafts(): Promise<void> {
    if (this.writingDrafts) { return; } this.writingDrafts = true;
    let current: SavedDraft | undefined = undefined; const attempt = this.draftClearRevision;
    try {
      await this.initialize();
      while (this.alive && !this.disabled && this.epoch !== undefined && this.pendingDrafts.size) {
        const first = this.pendingDrafts.entries().next().value as [string, SavedDraft];
        current = first[1]; this.pendingDrafts.delete(first[0]); this.writingSession = first[0]; const epoch = this.epoch; const ticket = this.draftClearRevision;
        const saved = await this.db.saveDraft(this.host, epoch, first[1]);
        if (!saved && this.alive && epoch !== this.epoch && ticket === this.draftClearRevision && !this.pendingDrafts.has(first[0])) { this.pendingDrafts.set(first[0], first[1]); }
        current = undefined; this.retryDelay = 1000;
      }
    } catch (_) {
      if (this.alive && attempt === this.draftClearRevision) {
        if (current && !this.pendingDrafts.has(current.sessionId)) { this.pendingDrafts.set(current.sessionId, current); }
        this.onStatus('草稿尚未保存，将自动重试，请检查本地存储'); clearTimeout(this.retryTimer);
        this.retryTimer = setTimeout(() => { this.retryTimer = -1; this.flushDrafts(); }, this.retryDelay);
        this.retryDelay = Math.min(30000, this.retryDelay * 2);
      }
    }
    finally { this.writingSession = ''; this.writingDrafts = false; this.drafts.prune(this.selected, 16, Array.from(this.pendingDrafts.keys())).forEach(session => this.draftLoads.delete(session)); }
  }
  private async persist(data: CacheExportData): Promise<void> {
    if (!this.alive) { return; } const ticket = this.clearRevision;
    await this.initialize(); if (!this.alive || ticket !== this.clearRevision || this.disabled || this.epoch === undefined) { return; }
    const epoch = this.epoch;
    const catalog = data.catalog && data.catalog !== this.catalog ? data.catalog : undefined;
    // 导出已由历史版本与防抖控制；不为去重再常驻最多八份 2MiB 历史字符串。
    const windows = data.windows;
    if (!catalog && !windows.length) { return; }
    try {
      if (await this.db.save(this.host, epoch, catalog, windows) && epoch === this.epoch && this.alive) {
        if (catalog) { this.catalog = catalog; }
      }
    } catch (_) { if (this.alive) { this.onStatus('历史缓存未保存，请检查本地存储'); } }
  }
  async clear(includeDrafts: boolean = false): Promise<void> {
    this.clearRevision++; if (includeDrafts) { this.draftClearRevision++; this.pendingDrafts.clear(); clearTimeout(this.retryTimer); }
    await this.initialize(); clearTimeout(this.timer);
    this.epoch = await this.db.clear(this.host, false, includeDrafts); this.catalog = undefined; this.windowLoads.clear();
    if (includeDrafts) { this.drafts.clear(); this.draftLoads.clear(); this.onDraft(this.client.selectedSessionId ?? '', ''); }
    if (!includeDrafts) { this.flushDrafts(); }
    this.onStatus('本地缓存已清理');
  }
  async removed(): Promise<void> { this.dispose(); await this.db.clear(this.host, true, true); }
  dispose(): void {
    // 页面销毁不等于删除资料；在切断观察前把已排队的草稿交给数据库串行队列。
    const pending = Array.from(this.pendingDrafts.values());
    const enqueue = (epoch: number): void => { pending.forEach(value => this.db.saveDraft(this.host, epoch, value).catch(() => {})); };
    if (this.epoch !== undefined) { enqueue(this.epoch); } else if (pending.length) { this.initialize().then(() => { if (this.epoch !== undefined) { enqueue(this.epoch); } }); }
    this.clearRevision++; this.alive = false; clearTimeout(this.timer); clearTimeout(this.retryTimer); this.pendingDrafts.clear(); this.client.onPrepared = async () => {}; this.client.onCache = () => {}; this.drafts.onChanged = () => {}; this.onDraft = () => {}; this.onStatus = () => {}; }
}
