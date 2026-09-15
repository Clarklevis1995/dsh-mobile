import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { patchPackage, patchSource } from './patch-host-origin-migration.mjs';

const source = '"permission/preset": disposition(["preset"]),\ncase "permission/preset":\n\t\t\tnonEmptyString(data["preset"], `${label} preset`);\n\t\t\treturn;';
test('拒绝未知代码或部分补丁；重复应用不改变结果', () => {
  const patched = patchSource(source);
  assert.equal(patchSource(patched), patched);
  assert.throws(() => patchSource('unknown'));
  assert.throws(() => patchSource(source + source));
  assert.throws(() => patchSource(source.replace('disposition(["preset"])', 'disposition(["preset"], ["origin"])')));
});
test('默认只检查；应用先备份且不改变共享硬链接', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-origin-patch-test-'));
  try {
    const pkg = path.join(dir, 'package'); fs.mkdirSync(path.join(pkg, 'lib'), { recursive: true });
    fs.writeFileSync(path.join(pkg, 'package.json'), JSON.stringify({ name: '@deepseek-ai/dsh-session-format-v0-to-v1', version: '0.1.5-rc.2' }));
    const target = path.join(pkg, 'lib/index.js'); fs.writeFileSync(target, source);
    const linked = path.join(dir, 'shared-cache'); fs.linkSync(target, linked);
    assert.equal(patchPackage(pkg).applied, false);
    assert.equal(fs.readFileSync(target, 'utf8'), source);
    const result = patchPackage(pkg, path.join(dir, 'backup'), true);
    assert.equal(result.applied, true);
    assert.equal(fs.readFileSync(result.backupPath, 'utf8'), source);
    assert.equal(fs.readFileSync(linked, 'utf8'), source);
    assert.equal(patchPackage(pkg, path.join(dir, 'backup'), true).changed, false);
  } finally { fs.rmSync(dir, { recursive: true, force: true }); }
});
