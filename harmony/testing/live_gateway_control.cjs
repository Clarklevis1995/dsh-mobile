// 真实 Host 验收辅助端：只创建和操作本轮专用会话；凭据仅驻内存。
const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const gatewayPackage = process.env.HARMONY_GATEWAY_PACKAGE;
if (!gatewayPackage) throw new Error('请设置 HARMONY_GATEWAY_PACKAGE 指向已安装网关的目录');
const { WebSocket } = require(require.resolve('ws', { paths: [gatewayPackage] }));
const base = process.env.HARMONY_LIVE_HTTP || 'http://127.0.0.1:3080';
const own = new Map();
if (process.env.HARMONY_LIVE_SEED) {
  const seed = JSON.parse(fs.readFileSync(process.env.HARMONY_LIVE_SEED, 'utf8'));
  for (const item of seed.sessions || []) own.set(item.id, { id: item.id, name: item.name, records: new Map(), streamFrames: 0, sequenceConflicts: 0 });
}
const pending = new Map();
const waits = [];
const audit = [];
const run = crypto.randomUUID().slice(0, 8);
const deviceId = crypto.randomUUID();
let token;
let control;
let conversation;
let capabilities = [];
let stopped = false;
let recovering = false;
let subscribedId;

async function management(route, body) {
  const response = await fetch(base + route, body === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  if (!response.ok) throw new Error('管理接口返回 ' + response.status);
  return response.json();
}
function wait(kind, predicate = () => true, timeout = 25000) {
  return new Promise((resolve, reject) => {
    const row = { kind, predicate, resolve, reject };
    row.timer = setTimeout(() => { const at = waits.indexOf(row); if (at >= 0) waits.splice(at, 1); reject(new Error('等待响应超时：' + kind)); }, timeout);
    waits.push(row);
  });
}
function accept(frame) {
  for (const row of [...waits]) {
    if (frame.kind === row.kind && row.predicate(frame)) {
      waits.splice(waits.indexOf(row), 1); clearTimeout(row.timer); row.resolve(frame);
    }
  }
  if (frame.kind === 'hello' && frame.capabilities) capabilities = frame.capabilities;
  if (!own.has(frame.sessionId)) return;
  const session = own.get(frame.sessionId);
  if (frame.kind === 'session-snapshot' || frame.kind === 'history') {
    for (const record of frame.events || []) session.records.set(record.seq, record);
    session.cursor = frame.cursor ?? session.cursor;
    session.hasMore = frame.hasMore;
    session.nextBeforeSeq = frame.nextBeforeSeq;
  }
  if (frame.kind === 'event') {
    const before = session.records.get(frame.seq);
    if (before?.event && JSON.stringify(before.event) !== JSON.stringify(frame.event)) session.sequenceConflicts++;
    session.records.set(frame.seq, { seq: frame.seq, time: frame.time, event: frame.event });
  }
  if (frame.kind === 'assistant-stream') {
    const item = frame.frame || {};
    session.streamFrames++;
    session.lastRevision = item.revision;
  }
  if (frame.kind.endsWith('-requested') && frame.rpcId) pending.set(frame.rpcId, frame);
  if (frame.kind.endsWith('-resolved') && frame.rpcId) pending.delete(frame.rpcId);
  audit.push({ kind: frame.kind, sessionId: frame.sessionId, seq: frame.seq, rpcId: frame.rpcId, requestId: frame.requestId, streamType: frame.frame?.type, revision: frame.frame?.revision });
}
async function connect(channel, pairingCode) {
  const protocols = ['dsh-mobile-v1'];
  if (pairingCode) protocols.push('dsh-pair.' + pairingCode);
  const socket = new WebSocket(base.replace(/^http/, 'ws') + '/ws/mobile', protocols, { headers: {
    'X-DSH-Device-ID': deviceId, 'X-DSH-Device-Name': Buffer.from('Harmony P1 validation observer').toString('base64'),
    'X-DSH-Channel': channel, ...(token ? { Authorization: 'Bearer ' + token } : {})
  }});
  socket.on('message', value => {
    const frame = JSON.parse(value.toString());
    if (frame.kind === 'paired') token = frame.token;
    accept(frame);
  });
  socket.on('error', () => {});
  socket.on('close', () => { if (!stopped && (socket === control || socket === conversation)) recover(); });
  const hello = wait('hello'); hello.catch(() => {});
  await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject); });
  await hello;
  return socket;
}
function send(socket, value) { socket.send(JSON.stringify(value)); }
function scoped(id) { if (!own.has(id)) throw new Error('仅允许操作本轮专用会话'); return own.get(id); }
async function create(label) {
  const requestId = crypto.randomUUID();
  const response = wait('session-created', f => f.requestId === requestId);
  send(control, { type: 'session-create', requestId });
  const result = await response;
  const id = result.sessionId;
  const name = '鸿蒙 P1-' + run + '-' + label;
  own.set(id, { id, name, records: new Map(), streamFrames: 0, sequenceConflicts: 0 });
  const renamed = wait('session-renamed', f => f.sessionId === id);
  send(control, { type: 'session-rename', sessionId: id, title: name });
  await renamed;
  return { id, name };
}
async function subscribe(id) {
  scoped(id); subscribedId = id;
  const received = wait('session-snapshot', f => f.sessionId === id);
  send(conversation, { type: 'subscribe', sessionId: id, assistantStream: true });
  await received;
}
async function recover() {
  if (stopped || recovering) return;
  recovering = true;
  control?.removeAllListeners('close'); conversation?.removeAllListeners('close');
  control?.close(); conversation?.close(); pending.clear();
  try {
    control = await connect('control'); conversation = await connect('conversation');
    if (subscribedId) await subscribe(subscribedId);
    recovering = false;
  } catch (_) { recovering = false; if (!stopped) setTimeout(recover, 500); }
}
async function stop() {
  if (stopped) return;
  stopped = true;
  for (const id of own.keys()) { if (control?.readyState === WebSocket.OPEN) send(control, { type: 'session-cancel', sessionId: id }); }
  control?.close(); conversation?.close();
  await management('/mgw/devices/' + encodeURIComponent(deviceId) + '/revoke', {}).catch(() => {});
}
(async () => {
  const status = await management('/mgw/status');
  const pair = await management('/mgw/pair', { publicUrl: base.replace(/^http/, 'ws') + '/ws/mobile' });
  control = await connect('control', pair.payload.pairingCode);
  conversation = await connect('conversation');
  const server = http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    try {
      let body = '';
      for await (const chunk of req) { body += chunk; if (body.length > 150000) throw new Error('请求过大'); }
      const args = body ? JSON.parse(body) : {};
      const route = req.url;
      let result;
      if (route === '/bootstrap') { const pairing = await management('/mgw/pair', { publicUrl: base.replace(/^http/, 'ws') + '/ws/mobile' }); result = { qrPayload: pairing.qrPayload, run }; }
      else if (route === '/status') result = { run, gatewayVersion: status.version, capabilities, sessions: [...own.values()].map(s => ({ id: s.id, name: s.name, records: s.records.size, streamFrames: s.streamFrames, sequenceConflicts: s.sequenceConflicts, hasMore: s.hasMore, nextBeforeSeq: s.nextBeforeSeq })) };
      else if (route === '/create') result = await create(args.label || 'A');
      else if (route === '/subscribe') { await subscribe(args.id); result = { subscribed: true }; }
      else if (route === '/message') { scoped(args.id); const requestId = crypto.randomUUID(); const ack = wait('sent', f => f.requestId === requestId); send(conversation, { type: 'message', sessionId: args.id, requestId, text: args.text, images: [], mode: 'queue', clientTimeZone: 'Asia/Shanghai' }); await ack; result = { accepted: true, requestId }; }
      else if (route === '/history') { const s = scoped(args.id); result = { records: [...s.records.values()].sort((a, b) => a.seq - b.seq), audit: audit.filter(a => a.sessionId === args.id) }; }
      else if (route === '/pending') result = [...pending.values()].filter(f => own.has(f.sessionId));
      else if (route === '/options') { scoped(args.id); const answer = wait('permission-options', f => f.sessionId === args.id); send(control, { type: 'permission-options', sessionId: args.id }); result = await answer; }
      else if (route === '/permission') { scoped(args.id); const answer = wait('permission', f => f.sessionId === args.id); send(control, { type: 'permission', sessionId: args.id, name: args.name }); result = await answer; }
      else if (route === '/question') { const item = pending.get(args.rpcId); if (!item || !own.has(item.sessionId) || item.kind !== 'question-requested') throw new Error('本轮问题已失效'); send(control, { type: 'question-answer', sessionId: item.sessionId, rpcId: item.rpcId, answers: args.answers }); result = { sent: true }; }
      else if (route === '/approval') { const item = pending.get(args.rpcId); if (!item || !own.has(item.sessionId) || item.kind !== 'approval-requested') throw new Error('本轮审批已失效'); if (!['allowed-once', 'rejected'].includes(args.outcome)) throw new Error('审批选项无效'); send(control, { type: 'approval-response', sessionId: item.sessionId, rpcId: item.rpcId, approvalId: item.approvalId, outcome: args.outcome }); result = { sent: true }; }
      else if (route === '/cancel') { scoped(args.id); send(control, { type: 'session-cancel', sessionId: args.id }); result = { sent: true }; }
      else if (route === '/stop') { result = { stopped: true }; setTimeout(() => stop().then(() => server.close()), 50); }
      else { res.statusCode = 404; result = { error: 'not-found' }; }
      res.end(JSON.stringify(result));
    } catch (error) { res.statusCode = 400; res.end(JSON.stringify({ error: error.message })); }
  });
  server.listen(Number(process.env.HARMONY_LIVE_PORT || '18885'), '127.0.0.1', () => console.log('LIVE_READY ' + run + ' gateway=' + status.version));
  process.on('SIGTERM', () => stop().finally(() => process.exit(0)));
})().catch(error => { console.error(error.name + ': live validation service failed'); process.exitCode = 1; });
