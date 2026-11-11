// 实际 ArkTS 协调代码 + 可控 I/O；RDB 本身另有真实 SDK F 专项。
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js');
const root = path.resolve(__dirname, '../../harmonyApp/entry/src/main/ets/core');
function load(name, cache = new Map()) {
  if (cache.has(name)) return cache.get(name);
  const code = ts.transpileModule(fs.readFileSync(path.join(root, name + '.ets'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 } }).outputText;
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, require: name => load(name.replace('./', ''), cache), setTimeout, clearTimeout, DataOperationType: { ADD: 'add', DELETE: 'delete', RELOAD: 'reload', CHANGE: 'change' } });
  cache.set(name, module.exports); return module.exports;
}
const { DraftStore } = load('DraftStore');
const { CacheController } = load('CacheController');
const { MessageDataSource } = load('MessageDataSource');
const turn = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
function setup() {
  const calls = []; const writes = []; let epoch = 1;
  const db = { async register() { return epoch; }, async catalog() {}, async draft() {}, async window() {}, async save(...args) { writes.push(args); return true; }, async saveDraft() { return true; }, async clear() { return ++epoch; } };
  const client = { connectionGeneration: 1, selectedSessionId: 's', async action(command) { calls.push(command); } };
  const drafts = new DraftStore(); const controller = new CacheController('host-A', db, client, drafts);
  return { db, client, drafts, controller, calls, writes };
}
test('迟到磁盘草稿不覆盖用户已经修改的文字，恢复不是编辑事件', async () => {
  const e = setup(); const gate = deferred(); const changed = [];
  e.db.draft = () => gate.promise; e.controller.onDraft = (...args) => changed.push(args);
  const restoring = e.client.onPrepared(false); await turn(); e.drafts.changed('s', '新输入'); gate.resolve({ sessionId: 's', text: '旧磁盘', revision: 9 }); await restoring;
  assert.equal(e.drafts.switchSession(undefined, 's', ''), '新输入'); assert.equal(changed.length, 0); e.controller.dispose();
});
test('清理时仍在等待初始化的旧历史导出不能用新 epoch 写回', async () => {
  const e = setup(); const gate = deferred(); e.db.register = () => gate.promise;
  const old = e.client.onCache({ catalog: 'old', windows: [] }); const clearing = e.controller.clear(false); gate.resolve(1); await Promise.all([old, clearing]);
  assert.equal(e.writes.length, 0); e.controller.dispose();
});
test('清理期间迟到窗口不导入 Native，完成后的读取任务不常驻内存', async () => {
  const e = setup(); const gate = deferred(); e.db.window = () => gate.promise;
  const restoring = e.client.onPrepared(false); await turn(); await e.controller.clear(false); gate.resolve('old-window'); await restoring; await turn();
  assert.equal(e.calls.filter(row => row.op === 'cache-import-window').length, 0); assert.equal(e.controller.windowLoads.size, 0); e.controller.dispose();
});
test('恢复草稿可被淘汰后重载，未确认提交和未落盘项保留', () => {
  const drafts = new DraftStore(); const notifications = []; drafts.onChanged = (...args) => notifications.push(args);
  assert.equal(drafts.changed('s', ''), false); assert.equal(drafts.restore('s', '原稿', 8), true); assert.equal(notifications.length, 0);
  drafts.sent('s', '原稿'); for (let index = 0; index < 20; index++) drafts.changed('s' + index, '稿' + index);
  drafts.prune('s19', 3, ['s0']); assert.equal(drafts.has('s'), true); assert.equal(drafts.has('s0'), true); assert.equal(drafts.has('s1'), false);
  assert.equal(drafts.restore('s1', '重载', 1), true); drafts.invalidateSubmissions(); drafts.prune('s19', 3); assert.equal(drafts.has('s'), false);
});
function source() {
  const source = new MessageDataSource(); const events = [];
  source.registerDataChangeListener({ onDataReloaded() { throw new Error('禁止混用旧通知'); }, onDatasetChange(ops) { events.push(JSON.parse(JSON.stringify(ops))); }, onDataChange() { throw new Error('禁止混用旧通知'); } });
  return { source, events };
}
test('千行分页前插只产生一个批量插入事件，稳定对象的流式更新不重载', () => {
  const { source: data, events } = source(); const old = [{ id: 'a' }, { id: 'b' }]; data.update(old, 'host:s'); events.length = 0;
  const prefix = Array.from({ length: 1000 }, (_, index) => ({ id: 'old-' + index })); const rows = [...prefix, ...old]; data.update(rows, 'host:s');
  assert.deepEqual(events, [[{ type: 'add', index: 0, count: 1000 }]]); assert.equal(data.totalCount(), 1002); assert.equal(data.getData(1000), old[0]);
  events.length = 0; old[1].text = 'new chunk'; data.update(rows.slice(), 'host:s'); assert.equal(events.length, 0);
});
test('同名消息跨主机重载，同主机同ID新对象重新绑定', () => {
  const { source: data, events } = source(); data.update([{ id: 'a' }], 'A:s'); events.length = 0;
  data.update([{ id: 'a' }], 'B:s'); assert.deepEqual(events, [[{type:'reload'}]]); events.length = 0;
  data.update([{ id: 'a' }], 'B:s'); assert.deepEqual(events, [[{type:'change', index:0}]]);
});

test('写入异常保留尚未落盘草稿，重新编辑可恢复写入且不让旧失败覆盖新稿', async () => {
  const e = setup(); let fail = true; const saved = []; e.db.saveDraft = async (_host,_epoch,value) => { if (fail) throw new Error('disk-full'); saved.push(value.text); return true; };
  e.drafts.changed('s','未落盘原稿'); await turn(); await turn(); assert.equal(e.controller.pendingDrafts.get('s').text,'未落盘原稿');
  fail = false; e.drafts.changed('s','更新后的草稿'); await turn(); await turn(); assert.deepEqual(saved,['更新后的草稿']); assert.equal(e.controller.pendingDrafts.size,0); e.controller.dispose();
});
test('明确清空草稿期间的旧写入异常不复活已删除文字', async () => {
  const e = setup(); let reject; e.db.saveDraft = () => new Promise((_resolve,no) => { reject = no; });
  e.drafts.changed('s','将被删除的草稿'); await turn(); await e.controller.clear(true); reject(new Error('late-disk-failure')); await turn(); await turn();
  assert.equal(e.controller.pendingDrafts.size,0); assert.equal(e.drafts.has('s'),false); e.controller.dispose();
});

test('根页销毁时将尚在内存队列中的其他草稿交给持久化队列', async () => {
  const e=setup(); await e.client.onPrepared(false); const gate=deferred(); const writes=[];
  e.db.saveDraft=async (_host,_epoch,row)=>{ writes.push(row.text); if(row.sessionId==='first') await gate.promise; return true; };
  e.drafts.changed('first','正在写入'); await turn(); e.drafts.changed('second','排队未落盘'); e.controller.dispose(); gate.resolve(); await turn(); await turn();
  assert.deepEqual(writes,['正在写入','排队未落盘']);
});

test('消息数据源不能保留调用方可变数组：原地追加、前插和删除仍须通知列表', () => {
  const source = new MessageDataSource(); const events = [];
  source.registerDataChangeListener({ onDataReloaded: () => events.push('reload'), onDatasetChange: ops => events.push(JSON.parse(JSON.stringify(ops))), onDataChange: index => events.push(index) });
  const rows = [{ id:'a' }]; source.update(rows,'host:session'); events.length=0;
  rows.push({id:'b'}); source.update(rows,'host:session'); assert.equal(source.totalCount(),2); assert.ok(events.length,'原地追加没有发出通知'); events.length=0;
  rows.unshift({id:'older'}); source.update(rows,'host:session'); assert.equal(source.getData(0).id,'older'); assert.ok(events.length,'原地前插没有发出通知'); events.length=0;
  rows.splice(1,1); source.update(rows,'host:session'); assert.equal(source.totalCount(),2); assert.ok(events.length,'原地删除没有发出通知');
});

test('空聊天数据源不会无限请求下一次布局', () => {
  const source = new MessageDataSource(); source.registerDataChangeListener({ onDatasetChange:()=>{} });
  assert.equal(source.update([],'host:empty'),true);
  assert.equal(source.update([],'host:empty'),false);
});
