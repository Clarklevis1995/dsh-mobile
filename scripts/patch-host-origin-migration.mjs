import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const packageName = '@deepseek-ai/dsh-session-format-v0-to-v1';
const patches = [
  ['"permission/preset": disposition(["preset"]),',
    '"permission/preset": disposition(["preset"], ["origin"]),'],
  ['case "permission/preset":\n\t\t\tnonEmptyString(data["preset"], `${label} preset`);\n\t\t\treturn;',
    'case "permission/preset":\n\t\t\tnonEmptyString(data["preset"], `${label} preset`);\n\t\t\tif (data["origin"] !== void 0) literalValue(data["origin"], ["default", "selection"], `${label} origin`);\n\t\t\treturn;'],
];

// 只接纳实际旧数据中的来源枚举；其他未知字段和非法来源继续拒绝。
export function patchSource(source) {
  const complete = patches.every(([, after]) => source.split(after).length === 2);
  if (complete) return source;
  for (const [before, after] of patches) {
    assert.equal(source.split(before).length, 2, '迁移器代码与已验证基线不一致，未修改文件');
    assert.equal(source.includes(after), false, '发现部分补丁，未修改文件');
  }
  return patches.reduce((text, [before, after]) => text.replace(before, after), source);
}

const digest = data => createHash('sha256').update(data).digest('hex');

export function patchPackage(packageDirectory, backupDirectory, apply = false) {
  const directory = fs.realpathSync(packageDirectory);
  const metadata = JSON.parse(fs.readFileSync(path.join(directory, 'package.json'), 'utf8'));
  assert.equal(metadata.name, packageName);
  assert.ok(['0.1.5-rc.1', '0.1.5-rc.2'].includes(metadata.version), '仅支持已核对的 0.1.5 rc.1/rc.2 迁移包');
  const target = fs.realpathSync(path.join(directory, 'lib/index.js'));
  const original = fs.readFileSync(target, 'utf8');
  const patched = patchSource(original);
  const result = { package: metadata.name, version: metadata.version, target,
    before: digest(original), after: digest(patched), changed: original !== patched, applied: false };
  if (!apply || original === patched) return result;
  assert.ok(backupDirectory, '--apply 需要 --backup-dir');
  const backup = path.resolve(backupDirectory);
  assert.ok(!backup.startsWith(directory + path.sep) && backup !== directory, '备份必须位于安装包目录之外');
  fs.mkdirSync(backup, { recursive: true, mode: 0o700 });
  const backupPath = path.join(backup, `${result.before}.index.js`);
  if (fs.existsSync(backupPath)) assert.equal(digest(fs.readFileSync(backupPath)), result.before);
  else fs.writeFileSync(backupPath, original, { flag: 'wx', mode: 0o600 });
  const temporary = `${target}.${randomUUID()}.tmp`;
  try {
    // 原子替换，避免就地写入影响 npm/pnpm 的其他硬链接。
    fs.writeFileSync(temporary, patched, { flag: 'wx', mode: fs.statSync(target).mode & 0o777 });
    const fd = fs.openSync(temporary, 'r');
    try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
    assert.equal(digest(fs.readFileSync(target)), result.before, '安装包发生并发修改，停止应用');
    fs.renameSync(temporary, target);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
  assert.equal(digest(fs.readFileSync(target)), result.after);
  return { ...result, applied: true, backupPath };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { values } = parseArgs({ options: {
    package: { type: 'string' }, 'backup-dir': { type: 'string' }, apply: { type: 'boolean', default: false },
  } });
  assert.ok(values.package, '需要 --package 指向 Host 实际使用的迁移包；默认只检查，--apply 才修改');
  console.log(JSON.stringify(patchPackage(values.package, values['backup-dir'], values.apply), null, 2));
}
