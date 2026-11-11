// 实际HostRuntime与视图模型：同一UI帧合并移除/重放时，ForEach稳定ID必须仍指向原对象。
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const ts=require('/Applications/DevEco-Studio.app/Contents/plugins/openharmony/ace-server/node_modules/typescript/lib/typescript.js');
const root=path.resolve(__dirname,'../../harmonyApp/entry/src/main/ets');
function environment(){
 const cache=new Map();
 function load(file){
  if(cache.has(file))return cache.get(file);
  const module={exports:{}};
  const req=id=>id==='./GatewayClient'?{GatewayClient:class{}}:load(path.posix.normalize(path.posix.join(path.posix.dirname(file),id)));
  const js=ts.transpileModule(fs.readFileSync(path.join(root,file+'.ets'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2021,experimentalDecorators:true}}).outputText;
  vm.runInNewContext(js,{module,exports:module.exports,require:req,Observed:C=>C,Date,console},{filename:file});cache.set(file,module.exports);return module.exports;
 }
 const runtime=new (load('core/HostRuntime').HostRuntime)('owned');const {emptyState}=load('core/Models');
 const state=()=>({...emptyState(),connected:true,selectedSessionId:'s'});
 const update=partial=>runtime.update({...state(),...partial},[],'已连接','');
 return{runtime,update};
}
const approval=(rpcId,replay=false)=>({rpcId,sessionId:'s',toolName:'write',reason:'raw',reasonEnglish:'raw en',status:'idle',replay});
test('审批经空快照和同rpc重放继续更新原Observed对象',()=>{
 const {runtime,update}=environment();update({approvals:[approval('a')]});const view=runtime.approvals[0];
 update({connected:false,approvals:[]});update({approvals:[{...approval('a',true),reason:'restored',status:'rejected',error:'unconfirmed'}]});
 assert.equal(runtime.approvals[0],view);assert.equal(view.replay,true);assert.equal(view.reason,'restored');assert.equal(view.error,'unconfirmed');
});
test('问题经握手空快照重放仍更新原对象，移除项不留在可见列表',()=>{
 const {runtime,update}=environment();const q={rpcId:'q',sessionId:'s',questions:[],status:'idle'};
 update({questions:[q]});const view=runtime.questions[0];update({questions:[]});assert.equal(runtime.questions.length,0);
 update({questions:[{...q,status:'submitting'}]});assert.equal(runtime.questions[0],view);assert.equal(view.status,'submitting');
});
test('失效交互对象缓存有界，不影响新的可见项',()=>{
 const {runtime,update}=environment();update({approvals:[approval('old')]});const old=runtime.approvals[0];
 for(let n=0;n<160;n++){update({approvals:[approval('a-'+n)]});update({approvals:[]});}
 update({approvals:[approval('old',true)]});assert.notEqual(runtime.approvals[0],old);assert.equal(runtime.approvals[0].replay,true);assert.equal(runtime.approvals.length,1);
});

test('目录更新复用同一会话视图时，共享未读和已读清除均能到达观察对象',()=>{
 const {runtime,update}=environment();const row={id:'s',title:'owned',running:false,cwd:'/owned',hasConversation:true,lastActivityEpochSeconds:100};
 update({sessions:[{...row,unread:false}]});const view=runtime.sessions[0];
 update({sessions:[{...row,unread:true}]});assert.equal(runtime.sessions[0],view);assert.equal(view.unread,true);
 update({sessions:[{...row,unread:false,running:true}]});assert.equal(runtime.sessions[0],view);assert.equal(view.unread,false);assert.equal(view.running,true);
});
