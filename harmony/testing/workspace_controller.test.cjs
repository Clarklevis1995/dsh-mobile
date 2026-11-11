// 执行实际ArkTS控制器，验证任务租约跨预算回收/预览关闭/主机移除的文件生命周期。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js');
const core = path.resolve(__dirname, '../../harmonyApp/entry/src/main/ets/core');
function environment() {
  const deleted = [], ready = [], modules = new Map(); let nonce = 0;
  function load(name) {
    if (modules.has(name)) return modules.get(name);
    const module = { exports: {} };
    const requireMock = id => {
      if (id === '@kit.CoreFileKit') return { fileIo: { async unlink(file) { deleted.push(file); }, async mkdir() {} } };
      if (id === '@kit.ArkTS') return { util: { generateRandomUUID: () => String(++nonce) }, taskpool: { Task: class { constructor(fn, ...args) { this.fn = fn; this.args = args; } }, async execute(task) { return task.fn(...task.args); }, cancel() {}, Priority: { HIGH: 1 } } };
      if (id === './FileWorker') return { writeWorkspaceFile() {} };
      return load(id.replace('./',''));
    };
    vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(core, name + '.ets'),'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 } }).outputText, { module, exports: module.exports, require: requireMock, Date, console }, { filename: name + '.ets' });
    modules.set(name, module.exports);return module.exports;
  }
  const client = { connectionGeneration: 1, selectedSessionId: 's', async action() {} };
  const controller = new (load('WorkspaceController').WorkspaceController)(client,{ cacheDir: '/owned' },'host');
  controller.onReady = file => ready.push(file);
  async function download(id) {
    await client.onFileIo({ transferId:id, sessionId:'s', token:id, operation:'create', size:10, offset:0, length:0 });
    client.onFileCompletion({ transferId:id, sessionId:'s', path:id+'.md', name:id+'.md', mediaType:'text/markdown', size:10, sha256:'owned' });
    return ready.at(-1);
  }
  return { controller, download, deleted };
}
test('导出租约在预览释放、预算淘汰和主机销毁后仍保护文件，最后一个租约仅删除一次', async () => {
  const { controller, download, deleted } = environment();
  const file = await download('exporting');
  const first = controller.acquire(file.transferId), second = controller.acquire(file.transferId);
  assert.equal(typeof first,'function');assert.equal(typeof second,'function');
  controller.release(file.transferId);
  for (let n=0;n<7;n++) { const other=await download('other-'+n);controller.release(other.transferId); }
  assert.ok(deleted.length>0,'必须实际触发预算淘汰');assert.ok(!deleted.includes(file.localPath));
  controller.dispose();assert.ok(!deleted.includes(file.localPath));assert.equal(controller.acquire(file.transferId),undefined);
  first();first();assert.ok(!deleted.includes(file.localPath),'重复释放不能消耗另一导出任务的租约');
  second();second();assert.equal(deleted.filter(p=>p===file.localPath).length,1);
});
test('已完成视图在预算内保留，淘汰后的传输不能重新获得租约', async () => {
  const { controller, download, deleted } = environment();
  const file=await download('old');controller.release(file.transferId);
  for(let n=0;n<6;n++){const other=await download('new-'+n);controller.release(other.transferId);}
  assert.ok(deleted.includes(file.localPath));assert.equal(controller.acquire(file.transferId),undefined);
  const current=await download('view');controller.dispose();assert.equal(deleted.filter(p=>p===current.localPath).length,1);
});
