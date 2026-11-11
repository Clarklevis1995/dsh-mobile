const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require((process.env.DEVECO_STUDIO_PATH || '/Applications/DevEco-Studio.app/Contents') + '/plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js');
const base = path.resolve(__dirname, '../../harmonyApp/entry/src/main/ets');
const clone = value => JSON.parse(JSON.stringify(value));

function environment() {
  const assets = new Map();
  let failWrite = false;
  let document = { version: 1, profiles: [], selectedId: '', appearance: { style: 'system', language: 'system', fontFollowsSystem: true, fontScale: 1, reduceMotion: false }, legacyMigrated: false };
  class CredentialStore {
    constructor(id = 'legacy') { this.id = id; }
    async load() { return assets.get(this.id); }
    async save(value) { assets.set(this.id, clone(value)); }
    async clear() { assets.delete(this.id); }
  }
  class Engine {
    dispatch(text) {
      const command = JSON.parse(text);
      return JSON.stringify({ ok: true, profile: command.profile });
    }
  }
  class GatewayClient {
    constructor(id) { this.id = id; }
  }
  const cache = new Map();
  function load(name) {
    const filename = path.resolve(base, name + '.ets');
    if (cache.has(filename)) return cache.get(filename);
    const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021, experimentalDecorators: true } }).outputText;
    const module = { exports: {} };
    const fakeRequire = id => {
      if (id === 'libwhaleness_bridge.so') return { Engine };
      if (id === '@kit.ArkTS') return { util: { generateRandomUUID: () => 'unique-id' } };
      if (id === '@kit.ArkData' || id === '@kit.AbilityKit') return {};
      if (id.endsWith('/CredentialStore')) return { CredentialStore };
      if (id.endsWith('/GatewayClient')) return { GatewayClient };
      return load(path.relative(base, path.resolve(path.dirname(filename), id)));
    };
    vm.runInNewContext(code, { module, exports: module.exports, require: fakeRequire, Observed: type => type, console }, { filename });
    cache.set(filename, module.exports); return module.exports;
  }
  const storage = { async read() { return clone(document); }, async write(value) { if (failWrite) throw new Error('disk failure'); document = clone(value); } };
  return { ...load('core/Models'), ...load('core/HostRepository'), ...load('core/HostRuntime'), assets, storage,
    document: () => document, failWrite: value => { failWrite = value; }, seed: value => { document = value; } };
}
const info = endpoint => ({ endpoint, endpoints: [endpoint], gatewayId: '12345678-1234-4123-8123-123456789abc', gatewayName: 'Test host' });

test('旧凭据迁移在元信息写入失败时保留原 Asset，重试只产生一台主机', async () => {
  const env = environment();
  env.assets.set('legacy', { endpoint: 'ws://127.0.0.1/ws/mobile', token: 'secret-one', deviceId: 'device-one' });
  let repository = new env.HostRepository(env.storage);
  env.failWrite(true);
  await assert.rejects(repository.load(), /disk failure/);
  assert.equal(env.assets.get('legacy').token, 'secret-one');
  env.failWrite(false);
  repository = new env.HostRepository(env.storage); await repository.load();
  assert.equal(repository.profiles.length, 1);
  assert.equal(env.assets.has('legacy'), false);
  assert.equal(env.assets.get('legacy-host').deviceId, 'device-one');
  const serialized = JSON.stringify(env.document());
  assert.equal(serialized.includes('secret-one'), false);
  assert.equal(serialized.includes('device-one'), false);
  await new env.HostRepository(env.storage).load();
  assert.equal(env.document().profiles.length, 1);
});

test('迁移完成标志防止清空资料后旧 Asset 复活', async () => {
  const env = environment(); const repository = new env.HostRepository(env.storage); await repository.load();
  env.assets.set('legacy', { endpoint: 'ws://127.0.0.1/ws/mobile', token: 'orphan', deviceId: 'orphan-device' });
  await new env.HostRepository(env.storage).load();
  assert.equal(env.document().profiles.length, 0);
});

test('两个主机的资料、当前选择及 Asset 独立，移除 A 不清 B', async () => {
  const env = environment(); const repository = new env.HostRepository(env.storage); await repository.load();
  await repository.connected('host-a', info('ws://127.0.0.1:1/ws/mobile'));
  await repository.connected('host-b', info('ws://127.0.0.1:2/ws/mobile'));
  env.assets.set('host-a', { token: 'a', deviceId: 'dev-a' }); env.assets.set('host-b', { token: 'b', deviceId: 'dev-b' });
  await repository.select('host-a'); await repository.remove('host-a');
  assert.equal(repository.selectedId, 'host-b');
  assert.equal(env.assets.has('host-a'), false); assert.equal(env.assets.get('host-b').token, 'b');
});

test('资料写失败不会发布未保存快照，随后操作仍可成功', async () => {
  const env = environment(); const repository = new env.HostRepository(env.storage); await repository.load();
  env.failWrite(true); await assert.rejects(repository.connected('host-a', info('ws://127.0.0.1/ws/mobile')));
  assert.equal(repository.profiles.length, 0);
  env.failWrite(false); await repository.connected('host-b', info('ws://127.0.0.1/ws/mobile'));
  assert.equal(repository.profiles[0].localId, 'host-b');
});

test('并发主机写入排队，不丢失其他资料；外观设置可重新加载', async () => {
  const env = environment(); const repository = new env.HostRepository(env.storage); await repository.load();
  await Promise.all([repository.connected('host-a', info('ws://127.0.0.1:1/ws/mobile')), repository.connected('host-b', info('ws://127.0.0.1:2/ws/mobile'))]);
  assert.equal(repository.profiles.length, 2);
  const settings = new env.AppearanceData(); settings.style = 'dark'; settings.language = 'en'; settings.fontScale = 1.5; settings.fontFollowsSystem = false;
  await repository.saveAppearance(settings);
  const restored = new env.HostRepository(env.storage); await restored.load();
  assert.equal(restored.appearance.style, 'dark'); assert.equal(restored.appearance.fontScale, 1.5);
});

test('相同会话 ID 的两台主机使用独立消息视图、问题和草稿', () => {
  const env = environment(); const a = new env.HostRuntime('host-a'); const b = new env.HostRuntime('host-b');
  const state = () => ({ ...env.emptyState(), selectedSessionId: 'same-session', sessions: [{ id: 'same-session', title: 'same', running: false }] });
  a.update(state(), [{ id: 'same-message', text: 'A', kind: 'ASSISTANT' }], '已连接', '');
  b.update(state(), [{ id: 'same-message', text: 'B', kind: 'ASSISTANT' }], '已连接', '');
  a.draft = 'draft-A'; b.draft = 'draft-B'; a.drafts.sent('same-session', a.draft);
  a.draft = a.drafts.acknowledged('same-session', 'same-session', a.draft);
  assert.equal(a.draft, ''); assert.equal(b.draft, 'draft-B');
  assert.equal(a.messages[0].text, 'A'); assert.equal(b.messages[0].text, 'B');
  assert.notEqual(a.messages[0], b.messages[0]);
  const oldView = a.sessions[0]; const updated = state(); updated.sessions[0].title = 'updated';
  a.update(updated, [], '已连接', ''); assert.equal(a.sessions[0], oldView); assert.equal(oldView.title, 'updated');
});
