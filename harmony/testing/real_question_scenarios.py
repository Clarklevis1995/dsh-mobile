"""真实 Agent 问题取消、冷重连重放、外端回答和 Agent 取消的原生验收。"""
import json,time,uuid

def run(ui,m,folder,passed,call,session):
    def wait(predicate,timeout=40):
        until=time.monotonic()+timeout
        while time.monotonic()<until:
            value=predicate()
            if value:return value
            time.sleep(.3)
        raise AssertionError('真实问题状态未按时确认')
    results=[]
    for mode in ['CANCEL','EXTERNAL_REPLAY','AGENT_CANCEL']:
        marker='HARMONY_QUESTION_'+mode+'_'+uuid.uuid4().hex[:8].upper();ui.message(marker)
        pending=wait(lambda:next((r for r in call('/pending') if r['sessionId']==session['id'] and r['kind']=='question-requested'),None));rpc=pending['rpcId'];assert len(pending['questions'])==3
        ui.navigate('interaction-inbox','question-option-'+rpc+'-0-0');ui.screenshot('question-'+mode.lower()+'-pending')
        if mode=='CANCEL':ui.click('question-cancel-'+rpc,scroll=True)
        elif mode=='EXTERNAL_REPLAY':
            m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE)
            ui.wait('home-settings',timeout=40);ui.text('已连接',timeout=40);ui.navigate('interaction-inbox','question-option-'+rpc+'-0-0')
            assert any(r['rpcId']==rpc for r in call('/pending'))
            ui.screenshot('real-question-same-rpc-replayed')
            answers=[dict(id=q['id'],selected=[q['options'][0]['label']] if q.get('options') else [],**({'custom':'HARMONY_EXTERNAL_ANSWER'} if not q.get('options') else {})) for q in pending['questions']]
            call('/question',{'rpcId':rpc,'answers':answers})
        else:call('/cancel',{'id':session['id']})
        wait(lambda:not any(r['rpcId']==rpc for r in call('/pending')))
        deadline=time.monotonic()+15
        while ui.find('question-submit-'+rpc):
            assert time.monotonic()<deadline,'已处理问题仍可再次提交';time.sleep(.3)
        ui.screenshot('question-'+mode.lower()+'-settled');ui.click('page-back')
        if mode=='EXTERNAL_REPLAY':ui.wait('home-settings');ui.click('session-'+session['id'])
        ui.wait('message-draft');wait(lambda:not ui.find('message-stop'))
        records=call('/history',{'id':session['id']})['records']
        def text(r):
            e=r.get('event') or r.get('data') or {};return e.get('text') or ''.join(b.get('text','') for b in e.get('content',[]) if b.get('type')=='text')
        assert sum((r.get('type') or r.get('event',{}).get('type'))=='user/message' and text(r)==marker for r in records)==1
        results.append(dict(case=mode,rpcId=rpc,pendingRemoved=True,userMessageCount=1))
        (folder/'real-question-results.json').write_text(json.dumps(dict(gatewayVersion='0.12.0',checks=results,scope='real UserQuestions with controlled local model; owned Host only'),ensure_ascii=False,indent=2))
        passed('P1-real-question-'+mode+'-G','真实三题问题的'+mode+'通过；同一rpc重放、明确取消/另一端处理后移除过期入口，独立观察用户消息仅一次')
