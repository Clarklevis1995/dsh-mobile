// 仅读取本轮临时 Host 的追加式 zstd 审批审计；不输出完整会话。
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const root = fs.realpathSync(process.argv[2]);
const file = fs.realpathSync(process.argv[3]);
if (!path.basename(root).startsWith('harmony-p7-owned-host-') || !file.startsWith(root + '/sessions/') || path.basename(file) !== 'session.v4.jsonl.zstd') throw new Error('只允许读取本轮主机审计');
const bytes = fs.readFileSync(file); let offset = 0; const records = [];
while (offset < bytes.length) {
  const frame = zlib.zstdDecompressSync(bytes.subarray(offset), { info: true });
  if (!frame.engine.bytesWritten) throw new Error('审计解码无进展');
  offset += frame.engine.bytesWritten;
  for (const line of frame.buffer.toString('utf8').split('\n')) {
    if (!line.trim()) continue;
    const row = JSON.parse(line);
    if (row.type === 'approval/asked' || row.type === 'approval/decided') records.push(row);
  }
}
process.stdout.write(JSON.stringify(records));
