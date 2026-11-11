import { common } from '@kit.AbilityKit';
import { relationalStore } from '@kit.ArkData';
import { LocalDatabase } from '../core/LocalDatabase';
import { CacheWindowData } from '../core/Models';

@Entry
@Component
struct RdbProbe {
  @State private lines: string[] = [];
  private context?: common.UIAbilityContext;
  private add(value: string): void { this.lines = this.lines.concat([value]); }
  private check(value: boolean, message: string): void { if (!value) { throw new Error(message); } }
  aboutToAppear(): void { this.context = this.getUIContext().getHostContext() as common.UIAbilityContext; this.run(); }
  private async run(): Promise<void> {
    if (!this.context) { return; }
    const name = 'harmony-rdb-probe.db';
    try {
      await relationalStore.deleteRdbStore(this.context, name).catch(() => {});
      const old = await relationalStore.getRdbStore(this.context, { name: name, securityLevel: relationalStore.SecurityLevel.S2, encrypt: true });
      await old.executeSql('CREATE TABLE cache_meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)');
      await old.executeSql("INSERT INTO cache_meta VALUES ('schema',1)");
      await old.executeSql('CREATE TABLE host_epochs (host TEXT PRIMARY KEY, epoch INTEGER NOT NULL, removed INTEGER NOT NULL DEFAULT 0)');
      await old.executeSql('CREATE TABLE catalogs (host TEXT PRIMARY KEY, json TEXT NOT NULL, updated INTEGER NOT NULL)');
      await old.executeSql('CREATE TABLE drafts (host TEXT NOT NULL, session TEXT NOT NULL, text TEXT NOT NULL, revision INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(host, session))');
      await old.executeSql('CREATE TABLE history_windows (host TEXT NOT NULL, session TEXT NOT NULL, json TEXT NOT NULL, bytes INTEGER NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(host, session))');
      await old.executeSql("INSERT INTO host_epochs VALUES ('A',0,0)");
      await old.executeSql("INSERT INTO drafts VALUES ('A','s','迁移草稿',4,1)"); await old.close();
      const db = new LocalDatabase(this.context, name); await db.open();
      this.check((await db.draft('A','s'))?.text === '迁移草稿', 'v1草稿未保留'); this.add('PASS encrypted S2 RDB v1 to v3 migration');
      const epoch = await db.register('A'); const second = await db.register('B'); this.check(epoch !== undefined && second !== undefined, '主机注册失败');
      const a = epoch as number; const b = second as number;
      await db.saveDraft('A', a, { sessionId: 's', text: '中文 English draft', revision: 7 });
      await db.saveDraft('A', a, { sessionId: 's', text: '旧写入', revision: 6 });
      this.check((await db.draft('A','s'))?.text === '中文 English draft' && !await db.draft('B','s'), '草稿隔离或版本失败'); this.add('PASS draft revision and host isolation');
      const window: CacheWindowData = { sessionId: 's', json: '{"fixture":true}', documents: [{ id: 'm', text: '原生中文与 English 历史' }] };
      await db.save('A', a, '{"catalog":true}', [window]);
      this.check((await db.search('A','中文')).length === 1 && (await db.search('A','english','s')).length === 1 && !(await db.search('B','中文')).length && !(await db.search('A','%')).length, '索引隔离或字面查询失败'); this.add('PASS Chinese English search and literal wildcard');
      const good: CacheWindowData = { sessionId: 'rollback', json: '{}', documents: [{ id: 'new', text: 'should rollback' }] };
      const bad: CacheWindowData = { sessionId: 'bad', json: 'x'.repeat(3145729), documents: [] };
      let rejected = false; try { await db.save('A', a, 'bad-catalog', [good,bad]); } catch (_) { rejected = true; }
      this.check(rejected && !await db.window('A','rollback') && (await db.catalog('A')) === '{"catalog":true}' && !(await db.search('A','rollback')).length, '事务部分写入'); this.add('PASS transactional history catalog and index rollback');
      const cleared = await db.clear('A', false, false);
      this.check(!await db.save('A', a, 'resurrect', [window]) && !await db.window('A','s') && (await db.search('A','中文')).length === 0 && (await db.draft('A','s'))?.text === '中文 English draft', '清理隔离失败');
      await db.saveDraft('A', cleared, { sessionId: 's', text: 'new epoch revision one', revision: 1 }); await db.saveDraft('B', b, { sessionId: 's', text: 'B isolated', revision: 1 });
      await db.close(); const reopened = new LocalDatabase(this.context, name); await reopened.open();
      this.check((await reopened.draft('A','s'))?.text === 'new epoch revision one', '关闭重开未持久化'); this.add('PASS epoch rejects old writes and close reopen persists');
      await reopened.clear('A', true, true);
      this.check(await reopened.register('A') === undefined && !await reopened.draft('A','s') && (await reopened.draft('B','s'))?.text === 'B isolated', '移除后旧命名空间复活或影响B'); this.add('PASS removed host tombstone preserves other hosts');
      const stats = await reopened.stats('B'); this.check(stats.drafts === 1 && stats.windows === 0 && stats.draftBytes > 0, '真实统计不符'); await reopened.close();
      this.add('PASS actual RDB statistics'); this.add('DONE RDB probe');
    } catch (error) { this.add('FAIL ' + (error as Error).message); }
  }
  build() {
    Column({ space: 14 }) { Text('加密 ArkData RDB 专项').fontSize(22).fontColor('#111111')
      ForEach(this.lines, (line: string) => { Text(line).fontSize(14).fontColor('#111111').width('100%') }, (line: string) => line)
    }.padding(24).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
