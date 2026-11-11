#!/usr/bin/env python3
"""通过真实Host正常消息/Agent链路创建千条事件历史，只使用本轮专属工作区。"""
import json,os,tempfile,time,uuid,urllib.request
from pathlib import Path


def main():
    owned=Path(os.environ['HARMONY_OWNED_REAL_ROOT']).resolve();meta=json.loads((owned/'owned-processes.json').read_text());assert owned.name.startswith('harmony-p7-owned-host-') and meta['port']==18897 and meta['observerPort']==18899
    folder=Path(tempfile.mkdtemp(prefix='harmony-real-history-seed-'));print('REPORT_DIR',folder,flush=True)
    def call(route,data=None):
        req=urllib.request.Request('http://127.0.0.1:18899'+route,None if data is None else json.dumps(data).encode(),{'Content-Type':'application/json'});return json.load(urllib.request.urlopen(req,timeout=30))
    reuse=os.environ.get('HARMONY_HISTORY_RESUME_SESSION')
    session=next(r for r in call('/status')['sessions'] if r['id']==reuse) if reuse else call('/create',{'label':'P1-history-'+uuid.uuid4().hex[:6]})
    call('/subscribe',{'id':session['id']})
    run=uuid.uuid4().hex[:8].upper();markers=[];records=[]
    def kind(r):return r.get('type') or (r.get('event') or {}).get('type')
    def text(r):
        e=r.get('event') or r.get('data') or {};return e.get('text') or ''.join(b.get('text','') for b in e.get('message',{}).get('content',e.get('content',[])) if b.get('type')=='text')
    if reuse:
        import re
        records=call('/history',{'id':session['id']})['records'];first=next(text(r) for r in records if kind(r)=='user/message' and text(r).startswith('HARMONY_HISTORY_'))
        match=re.fullmatch(r'HARMONY_HISTORY_([A-F0-9]{8})_000',first);assert match;run=match.group(1)
    for index in range(128):
        marker='HARMONY_HISTORY_'+run+'_'+str(index).zfill(3);markers.append(marker)
        if not any(kind(r)=='user/message' and text(r)==marker for r in records):call('/message',{'id':session['id'],'text':marker})
        deadline=time.monotonic()+35
        while time.monotonic()<deadline:
            records=call('/history',{'id':session['id']})['records']
            if any(kind(r)=='assistant/message' and text(r)==marker for r in records) and sum(kind(r)=='turn/end' for r in records)>=index+1:break
            time.sleep(.12)
        else:raise AssertionError('本轮真实历史构造未按时完成')
        if (index+1)%16==0:print('SEED_PROGRESS',index+1,'turns',len(records),'events',flush=True)
    users=[r for r in records if kind(r)=='user/message' and text(r).startswith('HARMONY_HISTORY_'+run+'_')];assistants=[r for r in records if kind(r)=='assistant/message'];assert len(users)==len(assistants)==128
    assert [text(r) for r in users]==markers and [text(r) for r in assistants]==markers
    seqs=[r['seq'] for r in records];assert len(seqs)==len(set(seqs)) and len(records)>=1000
    value=dict(root=str(owned),sessionId=session['id'],name=session['name'],run=run,markers=markers,eventCount=len(records),eventSequences=seqs,projectedSequences=[r['seq'] for r in records if (kind(r)=='user/message' or kind(r)=='assistant/message') and text(r).startswith('HARMONY_HISTORY_'+run+'_')],userCount=len(users),assistantCount=len(assistants),complete=True,method='real Host /message, real Agent and persisted turn/end, controlled local model; no synthetic history injection')
    (folder/'seed.json').write_text(json.dumps(value,ensure_ascii=False,indent=2));Path('/private/tmp/harmony-owned-history-seed-path').write_text(str(folder/'seed.json'))
    print('SEED_READY',session['id'],len(records),'events',flush=True)
if __name__=='__main__':main()
