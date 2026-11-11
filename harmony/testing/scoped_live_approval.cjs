// 真实审批专用小工作区：只接受工作区内写入，不批准 danger-full-access。
const fs = require('node:fs');
const crypto = require('node:crypto');
const http = require('node:http');
const path = require('node:path');
const { WebSocket } = require(require.resolve('ws', { paths: [process.env.HARMONY_GATEWAY_PACKAGE] }));
const base = 'http://127.0.0.1:3080';
const run = crypto.randomUUID().slice(0, 8);
const workspacePath = '/Users/lichaofan/DeepseekHarnessProject/dsh-mobile/.harmony-validation/p1-' + run;
const device = crypto.randomUUID();
let token, control, conversation, sessionId, workspaceId;
const waits = []; const pending = new Map(); const records = [];
async function management(route, data) {
  const response = await fetch(base + route, data === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  if (!response.ok) throw new Error('management ' + response.status); return response.json();
}
function wait(kind, predicate = () => true) { return new Promise((resolve, reject) => {
  const item = { kind, predicate, resolve }; waits.push(item);
  item.timer = setTimeout(() => { waits.splice(waits.indexOf(item), 1); reject(new Error('timeout ' + kind)); }, 30000);
}); }
function accept(frame) {
  for (const item of [...waits]) if (frame.kind === item.kind && item.predicate(frame)) { clearTimeout(item.timer); waits.splice(waits.indexOf(item), 1); item.resolve(frame); }
  if (frame.sessionId !== sessionId) return;
  if (frame.kind === 'event') records.push(frame);
  if (frame.kind === 'approval-requested') pending.set(frame.rpcId, frame);
  if (frame.kind === 'approval-resolved') pending.delete(frame.rpcId);
}
async function connect(channel, pairingCode) {
  const socket = new WebSocket(base.replace('http', 'ws') + '/ws/mobile', ['dsh-mobile-v1', ...(pairingCode ? ['dsh-pair.' + pairingCode] : [])], { headers: {
    'X-DSH-Device-ID': device, 'X-DSH-Device-Name': Buffer.from('Harmony scoped P1 observer').toString('base64'), 'X-DSH-Channel': channel,
    ...(token ? { Authorization: 'Bearer ' + token } : {})
  }});
  socket.on('message', data => { const frame = JSON.parse(data); if (frame.kind === 'paired') token = frame.token; accept(frame); });
  socket.on('error', () => {}); const ready = wait('hello'); await ready; return socket;
}
async function request(socket, body, kind, predicate) { const received = wait(kind, predicate); socket.send(JSON.stringify(body)); return received; }
(async () => {
  fs.mkdirSync(workspacePath, { recursive: true });
  const pairing = await management('/mgw/pair', { publicUrl: base.replace('http', 'ws') + '/ws/mobile' });
  control = await connect('control', pairing.payload.pairingCode); conversation = await connect('conversation');
  const created = await request(control, { type: 'workspace-create', path: workspacePath }, 'workspace-create');
  workspaceId = (created.workspace || created).workspaceId;
  if (!workspaceId) throw new Error('workspace-create missing workspaceId');
  const createdSession = await request(control, { type: 'session-create', requestId: crypto.randomUUID(), workspaceId }, 'session-created');
  sessionId = createdSession.sessionId;
  await request(control, { type: 'session-rename', sessionId, title: '鸿蒙 P1-工作区审批-' + run }, 'session-renamed');
  await request(conversation, { type: 'subscribe', sessionId, assistantStream: true }, 'session-snapshot', f => f.sessionId === sessionId);
  await request(control, { type: 'permission', sessionId, name: 'read-only' }, 'permission', f => f.sessionId === sessionId);
  const server = http.createServer(async (req, res) => {
    try {
      let data = ''; for await (const chunk of req) data += chunk;
      const args = data ? JSON.parse(data) : {};
      let result;
      if (req.url === '/status') result = { run, workspacePath, workspaceId, sessionId, name: '鸿蒙 P1-工作区审批-' + run, pending: [...pending.values()], records };
      else if (req.url === '/send') {
        const file = args.reject ? 'must-not-exist.txt' : 'approved-once.txt';
        const text = '范围受限的审批验收：只在当前工作区 ' + workspacePath + ' 内创建 ' + file + '，内容仅 HARMONY_SCOPED_TEST。当前为read-only；如需提权，只申请本工作区的workspace-write，禁止申请或使用danger-full-access，不读取其他文件、不调用其他工具绕过。收到允许才写入，若拒绝则停止。';
        await request(conversation, { type: 'message', sessionId, requestId: crypto.randomUUID(), text, images: [], mode: 'queue' }, 'sent');
        result = { target: path.join(workspacePath, file) };
      } else if (req.url === '/stop') {
        control.send(JSON.stringify({ type: 'session-cancel', sessionId })); control.close(); conversation.close();
        await management('/mgw/devices/' + device + '/revoke', {}); result = { stopped: true }; setTimeout(() => server.close(), 100);
      } else throw new Error('unknown route');
      res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(result));
    } catch (error) { res.statusCode = 400; res.end(JSON.stringify({ error: error.message })); }
  });
  server.listen(18887, '127.0.0.1', () => console.log('SCOPED_READY ' + JSON.stringify({ run, workspacePath, workspaceId, sessionId })));
})().catch(error => { console.error(error.message); process.exitCode = 1; });
