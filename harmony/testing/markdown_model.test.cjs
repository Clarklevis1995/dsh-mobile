// 只验证实际模型的空白保护与 UI 镜像策略；第三方词法解析由原生端到端用例验证。
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require(path.join(process.env.DEVECO_STUDIO_PATH || '/Applications/DevEco-Studio.app/Contents', 'plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js'));
const root = path.resolve(__dirname, '../../harmonyApp/entry/src/main/ets/core');
function load(name, lexer = () => []) {
  const module = { exports: {} };
  const code = ts.transpileModule(fs.readFileSync(path.join(root, name + '.ets'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2021, experimentalDecorators: true }
  }).outputText;
  vm.runInNewContext(code, { module, exports: module.exports, Observed: type => type,
    require: id => id === '@lidary/markdown/src/main/ets/core/marked' ? { lexer } : load(id.replace('./', ''), lexer) });
  return module.exports;
}

test('代码围栏保护 TAB 与 CRLF，正文不改写且与私用字符不碰撞', () => {
  const { protectCodeWhitespace } = load('MarkdownModel');
  const source = '\uE000\n正文\t内容\n```kotlin\r\n\tval x = 1\r\n```\r\n';
  const value = protectCodeWhitespace(source);
  assert.ok(value.source.includes('正文\t内容'));
  assert.ok(value.source.includes(value.tab + 'val x'));
  assert.equal(value.source.split(value.tab).join('\t').split(value.carriage).join('\r'), source);
});
test('长围栏中的短围栏不会提前解除代码保护，未闭合流式围栏也可保留', () => {
  const { protectCodeWhitespace } = load('MarkdownModel');
  const value = protectCodeWhitespace('````text\n```\n\tstill code\n');
  assert.ok(value.source.includes(value.tab + 'still code'));
  const tilde = protectCodeWhitespace('~~~sh\n\techo hi\n~~~\ntext\tplain');
  assert.ok(tilde.source.endsWith('text\tplain'));
});
test('原生行内投影保留嵌套强调和链接语义，不执行 HTML', () => {
  const tokens = [{ type: 'paragraph', raw: '**hello**', text: '**hello**', tokens: [
    { type: 'strong', raw: '**hello**', text: 'hello', tokens: [{ type: 'text', raw: 'hello', text: 'hello' }] },
    { type: 'link', raw: '[文档](https://example.com)', text: '文档', href: 'https://example.com', tokens: [{ type: 'text', raw: '文档', text: '文档' }] },
    { type: 'html', raw: '<script>', text: '<script>' }
  ] }];
  const { parseMarkdown } = load('MarkdownModel', () => tokens);
  const [block] = parseMarkdown('source');
  assert.equal(block.text, 'hello文档');
  assert.equal(block.runs[0].bold, true);
  assert.equal(block.runs[1].href, 'https://example.com');
});
test('稳定块对象也会接收同原文的引用链接补全', () => {
  const { MarkdownBlock } = load('MarkdownBlock');
  const first = { id: '0:paragraph', kind: 'paragraph', raw: '[a][ref]', text: 'a', runs: [], depth: 0, quote: false, prefix: '', language: '', header: [], rows: [] };
  const block = new MarkdownBlock(first);
  block.update({ ...first, runs: [{ text: 'a', bold: false, italic: false, strike: false, code: false, href: 'https://example.com', light: '', dark: '' }] });
  assert.equal(block.runs[0].href, 'https://example.com');
  assert.equal(block.id, '0:paragraph');
});

test('表格沿用词法树列对齐，包括未复制 align 属性的单元格', () => {
  const c = text => ({ text, tokens: [{ type: 'text', raw: text, text }], align: null });
  const tokens = [{ type: 'table', raw: 'table', align: ['left', 'right', 'center'], header: [c('名称'), c('数量'), c('备注')], rows: [[c('鲸鱼'), c('2'), c('中文长单元格')]] }];
  const { parseMarkdown } = load('MarkdownModel', () => tokens);
  const [block] = parseMarkdown('table');
  assert.deepEqual(Array.from(block.header, cell => cell.align), ['left', 'right', 'center']);
  assert.deepEqual(Array.from(block.rows[0].cells, cell => cell.align), ['left', 'right', 'center']);
});
