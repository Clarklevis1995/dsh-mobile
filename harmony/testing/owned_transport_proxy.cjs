// 仅本轮独立Host的透明网络故障探针；不生成业务帧、不记录认证和正文。
const http=require('node:http'),fs=require('node:fs'),path=require('node:path');
const root=fs.realpathSync(process.env.HARMONY_OWNED_REAL_ROOT||'');
const meta=JSON.parse(fs.readFileSync(path.join(root,'owned-processes.json'),'utf8'));
if(!path.basename(root).startsWith('harmony-p7-owned-host-')||meta.port!==18897||meta.observerPort!==18899)throw Error('only owned Host');
const {WebSocket,WebSocketServer}=require(require.resolve('ws',{paths:[process.env.HARMONY_GATEWAY_PACKAGE||'/Users/lichaofan/DeepseekHarnessProject/dsh-plugin-mobile-gateway']}));
const pairs=new Set(),audit=[];let rule,held=[],serial=0,offline=false;
function record(direction,frame,channel){audit.push({direction,kind:frame.type||frame.kind,rpcId:frame.rpcId,requestId:frame.requestId,sessionId:frame.sessionId,channel,replay:frame.replay,time:Date.now()});if(audit.length>4000)audit.shift();}
const web=http.createServer((req,res)=>{res.writeHead(404);res.end();});
const wss=new WebSocketServer({noServer:true});
web.on('upgrade',(req,socket,head)=>{
 if(offline){socket.write('HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n');socket.destroy();return;}
 if(req.url!=='/ws/mobile'){socket.destroy();return;}
 wss.handleUpgrade(req,socket,head,client=>{
  const headers={};for(const name of ['authorization','x-dsh-device-id','x-dsh-device-name','x-dsh-channel'])if(req.headers[name])headers[name]=req.headers[name];
  const channel=headers['x-dsh-channel'];const protocols=(req.headers['sec-websocket-protocol']||'').split(',').map(x=>x.trim()).filter(Boolean);
  const upstream=new WebSocket('ws://127.0.0.1:18897/ws/mobile',protocols,{headers});
  const pair={id:++serial,client,upstream};pairs.add(pair);const queue=[];
  client.on('message',(bytes,isBinary)=>{let frame;try{frame=JSON.parse(bytes.toString());record('request',frame,channel);}catch{}if(upstream.readyState===WebSocket.OPEN)upstream.send(bytes,{binary:isBinary});else if(queue.length<20)queue.push([bytes,isBinary]);else client.close(1009);});
  upstream.on('open',()=>{for(const [bytes,isBinary]of queue)upstream.send(bytes,{binary:isBinary});queue.length=0;});
  upstream.on('message',(bytes,isBinary)=>{
   let frame;try{frame=JSON.parse(bytes.toString());record('response',frame,channel);}catch{}
   if(rule&&frame&&rule.kinds.includes(frame.kind)&&(!rule.rpcId||rule.rpcId===frame.rpcId)&&(!rule.sessionId||rule.sessionId===frame.sessionId)){
    held.push({pair,bytes,isBinary});return;
   }
   if(client.readyState===WebSocket.OPEN)client.send(bytes,{binary:isBinary});
  });
  const close=()=>{pairs.delete(pair);client.close(1001);upstream.close(1001);};client.on('close',close);upstream.on('close',close);client.on('error',()=>{});upstream.on('error',()=>{});
 });
});
const control=http.createServer(async(req,res)=>{
 res.setHeader('Content-Type','application/json');
 try{let body='';for await(const chunk of req){body+=chunk;if(body.length>10000)throw Error('too large');}const args=body?JSON.parse(body):{};let result;
 if(req.url==='/status')result={owned:true,upstream:18897,connections:pairs.size,held:held.length,offline};
 else if(req.url==='/audit')result=audit;
 else if(req.url==='/hold'){if(!Array.isArray(args.kinds)||args.kinds.some(k=>!['history','sent','question-response','question-resolved','approval-response','approval-resolved'].includes(k)))throw Error('unsupported filter');rule=args;result={holding:true};}
 else if(req.url==='/release'){rule=undefined;const count=held.length;for(const row of held)if(row.pair.client.readyState===WebSocket.OPEN)row.pair.client.send(row.bytes,{binary:row.isBinary});held=[];result={released:count};}
 else if(req.url==='/offline'){offline=true;rule=undefined;held=[];for(const p of pairs){p.client.terminate();p.upstream.terminate();}result={offline:true};}
 else if(req.url==='/online'){offline=false;result={offline:false};}
 else if(req.url==='/disconnect'){rule=undefined;held=[];for(const p of pairs){p.client.terminate();p.upstream.terminate();}result={disconnected:true};}
 else{res.statusCode=404;result={error:'not-found'};}res.end(JSON.stringify(result));
 }catch{res.statusCode=400;res.end(JSON.stringify({error:'owned transport probe failed'}));}
});
function stop(){for(const p of pairs){p.client.terminate();p.upstream.terminate();}web.close();control.close();wss.close();}
process.on('SIGTERM',stop);web.listen(18907,'127.0.0.1');control.listen(18908,'127.0.0.1',()=>console.log('OWNED_TRANSPORT_READY'));
