// 验证应用路由策略；真实系统边缘返回仍由模拟器专项验证。
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const deveco = process.env.DEVECO_STUDIO_PATH || '/Applications/DevEco-Studio.app/Contents';
const ts = require(path.join(deveco, 'plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js'));

function navigation() {
  class Paths {
    rows = [];
    setInterception(value) { this.interception = value; }
    getAllPathName() { return this.rows.map(row => row.name); }
    pushPath(row) { this.rows.push(row); }
    pop() { return this.rows.pop(); }
    size() { return this.rows.length; }
    clear() { this.rows = []; }
    popToName(name) { const index = this.getAllPathName().lastIndexOf(name); this.rows.splice(index + 1); return index; }
    nativeBack(committed) { if (committed) this.pop(); this.interception.didShow(); }
  }
  const filename = path.resolve(__dirname, '../../harmonyApp/entry/src/main/ets/core/AppNavigation.ets');
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021, experimentalDecorators: true }
  }).outputText;
  const module = { exports: {} };
  vm.runInNewContext(code, { module, exports: module.exports, NavPathStack: Paths, Observed: type => type });
  return { nav: new module.exports.AppNavigation(), R: module.exports.AppRoute };
}

test('设置进入主机编辑后按真实来源逐级返回', () => {
  const { nav, R } = navigation();
  for (const route of [R.Settings, R.Hosts, R.HostEdit]) nav.push(route);
  for (const expected of [R.Hosts, R.Settings, R.Home]) { assert.equal(nav.back(), true); assert.equal(nav.current, expected); }
  assert.equal(nav.back(), false);
});
test('字号和目录分别成为独立目的页面', () => {
  const { nav, R } = navigation();
  nav.push(R.Settings); nav.push(R.Font); nav.back(); assert.equal(nav.current, R.Settings);
  nav.home(); nav.push(R.Workspaces); nav.push(R.Directory); nav.back(); assert.equal(nav.current, R.Workspaces);
});
test('切换会话复用唯一聊天目的页并返回待处理入口', () => {
  const { nav, R } = navigation();
  nav.conversation(); nav.conversation(); assert.equal(nav.paths.size(), 1);
  nav.push(R.Inbox); nav.back(); assert.equal(nav.current, R.Conversation);
  nav.push(R.Inbox); nav.conversation(); assert.equal(nav.paths.size(), 1);
});
test('取消原生返回不清理认证，完成出栈只清理一次', () => {
  const { nav, R } = navigation(); const removed = [];
  nav.onRemoved = route => removed.push(route);
  nav.push(R.Hosts); nav.push(R.HostAdd);
  nav.paths.nativeBack(false); assert.equal(nav.current, R.HostAdd); assert.equal(removed.length, 0);
  nav.paths.nativeBack(true); nav.sync(); assert.equal(nav.current, R.Hosts); assert.deepEqual(removed, [R.HostAdd]);
});
test('切换主机清空整个旧路径而不是留下可返回的旧上下文', () => {
  const { nav, R } = navigation(); nav.conversation(); nav.push(R.Inbox); nav.home(false);
  assert.equal(nav.current, R.Home); assert.equal(nav.paths.size(), 0); assert.equal(nav.back(), false);
});

test('轨迹与内容详情逐级系统返回到原始聊天来源', () => {
  const { nav,R }=navigation();nav.push(R.OfflineSearch);nav.push(R.SearchConversation);nav.push(R.Trajectory);nav.push(R.Content);
  for (const expected of [R.Trajectory,R.SearchConversation,R.OfflineSearch]) { nav.paths.nativeBack(true);assert.equal(nav.current,expected); }
});

test('版本说明与更新检查各自压栈，返回仍是设置来源', () => {
  const { nav,R }=navigation();nav.push(R.Settings);nav.push(R.ReleaseNotes);nav.paths.nativeBack(true);assert.equal(nav.current,R.Settings);
  nav.push(R.Update);nav.paths.nativeBack(false);assert.equal(nav.current,R.Update);nav.paths.nativeBack(true);assert.equal(nav.current,R.Settings);
});
