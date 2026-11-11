"""真实专属 Host 的前后台、重复正文身份和重启恢复；不操作用户主机。"""
import json,subprocess,sys,time,uuid


def run(ui,m,folder,passed,call,session,owned):
    checks=[]
    def wait(predicate,timeout=55):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=predicate()
            if value:return value
            time.sleep(.4)
        raise AssertionError('真实恢复状态未按时确认')
    def records():return call('/history',{'id':session['id']})['records']
    def kind(r):return r.get('type') or (r.get('event') or {}).get('type')
    def text(r):
        e=r.get('event') or r.get('data') or {};return e.get('text') or ''.join(b.get('text','') for b in e.get('message',{}).get('content',e.get('content',[])) if b.get('type')=='text')
    def count(marker):return sum(kind(r)=='user/message' and text(r)==marker for r in records())
    def finished(marker):return any(kind(r)=='assistant/message' and marker in text(r) for r in records())
    def record(case,**data):
        checks.append(dict(case=case,**data));(folder/'real-resilience-results.json').write_text(json.dumps(dict(gatewayVersion='0.12.0',checks=checks,scope='real isolated Host; local controlled model; API21 simulator, not locked device'),ensure_ascii=False,indent=2))
    def foreground():
        m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('message-draft',timeout=35)
    # 同文的两次主动发送必须各自有消息身份，正文不是去重键。
    marker='HARMONY_REAL_SAME_'+uuid.uuid4().hex[:8].upper()
    for _ in range(2):
        ui.message(marker);wait(lambda:not ui.find('message-stop'));wait(lambda:finished(marker))
    wait(lambda:count(marker)==2);ui.screenshot('real-same-text-two-requests');record('same-text',userMessages=2)
    passed('P1-real-same-text-G','同一正文主动发送两次，真实历史保留两条用户消息，各轮收束')
    marker='HARMONY_SLOW_BACKGROUND_'+uuid.uuid4().hex[:8].upper()
    before=next(r['streamFrames'] for r in call('/status')['sessions'] if r['id']==session['id'])
    ui.message(marker);wait(lambda:next(r['streamFrames'] for r in call('/status')['sessions'] if r['id']==session['id'])>=before+3)
    draft='前后台恢复草稿-'+uuid.uuid4().hex[:6];ui.input('message-draft',draft)
    m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(4);foreground()
    assert ui.find('message-draft').get('text')==draft
    wait(lambda:finished(marker),timeout=65);wait(lambda:not ui.find('message-stop'))
    final=next(text(r) for r in records() if kind(r)=='assistant/message' and marker in text(r))
    assert final==''.join(marker+':'+str(i)+'\n' for i in range(200)), '长流终帧有遗漏或重复'
    assert count(marker)==1;assert ui.find('message-draft').get('text')==draft
    ui.screenshot('real-background-200-chunks-final');record('background-stream',chunks=200,userMessages=1,exactFinal=True,draftPreserved=True)
    passed('P1-real-background-stream-G','真实200段约40秒流式经Home离开/返回，终帧逐字一致、消息唯一、草稿保留')
    call('/permission',{'id':session['id'],'name':'read-only'})
    marker='HARMONY_APPROVAL_BACKGROUND_'+uuid.uuid4().hex[:8].upper();target=owned/'workspace'/(marker+'.txt');ui.message(marker)
    item=wait(lambda:next((r for r in call('/pending') if r.get('callId')=='approval-'+marker),None));rpc=item['rpcId'];assert not target.exists()
    ui.navigate('interaction-inbox','approval-allow-'+rpc)
    m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(4)
    m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('approval-reject-'+rpc,timeout=35)
    assert not target.exists();ui.screenshot('real-background-pending-approval');ui.click('approval-reject-'+rpc)
    wait(lambda:not any(r['rpcId']==rpc for r in call('/pending')));assert not target.exists();ui.click('page-back');ui.wait('message-draft');wait(lambda:not ui.find('message-stop'))
    record('background-approval',rpcId=rpc,rejected=True,fileExists=False)
    passed('P1-real-background-approval-G','真实待审批状态经Home返回保留同rpc，未经选择无执行，拒绝后不写文件')
    # 重启只允许元数据核验过的本轮临时 Host。恢复后主动发送，不能自动重复旧消息。
    draft='网关重启保持草稿-'+uuid.uuid4().hex[:6];ui.input('message-draft',draft)
    prior=sum(kind(r)=='user/message' for r in records())
    subprocess.run([sys.executable,str(m.ROOT/'harmony/testing/restart_owned_acceptance.py'),'--root',str(owned)],check=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=85)
    wait(lambda:call('/options',{'id':session['id']}).get('sessionPermissions'),timeout=55)
    ui.wait('message-draft',timeout=35);wait(lambda:str(ui.find('message-send').get('enabled')).lower()=='true')
    assert ui.find('message-draft').get('text')==draft
    assert sum(kind(r)=='user/message' for r in records())==prior
    marker='HARMONY_REAL_RESTART_'+uuid.uuid4().hex[:8].upper();ui.message(marker);wait(lambda:finished(marker));assert count(marker)==1
    assert not next(r['sequenceConflicts'] for r in call('/status')['sessions'] if r['id']==session['id'])
    ui.screenshot('real-host-restart-continued-chat');record('host-restart',oldMessagesReplayed=0,draftPreserved=True,newUserMessages=1,sequenceConflicts=0)
    passed('P1-real-host-restart-idle-G','专属真实Host重启后身份重连，原消息不重发，草稿保留，继续发送唯一且无序列冲突')
