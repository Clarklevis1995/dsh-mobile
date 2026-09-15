import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const { values } = parseArgs({ options: { 'modules-root': { type: 'string' }, fixture: { type: 'string' } } });
assert.ok(values['modules-root'], '需要 --modules-root 指定包含 @deepseek-ai 的 node_modules');
const entry = path.join(values['modules-root'], '@deepseek-ai/dsh-session-format-catalog/lib/index.js');
const { sessionFormatCatalog: catalog } = await import(pathToFileURL(entry).href);
function restore(rows) {
  // 旧版紧凑 chunk 解码器会复用传入数组；每次验证提供独立输入。
  const detached = structuredClone(rows);
  const decoder = catalog.createRestore(detached[0], { recovery: 'strict', validation: 'current' });
  detached.slice(1).forEach(row => decoder.decodeRow(row));
  return decoder.finish();
}
function roundTrip(artifact) {
  const physical = [catalog.encodeCurrentHeader(artifact.header, artifact.inheritedEventCount),
    ...artifact.events.map(event => catalog.encodeCurrentEvent(event))];
  assert.deepEqual(restore(JSON.parse(JSON.stringify(physical))), artifact);
}
const header = { type: 'session', version: 0, id: 'session-origin-regression', createdAt: 1, delegationDepth: 0 };
const event = data => ({ type: 'permission/preset', seq: 0, time: 2, data });
let checks = 0;
for (const origin of [undefined, 'default', 'selection']) {
  const data = { preset: 'workspace-write', ...(origin === undefined ? {} : { origin }) };
  const rows = [header, event(data)];
  const original = JSON.stringify(rows);
  const artifact = restore(rows);
  assert.equal(artifact.header.version, 3);
  assert.deepEqual(artifact.events[0].data, data);
  assert.equal(JSON.stringify(rows), original, '不得修改输入历史');
  roundTrip(artifact);
  checks++;
}
for (const origin of [null, 1, {}, [], '', 'unknown']) {
  assert.throws(() => restore([header, event({ preset: 'workspace-write', origin })]), /origin/);
  checks++;
}
assert.throws(() => restore([header, event({ preset: 'workspace-write', origin: 'default', unexpected: true })]), /unexpected/);
assert.throws(() => restore([header, event({ origin: 'default' })]), /preset/);
checks += 2;
if (values.fixture) {
  const before = fs.readFileSync(values.fixture);
  // Session 是多个拼接的 zstd 帧；Node 的单帧解码会只得到头部，造成空数据假通过。
  const decoded = execFileSync('zstd', ['-dc'], { input: before, maxBuffer: 256 * 1024 * 1024 });
  const rows = decoded.toString().trim().split('\n').map(JSON.parse);
  assert.ok(rows.some(row => row.type === 'permission/preset' && row.data.origin !== undefined),
    '真实回归样本必须包含带 origin 的旧权限事件，不能只验证头部');
  const source = JSON.stringify(rows);
  const actual = restore(rows);
  const withoutOrigin = structuredClone(rows);
  for (const row of withoutOrigin) if (row.type === 'permission/preset') delete row.data.origin;
  const reference = restore(withoutOrigin);
  const comparable = structuredClone(actual);
  for (const row of comparable.events) if (row.type === 'permission/preset') delete row.data.origin;
  assert.deepEqual(comparable, reference, '除保留 origin 外，迁移结果必须完全相同');
  roundTrip(actual);
  assert.equal(JSON.stringify(rows), source);
  assert.deepEqual(fs.readFileSync(values.fixture), before);
  console.log(JSON.stringify({ fixtureRows: rows.length, restoredEvents: actual.events.length,
    sourceSha256: createHash('sha256').update(before).digest('hex') }));
  checks++;
}
console.log(`PASS ${checks} migration checks`);
