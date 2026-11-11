// 真实主机目录验收的只读观察端。完整目录仅驻内存，输出严格限于本轮指定会话／工作区。
const http = require('node:http');
const crypto = require('node:crypto');
const { WebSocket } = require(require.resolve('ws', { paths: [process.env.HARMONY_GATEWAY_PACKAGE] }));
const sessionId = process.env.HARMONY_CATALOG_SESSION;
const workspacePath = process.env.HARMONY_CATALOG_PATH;
if (!sessionId?.startsWith('session-') || !workspacePath?.includes('/.harmony-validation/')) throw new Error('需要本轮专用会话和工作区');
const base = 'http://127.0.0.1:3080'; const device = crypto.randomUUID();
let token, socket, session, workspace, archived = false;
const waits = [];
async function management(route, data) {
  const response = await fetch(base + route, data === undefined ? {} : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) });
  if (!response.ok) throw new Error('management ' + response.status); return response.json();
}
function wait(kind) { return new Promise((resolve, reject) => {
  const row = { kind, resolve }; waits.push(row); row.timer = setTimeout(() => { waits.splice(waits.indexOf(row), 1); reject(new Error('等待超时')); }, 20000);
}); }
async function refresh() {
  const listed = wait('sessions'), grouped = wait('workspaces');
  socket.send(JSON.stringify({ type: 'sessions' })); socket.send(JSON.stringify({ type: 'workspaces' }));
  await Promise.all([listed, grouped]);
  return { session, workspace, archived };
}
(async () => {
  const pair = await management('/mgw/pair', { publicUrl: 'ws://127.0.0.1:3080/ws/mobile' });
  const ready = wait('hello');
  socket = new WebSocket('ws://127.0.0.1:3080/ws/mobile', ['dsh-mobile-v1', 'dsh-pair.' + pair.payload.pairingCode], { headers: { 'X-DSH-Device-ID': device, 'X-DSH-Device-Name': Buffer.from('Harmony P2 catalog observer').toString('base64'), 'X-DSH-Channel': 'control' } });
  socket.on('error', () => {});
  socket.on('message', data => {
    const frame = JSON.parse(data);
    if (frame.kind === 'paired') token = frame.token;
    if (frame.kind === 'sessions') session = frame.items?.find(row => row.sessionId === sessionId);
    if (frame.kind === 'workspaces') workspace = frame.items?.find(row => row.path === workspacePath);
    if (frame.archivedSessionIds) archived = frame.archivedSessionIds.includes(sessionId);
    for (const row of [...waits]) if (row.kind === frame.kind) { clearTimeout(row.timer); waits.splice(waits.indexOf(row), 1); row.resolve(); }
  });
  await ready;
  const server = http.createServer(async (req, res) => {
    res.setHeader('Content-Type', 'application/json');
    try {
      if (req.url === '/status') res.end(JSON.stringify(await refresh()));
      else if (req.url === '/stop' && req.method === 'POST') {
        socket.close(); await management('/mgw/devices/' + device + '/revoke', {});
        res.end('{"stopped":true}'); setTimeout(() => server.close(), 50);
      } else { res.statusCode = 404; res.end('{}'); }
    } catch (_) { res.statusCode = 500; res.end('{"error":"观察失败"}'); }
  });
  server.listen(18895, '127.0.0.1', () => console.log('CATALOG_READY'));
})().catch(() => { console.error('目录观察端未能启动'); process.exitCode = 1; });
