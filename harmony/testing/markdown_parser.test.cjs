// 实际生产Worker协调器：匹配乱序结果、空闲释放、超时/崩溃与旧线程迟到隔离。
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const ts=require('/Applications/DevEco-Studio.app/Contents/plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js');
function setup(){
 const workers=[],timers=new Map();let serial=0;
 class ThreadWorker{
  constructor(url){this.url=url;this.sent=[];this.terminated=0;workers.push(this);}
  postMessage(value){this.sent.push(value);}
  terminate(){this.terminated++;}
  reply(id,result){this.onmessage({data:{id,result,failed:false}});}
 }
 const module={exports:{}};
 const code=ts.transpileModule(fs.readFileSync(path.resolve(__dirname,'../../harmonyApp/entry/src/main/ets/core/MarkdownParser.ets'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2021}}).outputText;
 vm.runInNewContext(code,{module,exports:module.exports,require:id=>id==='@kit.ArkTS'?{worker:{ThreadWorker}}:{},setTimeout(fn,delay){const id=++serial;timers.set(id,{fn,delay});return id;},clearTimeout(id){timers.delete(id);}});
 const parser=new module.exports.MarkdownParser();
 return {parser,workers,run(delay){for(const [id,row]of [...timers])if(row.delay===delay&&timers.has(id)){timers.delete(id);row.fn();}}};
}
test('并发解析共享一线程、乱序结果按请求匹配，全部完成后空闲释放并可重建',async()=>{
 const e=setup(),a=e.parser.parse('first'),b=e.parser.parse('second',true);assert.equal(e.workers.length,1);const w=e.workers[0];assert.equal(w.sent[1].plain,true);
 const lateExit=w.onexit,lateMessage=w.onmessage;w.reply(w.sent[1].id,'second-plain');e.run(2000);assert.equal(w.terminated,0,'仍有请求时不能销毁线程');w.reply(w.sent[0].id,'first-json');assert.deepEqual(await Promise.all([a,b]),['first-json','second-plain']);e.run(2000);assert.equal(w.terminated,1);
 const c=e.parser.parse('third');assert.equal(e.workers.length,2);lateExit(0);lateMessage({data:{id:w.sent[0].id,result:'old',failed:false}});const fresh=e.workers[1];assert.equal(fresh.terminated,0);fresh.reply(fresh.sent[0].id,'fresh');assert.equal(await c,'fresh');
});
test('线程超时会结束全部在途请求并释放，旧线程错误不影响新一轮',async()=>{
 const e=setup(),a=e.parser.parse('one'),b=e.parser.parse('two');const lateError=e.workers[0].onerror;const checks=Promise.all([assert.rejects(a,/unavailable/),assert.rejects(b,/unavailable/)]);e.run(30000);await checks;assert.equal(e.workers[0].terminated,1);
 const next=e.parser.parse('next'),fresh=e.workers[1];lateError();assert.equal(fresh.terminated,0);fresh.reply(fresh.sent[0].id,'ok');assert.equal(await next,'ok');
});
test('等待队列有界，线程异常明确失败且不会创建无界Worker',async()=>{
 const e=setup(),requests=Array.from({length:64},(_,i)=>e.parser.parse('source-'+i).catch(error=>error.message));await assert.rejects(e.parser.parse('overflow'),/busy/);assert.equal(e.workers.length,1);assert.equal(e.workers[0].sent.length,64);
 e.workers[0].onmessageerror();assert.ok((await Promise.all(requests)).every(message=>message==='markdown-parser-unavailable'));assert.equal(e.workers[0].terminated,1);
});
