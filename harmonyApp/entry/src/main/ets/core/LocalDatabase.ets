import { common } from '@kit.AbilityKit';
import { relationalStore } from '@kit.ArkData';
import { CacheDocumentData, CacheWindowData } from './Models';

export interface SavedDraft { sessionId: string; text: string; revision: number; }
export interface OfflineSearchRow { sessionId: string; messageId: string; text: string; }
export interface StorageStats { windows: number; historyBytes: number; drafts: number; draftBytes: number; }

/** 真实 ArkData 加密 RDB。所有操作串行，写入必须匹配主机清理 epoch。 */
export class LocalDatabase {
  private name: string;
  private context: common.UIAbilityContext;
  private db?: relationalStore.RdbStore;
  private opening?: Promise<void>;
  private queue: Promise<void> = Promise.resolve();
  constructor(context: common.UIAbilityContext, name: string = 'whaleness-cache.db') { this.context = context; this.name = name; }
  open(): Promise<void> {
    if (!this.opening) { const operation = this.initialize(); this.opening = operation; operation.catch(() => { if (this.opening === operation) { this.opening = undefined; } }); } return this.opening;
  }
  private async initialize(): Promise<void> {
    const db = await relationalStore.getRdbStore(this.context, { name: this.name, securityLevel: relationalStore.SecurityLevel.S2, encrypt: true });
    const tx = await db.createTransaction();
    try {
      await tx.execute('CREATE TABLE IF NOT EXISTS cache_meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)');
      const result = await tx.querySql("SELECT value FROM cache_meta WHERE key='schema'");
      let version = 0; try { if (result.goToFirstRow()) { version = result.getLong(0); } } finally { result.close(); }
      if (version > 3) { throw new Error('本地缓存格式来自较新版本，请升级应用'); }
      if (version < 1) {
        await tx.execute('CREATE TABLE host_epochs (host TEXT PRIMARY KEY, epoch INTEGER NOT NULL, removed INTEGER NOT NULL DEFAULT 0)');
        await tx.execute('CREATE TABLE catalogs (host TEXT PRIMARY KEY, json TEXT NOT NULL, updated INTEGER NOT NULL)');
        await tx.execute('CREATE TABLE drafts (host TEXT NOT NULL, session TEXT NOT NULL, text TEXT NOT NULL, revision INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(host, session))');
        await tx.execute('CREATE TABLE history_windows (host TEXT NOT NULL, session TEXT NOT NULL, json TEXT NOT NULL, bytes INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(host, session))');
      }
      if (version < 2) {
        await tx.execute('CREATE TABLE search_documents (host TEXT NOT NULL, session TEXT NOT NULL, message TEXT NOT NULL, text TEXT NOT NULL, PRIMARY KEY(host, session, message))');
        await tx.execute('CREATE INDEX history_lru ON history_windows(updated)');
      }
      if (version < 3) { await tx.execute('ALTER TABLE drafts ADD COLUMN epoch INTEGER NOT NULL DEFAULT 0'); }
      await tx.execute("INSERT OR REPLACE INTO cache_meta(key,value) VALUES ('schema',3)");
      await tx.commit(); this.db = db;
    } catch (failure) { await tx.rollback().catch(() => {}); await db.close(); throw new Error(String(failure)); }
  }
  private run<T>(operation: (db: relationalStore.RdbStore) => Promise<T>): Promise<T> {
    const action = async (): Promise<T> => { await this.open(); if (!this.db) { throw new Error('本地缓存暂不可用'); } return operation(this.db); };
    const result = this.queue.then(action, action); this.queue = result.then(() => {}, () => {}); return result;
  }
  private async current(tx: relationalStore.Transaction, host: string, epoch: number): Promise<boolean> {
    const result = await tx.querySql('SELECT epoch,removed FROM host_epochs WHERE host=?', [host]);
    try { return result.goToFirstRow() && result.getLong(0) === epoch && result.getLong(1) === 0; } finally { result.close(); }
  }
  register(host: string): Promise<number | undefined> {
    return this.run(async db => {
      const tx = await db.createTransaction();
      try {
        await tx.execute('INSERT OR IGNORE INTO host_epochs(host,epoch,removed) VALUES (?,0,0)', [host]);
        await tx.execute('UPDATE host_epochs SET epoch=epoch+1 WHERE host=? AND removed=0', [host]);
        const result = await tx.querySql('SELECT epoch,removed FROM host_epochs WHERE host=?', [host]);
        let value: number | undefined = undefined; try { if (result.goToFirstRow() && result.getLong(1) === 0) { value = result.getLong(0); } } finally { result.close(); }
        await tx.commit(); return value;
      } catch (failure) { await tx.rollback().catch(() => {}); throw new Error(String(failure)); }
    });
  }
  catalog(host: string): Promise<string | undefined> {
    return this.run(async db => { const result = await db.querySql('SELECT json FROM catalogs WHERE host=? AND EXISTS(SELECT 1 FROM host_epochs WHERE host=? AND removed=0)', [host, host]); try { return result.goToFirstRow() ? result.getString(0) : undefined; } finally { result.close(); } });
  }
  draft(host: string, session: string): Promise<SavedDraft | undefined> {
    return this.run(async db => {
      const result = await db.querySql('SELECT text,revision FROM drafts WHERE host=? AND session=? AND EXISTS(SELECT 1 FROM host_epochs WHERE host=? AND removed=0)', [host, session, host]);
      try { if (!result.goToFirstRow()) { return undefined; } const value: SavedDraft = { sessionId: session, text: result.getString(0), revision: result.getLong(1) }; return value; } finally { result.close(); }
    });
  }
  window(host: string, session: string): Promise<string | undefined> {
    return this.run(async db => {
      const result = await db.querySql('SELECT json FROM history_windows WHERE host=? AND session=? AND EXISTS(SELECT 1 FROM host_epochs WHERE host=? AND removed=0)', [host, session, host]);
      let json: string | undefined = undefined; try { if (result.goToFirstRow()) { json = result.getString(0); } } finally { result.close(); }
      if (json !== undefined) { await db.executeSql('UPDATE history_windows SET updated=? WHERE host=? AND session=?', [Date.now(), host, session]); } return json;
    });
  }
  saveDraft(host: string, epoch: number, value: SavedDraft): Promise<boolean> {
    return this.run(async db => {
      const tx = await db.createTransaction();
      try {
        if (!await this.current(tx, host, epoch)) { await tx.rollback(); return false; }
        await tx.execute('INSERT INTO drafts(host,session,text,revision,updated,epoch) VALUES (?,?,?,?,?,?) ON CONFLICT(host,session) DO UPDATE SET text=excluded.text,revision=excluded.revision,updated=excluded.updated,epoch=excluded.epoch WHERE excluded.epoch>drafts.epoch OR (excluded.epoch=drafts.epoch AND excluded.revision>=drafts.revision)', [host, value.sessionId, value.text, value.revision, Date.now(), epoch]);
        await tx.commit(); return true;
      } catch (failure) { await tx.rollback().catch(() => {}); throw new Error(String(failure)); }
    });
  }
  save(host: string, epoch: number, catalog: string | undefined, windows: CacheWindowData[]): Promise<boolean> {
    return this.run(async db => {
      const tx = await db.createTransaction();
      try {
        if (!await this.current(tx, host, epoch)) { await tx.rollback(); return false; }
        if (catalog !== undefined) { await tx.execute('INSERT OR REPLACE INTO catalogs(host,json,updated) VALUES (?,?,?)', [host, catalog, Date.now()]); }
        for (const window of windows) {
          if (window.json.length > 3145728 || window.documents.length > 800) { throw new Error('缓存窗口超出预算'); }
          const docs: relationalStore.ValuesBucket[] = window.documents.map((doc: CacheDocumentData): relationalStore.ValuesBucket => { return { host: host, session: window.sessionId, message: doc.id, text: doc.text }; });
          await tx.execute('INSERT OR REPLACE INTO history_windows(host,session,json,bytes,updated) VALUES (?,?,?,length(CAST(? AS BLOB)),?)', [host, window.sessionId, window.json, window.json, Date.now()]);
          await tx.execute('DELETE FROM search_documents WHERE host=? AND session=?', [host, window.sessionId]);
          if (docs.length) { await tx.batchInsert('search_documents', docs); }
        }
        const rows = await tx.querySql('SELECT host,session,bytes FROM history_windows ORDER BY updated DESC');
        let retained = 0; const remove: string[][] = [];
        try { if (rows.goToFirstRow()) { do { retained += rows.getLong(2) * 2; if (retained > 134217728) { remove.push([rows.getString(0), rows.getString(1)]); } } while (rows.goToNextRow()); } } finally { rows.close(); }
        for (const row of remove) { await tx.execute('DELETE FROM history_windows WHERE host=? AND session=?', row); await tx.execute('DELETE FROM search_documents WHERE host=? AND session=?', row); }
        await tx.commit(); return true;
      } catch (failure) { await tx.rollback().catch(() => {}); throw new Error(String(failure)); }
    });
  }
  search(host: string, query: string, session?: string): Promise<OfflineSearchRow[]> {
    return this.run(async db => {
      const value = query.trim(); if (!value || value.length > 256) { return []; }
      const args: relationalStore.ValueType[] = [host, value]; if (session) { args.push(session); }
      const result = await db.querySql('SELECT session,message,text FROM search_documents WHERE host=? AND instr(lower(text),lower(?))>0' + (session ? ' AND session=?' : '') + ' LIMIT 80', args);
      const rows: OfflineSearchRow[] = [];
      try { if (result.goToFirstRow()) { do { const text = result.getString(2); const at = text.toLocaleLowerCase().indexOf(value.toLocaleLowerCase()); rows.push({ sessionId: result.getString(0), messageId: result.getString(1), text: text.substring(Math.max(0, at - 60), Math.max(0, at) + value.length + 180) }); } while (result.goToNextRow()); } return rows; } finally { result.close(); }
    });
  }
  clear(host: string, removeHost: boolean, drafts: boolean = false): Promise<number> {
    return this.run(async db => {
      const tx = await db.createTransaction();
      try {
        await tx.execute('INSERT OR IGNORE INTO host_epochs(host,epoch,removed) VALUES (?,0,0)', [host]);
        await tx.execute('UPDATE host_epochs SET epoch=epoch+1,removed=? WHERE host=?', [removeHost ? 1 : 0, host]);
        await tx.execute('DELETE FROM history_windows WHERE host=?', [host]); await tx.execute('DELETE FROM search_documents WHERE host=?', [host]);
        if (removeHost) { await tx.execute('DELETE FROM catalogs WHERE host=?', [host]); }
        if (removeHost || drafts) { await tx.execute('DELETE FROM drafts WHERE host=?', [host]); }
        const result = await tx.querySql('SELECT epoch FROM host_epochs WHERE host=?', [host]); let epoch = 0; try { if (result.goToFirstRow()) { epoch = result.getLong(0); } } finally { result.close(); }
        await tx.commit(); return epoch;
      } catch (failure) { await tx.rollback().catch(() => {}); throw new Error(String(failure)); }
    });
  }
  stats(host: string): Promise<StorageStats> {
    return this.run(async db => {
      const windows = await db.querySql('SELECT count(*),coalesce(sum(bytes),0) FROM history_windows WHERE host=?', [host]);
      let count = 0; let bytes = 0; try { if (windows.goToFirstRow()) { count = windows.getLong(0); bytes = windows.getLong(1); } } finally { windows.close(); }
      const drafts = await db.querySql("SELECT count(*),coalesce(sum(length(CAST(text AS BLOB))),0) FROM drafts WHERE host=? AND text<>''", [host]);
      try { drafts.goToFirstRow(); const value: StorageStats = { windows: count, historyBytes: bytes, drafts: drafts.getLong(0), draftBytes: drafts.getLong(1) }; return value; } finally { drafts.close(); }
    });
  }
  async close(): Promise<void> { await this.queue; await this.db?.close(); this.db = undefined; this.opening = undefined; }
}
