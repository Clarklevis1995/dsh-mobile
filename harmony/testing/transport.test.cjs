// 用 DevEco 自带 TypeScript 转译实际 ArkTS 核心，替换系统 I/O；无需额外 npm 依赖。
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const deveco = process.env.DEVECO_STUDIO_PATH || '/Applications/DevEco-Studio.app/Contents';
const ts = require(path.join(deveco, 'plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js'));
const core = path.resolve(__dirname, '../../harmonyApp/entry/src/main/ets/core');

function environment() {
  let now = 1000;
  let sequence = 0;
  const timers = new Map();
  const sockets = [];
  const engines = [];
  const calls = [];
  const records = [];
  let loadGate;
  let connectGate;
  const saved = { endpoint: 'ws://127.0.0.1/ws/mobile', token: 'test-token', deviceId: 'test-device' };
  const empty = () => ({ connected: false, pendingRequest: false, pendingOperations: [], questions: [], approvals: [], sessions: [], workspaces: [], hasMore: false, loadingHistory: false, pendingQuestionCount: 0, pendingApprovalCount: 0 });
  class Engine {
    state = empty();
    constructor() { engines.push(this); }
    dispatch(text) {
      const command = JSON.parse(text);
      calls.push(command);
      let result = { ok: true, patches: [], outbound: [], sent: false };
      if (command.op === 'prepare') result.pairing = { endpoint: command.endpoint, pairingCode: '' };
      if (command.op === 'disconnect') this.state = { ...this.state, connected: false, pendingOperations: [], questions: [], approvals: [] };
      if (command.op === 'frame') {
        const frame = JSON.parse(command.frame);
        if (frame.kind === 'hello') this.state.connected = true;
        if (frame.state) this.state = { ...this.state, ...frame.state };
        if (frame.kind === 'error') result = { ...result, ok: false, error: 'unrelated-request-error', fatal: frame.fatal === true, outbound: frame.recovery ? [{ channel: 'control', text: JSON.stringify({ type: 'session-agent-preset', sessionId: 'a' }) }] : [] };
      }
      if (command.op === 'operation-timeout') {
        this.state.pendingOperations = this.state.pendingOperations.filter(key => key !== command.requestKey);
        result = { ...result, ok: false, error: 'timeout' };
      }
      if (command.op === 'send') result.outbound = [{ channel: 'control', text: '{"type":"message"}' }];
      return JSON.stringify({ ...result, state: this.state });
    }
  }
  class Credential { endpoint = ''; token = ''; deviceId = ''; }
  class CredentialStore {
    async load() { if (loadGate) await loadGate; return saved; }
    async save(value) { records.push(value); }
    async clear() { records.length = 0; }
  }
  class Socket {
    events = {};
    sent = [];
    sendSucceeds = true;
    closed = 0;
    on(kind, handler) { this.events[kind] = handler; }
    off(kind) { delete this.events[kind]; }
    async connect() { if (connectGate) await connectGate; }
    async send(text) { this.sent.push(text); return this.sendSucceeds; }
    async close() { this.closed++; }
    emit(value) { this.events.message?.(undefined, JSON.stringify(value)); }
  }
  function schedule(fn, delay, interval) { const id = ++sequence; timers.set(id, { fn, due: now + delay, interval }); return id; }
  const cache = new Map();
  function load(name) {
    if (cache.has(name)) return cache.get(name);
    const code = ts.transpileModule(fs.readFileSync(path.join(core, name + '.ets'), 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021 }
    }).outputText;
    const module = { exports: {} };
    const fakeRequire = id => {
      if (id === '@kit.NetworkKit') return { webSocket: { createWebSocket() { const socket = new Socket(); sockets.push(socket); return socket; } } };
      if (id === '@kit.ArkTS') return { util: { generateRandomUUID: () => 'test-uuid' } };
      if (id === 'libwhaleness_bridge.so') return { Engine };
      if (id === './CredentialStore') return { Credential, CredentialStore };
      return load(id.replace('./', ''));
    };
    vm.runInNewContext(code, {
      module, exports: module.exports, require: fakeRequire, console,
      setTimeout: (fn, ms) => schedule(fn, ms, 0), setInterval: (fn, ms) => schedule(fn, ms, ms),
      clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id),
      Date: class extends Date { static now() { return now; } }
    }, { filename: name + '.ets' });
    cache.set(name, module.exports);
    return module.exports;
  }
  const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
  async function advance(ms) {
    const end = now + ms;
    for (;;) {
      const next = [...timers].sort((a, b) => a[1].due - b[1].due).find(([, timer]) => timer.due <= end);
      if (!next) break;
      const [id, timer] = next;
      now = timer.due;
      if (timer.interval) timer.due += timer.interval; else timers.delete(id);
      timer.fn();
      await flush();
    }
    now = end;
    await flush();
  }
  return { ...load('GatewayClient'), ...load('Models'), ...load('DraftStore'), calls, sockets, engines, flush, advance,
    gate(promise) { loadGate = promise; }, connectGate(promise) { connectGate = promise; }, records };
}

async function connected() {
  const env = environment();
  env.client = new env.GatewayClient();
  await env.client.connect('ws://127.0.0.1/ws/mobile', '', '');
  env.sockets[0].emit({ kind: 'hello' });
  await env.flush();
  return env;
}

test('断开时尚未返回的 Asset 读取不能重新打开连接', async () => {
  const env = environment();
  let release;
  env.gate(new Promise(resolve => { release = resolve; }));
  const client = new env.GatewayClient();
  const connecting = client.connect('ws://127.0.0.1/ws/mobile', '', '');
  await env.flush();
  client.disconnect();
  release();
  await connecting;
  assert.equal(env.sockets.length, 0);
});

test('旧连接回调不会污染新一代连接，主动断开不会重连', async () => {
  const env = await connected();
  const stale = env.sockets[0].events.message;
  env.client.disconnect();
  stale(undefined, JSON.stringify({ kind: 'test', state: { connected: true } }));
  await env.advance(60000);
  assert.equal(env.sockets.length, 1);
  assert.equal(env.engines[0].state.connected, false);
});

test('无关错误不会撤销另一个请求的超时', async () => {
  const env = await connected();
  env.sockets[0].emit({ kind: 'test', state: { pendingOperations: ['message:1', 'history:2'] } });
  await env.flush();
  env.sockets[0].emit({ kind: 'error' });
  await env.flush();
  await env.advance(20001);
  assert.deepEqual(env.calls.filter(call => call.op === 'operation-timeout').map(call => call.requestKey).sort(), ['history:2', 'message:1']);
});

test('前台恢复复用健康连接，无响应则重连而不重发消息', async () => {
  const env = await connected();
  env.client.setForeground(false);
  await env.advance(1000);
  env.client.setForeground(true);
  await env.flush();
  assert.equal(env.sockets.length, 1);
  assert.ok(env.calls.some(call => call.op === 'resume'));
  await env.advance(9000);
  assert.equal(env.sockets.length, 2);
  assert.equal(env.calls.filter(call => call.op === 'send').length, 0);
});

test('socket.send 返回 false 会关闭坏连接并安排重连', async () => {
  const env = await connected();
  env.sockets[0].sendSucceeds = false;
  await assert.rejects(env.client.action(new env.Command('send')), /发送结果尚未确认/);
  await env.advance(1001);
  assert.equal(env.sockets.length, 2);
});

test('草稿按会话保留，迟到确认只清原文且保留新输入', () => {
  const { DraftStore } = environment();
  const store = new DraftStore();
  store.sent('A', 'sent-A');
  assert.equal(store.switchSession('A', 'B', 'sent-A'), '');
  assert.equal(store.acknowledged('A', 'B', 'draft-B'), 'draft-B');
  assert.equal(store.switchSession('B', 'A', 'draft-B'), '');
  store.sent('A', 'old');
  assert.equal(store.acknowledged('A', 'A', 'new'), 'new');
  assert.equal(store.acknowledged('B', 'B', 'draft-B'), 'draft-B');
});


test('身份校验失败后立即禁用业务操作并禁止自动重连', async () => {
  const env = await connected();
  let connectedState = true;
  env.client.onChange = state => { connectedState = state.connected; };
  env.sockets[0].emit({ kind: 'error', fatal: true });
  await env.flush();
  assert.equal(connectedState, false);
  await env.advance(60000);
  assert.equal(env.sockets.length, 1);
});


test('底层 connect 未返回时握手超时仍会关闭连接', async () => {
  const env = environment();
  let release;
  env.connectGate(new Promise(resolve => { release = resolve; }));
  const client = new env.GatewayClient();
  const opening = client.connect('ws://127.0.0.1/ws/mobile', '', '');
  await env.flush();
  assert.equal(env.sockets.length, 1);
  await env.advance(15001);
  assert.ok(env.sockets[0].closed > 0);
  assert.equal(env.engines[0].state.connected, false);
  release();
  await opening;
  client.disconnect();
});


test('旧 ACK 不清除改成新文后又改回原文的草稿', () => {
  const { DraftStore } = environment();
  const drafts = new DraftStore();
  drafts.sent('a', 'same');
  drafts.changed('a', 'new'); drafts.changed('a', 'same');
  assert.equal(drafts.acknowledged('a', 'a', 'same'), 'same');
});

test('非致命错误执行 Native 状态重查，不重发消息；致命错误不发送任何恢复请求', async () => {
  const env = await connected();
  env.sockets[0].emit({ kind: 'error', recovery: true }); await env.flush();
  const rechecks = env.sockets[0].sent.map(text => JSON.parse(text)).filter(row => row.type === 'session-agent-preset');
  assert.equal(rechecks.length, 1);
  assert.equal(env.sockets[0].sent.map(text => JSON.parse(text)).filter(row => row.type === 'message').length, 0);
  env.sockets[0].emit({ kind: 'error', recovery: true, fatal: true }); await env.flush();
  assert.equal(env.sockets[0].sent.map(text => JSON.parse(text)).filter(row => row.type === 'session-agent-preset').length, 1);
});
test('队列确认回填追加原文，保持另一会话与确认前的新输入，并使旧发送 ACK 失效', () => {
  const { DraftStore } = environment(); const drafts = new DraftStore();
  drafts.sent('a', 'old'); drafts.changed('a', 'new'); drafts.changed('b', 'draft-b');
  assert.equal(drafts.append('a', 'queued'), 'new\n\nqueued');
  assert.equal(drafts.switchSession('b', 'a', 'draft-b'), 'new\n\nqueued');
  assert.equal(drafts.acknowledged('a', 'a', 'new\n\nqueued'), 'new\n\nqueued');
  assert.equal(drafts.switchSession('a', 'b', 'new\n\nqueued'), 'draft-b');
});

test('迟到命令替换同时校验文本和编辑版本，不能清除新稿或相同文本的再次输入', () => {
  const { DraftStore } = environment(); const drafts = new DraftStore();
  drafts.changed('s', '/route'); const revision = drafts.version('s');
  assert.equal(drafts.replace('s', revision, '/route', ''), '');
  drafts.changed('s', 'new');
  assert.equal(drafts.replace('s', revision, '/route', ''), undefined);
  drafts.changed('s', '/route');
  assert.equal(drafts.replace('s', revision, '/route', ''), undefined);
  assert.equal(drafts.switchSession(undefined, 's', ''), '/route');
});

test('程序填充后的同值输入回调不再作为真实编辑，不会重新解释已选命令', () => {
  const { DraftStore } = environment(); const drafts = new DraftStore();
  assert.equal(drafts.replace('s', 0, '', '/switch'), '/switch');
  const revision = drafts.version('s');
  assert.equal(drafts.changed('s', '/switch'), false);
  assert.equal(drafts.version('s'), revision);
  assert.equal(drafts.changed('s', '/switch changed'), true);
});

test('手动 Ping 必须等待经过共享校验的控制 pong，发送成功不能冒充响应', async () => {
  const env = await connected(); let done = false;
  const result = env.client.ping().then(value => { done = true; return value; });
  await env.flush(); assert.equal(done, false);
  env.sockets[0].emit({ kind: 'other' }); await env.advance(100); assert.equal(done, false);
  env.sockets[0].emit({ kind: 'pong' }); await env.advance(50); assert.equal(await result, true);
});
test('Ping 无回包超时、发送失败及中途断开均不能返回成功', async () => {
  const env = await connected(); const missing = env.client.ping(); await env.flush();
  await env.advance(5001); assert.equal(await missing, false);
  env.sockets[0].sendSucceeds = false; assert.equal(await env.client.ping(), false);
  env.sockets[0].sendSucceeds = true; const disconnected = env.client.ping(); await env.flush();
  env.client.disconnect(); await env.advance(50); assert.equal(await disconnected, false);
});
