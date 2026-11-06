// 用本机官方发布包验证移动端发送的 typed timing / RRULE 语义。
// node scripts/test_schedule_timing_contract.mjs /path/to/node_modules/@deepseek-ai/dsh-util-time/lib/index.js
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
const { RRule, Cron } = await import(pathToFileURL(process.argv[2]).href);
function parse(expression) {
  const result = RRule.parse(expression, { rejectIneffectiveSetPositions: true });
  assert.equal(result.kind, 'accepted', JSON.stringify(result));
  return result.rule;
}
for (const frequency of ['MINUTELY', 'HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY']) {
  const extra = frequency === 'WEEKLY' ? ';BYDAY=MO,WE,FR' : frequency === 'MONTHLY' ? ';BYMONTHDAY=31,-1;BYSETPOS=1' : '';
  parse(`DTSTART;TZID=Asia/Shanghai:20990131T080037\nRRULE:FREQ=${frequency};INTERVAL=2${extra}`);
}
const monthEnd = parse('DTSTART;TZID=Asia/Shanghai:20270131T080000\nRRULE:FREQ=MONTHLY;INTERVAL=1;BYMONTHDAY=31,-1;BYSETPOS=1');
assert.equal(monthEnd.next(Date.parse('2027-02-01T00:00:00Z')), '2027-02-28T00:00:00.000Z');
const dst = parse('DTSTART;TZID=America/New_York:20270301T080037\nRRULE:FREQ=DAILY;INTERVAL=2');
assert.equal(dst.next(Date.parse('2027-03-13T14:00:00Z')), '2027-03-15T12:00:37.000Z');
const weekly = parse('DTSTART;TZID=Europe/Berlin:20270104T090045\nRRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE');
assert.equal(weekly.next(Date.parse('2027-01-06T10:00:00Z')), '2027-01-18T08:00:45.000Z');
assert.equal(Cron.parse('0 9 * * 1-5', 'Europe/London').kind, 'accepted');
console.log('官方 0.2.2-rc.1 契约检查通过：5 种重复规则、月末、夏令时、隔周和 Cron。');
