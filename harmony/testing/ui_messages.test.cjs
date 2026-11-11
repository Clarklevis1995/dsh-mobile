// 验证真实平台解析器的动态语言、原文保护和参数替换边界。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('/Applications/DevEco-Studio.app/Contents/plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js');
const file = path.resolve(__dirname, '../../harmonyApp/entry/src/main/ets/core/UiMessages.ets');
const moduleValue = {exports:{}};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(file, 'utf8'), {compilerOptions:{module:ts.ModuleKind.CommonJS}}).outputText, {module:moduleValue,exports:moduleValue.exports});
const resolve = moduleValue.exports.resolveUiMessage;
test('保留语义文案后切换语言即时重新解析', () => {
  const message = {key:'voice.status.releaseToCancel'};
  assert.equal(resolve(message,false),'松手取消');
  assert.equal(resolve(message,true),'Release to cancel');
  assert.equal(resolve(message,false),'松手取消');
});
test('主机原文不按本地文案或占位符二次解释', () => {
  const text='voice.status.recording %1$s 主机原文';
  assert.equal(resolve({key:'raw',args:[text]},true),text);
  assert.equal(resolve({key:'raw'},false),'');
});
test('主干已使用的共享语义 key 均有鸿蒙中英翻译', () => {
  const folder=path.resolve(__dirname,'../../shared/src/commonMain/kotlin/com/clarklevis/dsh/shared/facade');
  for(const name of fs.readdirSync(folder).filter(x=>x.endsWith('.kt'))){
    const source=fs.readFileSync(path.join(folder,name),'utf8');
    for(const match of source.matchAll(/UiMessage\("([^"$]+)"/g)){
      const key=match[1];
      assert.notEqual(resolve({key},false),key,name+': '+key);
      assert.notEqual(resolve({key},true),key,name+': '+key);
    }
  }
});

test('参数中的占位符和百分号不被递归替换', () => {
  const value={key:'wire.decodeFailed.missingFields',args:['payload %2$s 100%', 'requestId']};
  assert.equal(resolve(value,false),'payload %2$s 100%: 缺少必填字段 requestId');
});
