// 实际 ArkTS 通知协调器＋受控系统 I/O，系统授权／通知面板仍另行 F／D。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js');
const base = path.resolve(__dirname, '../../harmonyApp/entry/src/main/ets/core');
const turn = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return {promise,resolve}; }
function setup(saved = new Map([['enabled',true]])) {
  const shown = []; const cancelled = []; const granted = { value: true }; let agentGate;
  const handle = { async get(key, fallback) { return saved.has(key) ? saved.get(key) : fallback; }, async put(key,value) { saved.set(key,value); }, async flush() {} };
  const system = { async isNotificationEnabled() { return granted.value; }, async requestEnableNotification() {}, async publish(row) { shown.push(row); }, async cancel(id) { cancelled.push(id); }, ContentType: {NOTIFICATION_CONTENT_BASIC_TEXT:0} };
  const kits = { '@kit.NotificationKit': {notificationManager:system}, '@kit.AbilityKit': {wantAgent:{ async getWantAgent(want) { if(agentGate) await agentGate.promise; return want; }, OperationType:{START_ABILITY:1}, WantAgentFlags:{UPDATE_PRESENT_FLAG:1}}}, '@kit.ArkData':{preferences:{async getPreferences(){return handle;}}} };
  function load(name) {
    const module = {exports:{}}; const code = ts.transpileModule(fs.readFileSync(path.join(base,name+'.ets'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2021}}).outputText;
    vm.runInNewContext(code,{module,exports:module.exports,require:name=>kits[name] || load(name.replace('./',''))}); return module.exports;
  }
  const {AgentNotifications}=load('AgentNotifications'); const service=new AgentNotifications({abilityInfo:{bundleName:'smoke'}}); const hosts = new Set(['A','B']); service.knownHost=id=>hosts.has(id);
  return {service,shown,cancelled,hosts,saved,granted,load,gate(value){agentGate=value;}};
}
const notice = {key:'approval:same:r',sessionId:'same',kind:'approval',failed:false};
test('前台抑制、跨主机隔离与冷启持久去重', async()=>{
  const e=setup(); await e.service.open(); e.service.accept('A',[notice],true); e.service.accept('A',[notice],false); e.service.accept('B',[notice],false); await turn(); await turn();
  assert.equal(e.shown.length,1); assert.match(e.shown[0].wantAgent.wants[0].parameters.whalenessNotificationTarget,/"host":"B"/);
  const cold=setup(e.saved); await cold.service.open(); cold.service.accept('B',[notice],false); await turn(); assert.equal(cold.shown.length,0);
});
test('系统授权等待期间关闭，不发布迟到提醒',async()=>{
  const e=setup(); await e.service.open(); const gate=deferred(); e.gate(gate); e.service.accept('A',[notice],false); await turn(); await e.service.setEnabled(false); gate.resolve(); await turn();
  assert.equal(e.shown.length,0); assert.equal(e.saved.get('enabled'),false);
});
test('WantAgent 准备期间移除主机，不复活该主机通知',async()=>{
  const e=setup(); await e.service.open(); const gate=deferred(); e.gate(gate); e.service.accept('A',[notice],false); await turn(); e.hosts.delete('A'); await e.service.removeHost('A'); gate.resolve(); await turn();
  assert.equal(e.shown.length,0); assert.equal(JSON.parse(e.saved.get('seen')).length,0);
});
test('未获系统权限保持禁用，外部导航只接受有界标识',async()=>{
  const e=setup(); await e.service.open(); e.granted.value=false; await e.service.setEnabled(true); e.service.accept('A',[notice],false); await turn(); assert.equal(e.shown.length,0);
  const {parseNotificationTarget}=e.load('NotificationTarget'); assert.equal(parseNotificationTarget('{"host":"../../bad","session":"s"}'),undefined); assert.equal(parseNotificationTarget('x'),undefined);
  assert.equal(parseNotificationTarget('{"host":"host-A","session":"s"}').session,'s');
});
