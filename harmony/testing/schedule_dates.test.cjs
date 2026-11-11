const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');
const ts = require(path.join(process.env.DEVECO_STUDIO_PATH || '/Applications/DevEco-Studio.app/Contents', 'plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js'));
const source = fs.readFileSync(path.resolve(__dirname, '../../harmonyApp/entry/src/main/ets/core/ScheduleDates.ets'), 'utf8');
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
function dates() { const module = { exports: {} }; vm.runInNewContext(code, { module, exports: module.exports, Date, Number }); return module.exports; }
test('日期选择拒绝非法日，不将 2 月 29 日静默改为 3 月', () => {
  const { localScheduleInstant } = dates();
  assert.equal(localScheduleInstant(2026, 1, 29, 9, 0), undefined);
  assert.ok(localScheduleInstant(2028, 1, 29, 9, 0));
});
test('设备本地时间拒绝 DST 缺失小时，重复小时使用较早时刻', () => {
  const script = `const module = {exports:{}}; const exports = module.exports; ${code}; process.stdout.write(JSON.stringify([module.exports.localScheduleInstant(2026,2,8,2,30),module.exports.localScheduleInstant(2026,10,1,1,30)]));`;
  const child = spawnSync(process.execPath, ['-e', script], { env: { ...process.env, TZ: 'America/New_York' }, encoding: 'utf8' });
  assert.equal(child.status, 0, child.stderr);
  assert.deepEqual(JSON.parse(child.stdout), [null, '2026-11-01T05:30:00.000Z']);
});
