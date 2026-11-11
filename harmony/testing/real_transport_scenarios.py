"""真实业务经透明网络探针验证确认丢失、晚回应和待审批重连，不伪造Host业务。"""
import json,time,uuid,urllib.request


def run(ui,m,folder,passed,call,session,owned):
    def proxy(route,data=None):
        req=urllib.request.Request('http://127.0.0.1:18908'+route,None if data is None else json.dumps(data).encode(),{'Content-Type':'application/json'})
        return json.load(urllib.request.urlopen(req,timeout=10))
    def wait(fn,timeout=50):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=fn()
            if value:return value
            time.sleep(.35)
        raise AssertionError('真实网络边界状态未按时确认')
    def history():return call('/history',{'id':session['id']})['records']
    def kind(r):return r.get('type') or (r.get('event') or {}).get('type')
    def text(r):
        e=r.get('event') or r.get('data') or {};return e.get('text') or ''.join(b.get('text','') for b in e.get('message',{}).get('content',e.get('content',[])) if b.get('type')=='text')
    def count(marker):return sum(kind(r)=='user/message' and text(r)==marker for r in history())
    def requests(kind,rpc=None):return [r for r in proxy('/audit') if r['direction']=='request' and r['kind']==kind and (not rpc or r.get('rpcId')==rpc)]
    checks=[]
    def done(name,**values):
        checks.append(dict(case=name,**values));(folder/'real-transport-results.json').write_text(json.dumps(dict(gatewayVersion='0.12.0',checks=checks,method='real owned Host and Agent through transparent owned response-delay/disconnect proxy; no fabricated business frames'),ensure_ascii=False,indent=2))
        (folder/'transport-structural-audit.json').write_text(json.dumps(proxy('/audit'),indent=2))
        passed('P1-transport-'+name+'-G',values['detail'])
    for disconnect in [False,True]:
        proxy('/hold',dict(kinds=['sent'],sessionId=session['id']))
        before=len(requests('message'));marker='HARMONY_REAL_CONFIRM_'+uuid.uuid4().hex[:8].upper();ui.input('message-draft',marker);ui.click('message-send')
        wait(lambda:count(marker)==1);wait(lambda:proxy('/status')['held']>0)
        draft='确认期间新草稿-'+uuid.uuid4().hex[:8];ui.input('message-draft',draft)
        if disconnect:proxy('/disconnect',{})
        else:proxy('/release',{})
        wait(lambda:ui.find('message-send') and str(ui.find('message-send').get('enabled')).lower()=='true')
        assert ui.find('message-draft').get('text')==draft
        assert count(marker)==1 and len(requests('message'))==before+1
        ui.screenshot('real-confirm-'+('disconnect' if disconnect else 'late-ack'))
        done('confirmation-disconnect' if disconnect else 'confirmation-edited',detail='真实发送确认被延迟，确认前新草稿保持；'+('断开并自动重连后未自动重发，服务端消息与客户端请求均唯一' if disconnect else '晚确认不清除新草稿，服务端消息与客户端请求均唯一'),userMessages=1,clientRequests=1,draftPreserved=True)
    marker='HARMONY_QUESTION_LATE_'+uuid.uuid4().hex[:8].upper();ui.message(marker)
    pending=wait(lambda:next((r for r in call('/pending') if r.get('sessionId')==session['id'] and r['kind']=='question-requested'),None));rpc=pending['rpcId']
    proxy('/hold',dict(kinds=['question-response','question-resolved'],rpcId=rpc))
    ui.navigate('interaction-inbox','question-option-'+rpc+'-0-0')
    ui.click('question-option-'+rpc+'-0-0',scroll=True);ui.click('question-option-'+rpc+'-1-0',scroll=True);ui.input('question-custom-'+rpc+'-2','真实迟到确认验收',scroll=True);ui.click('question-submit-'+rpc,scroll=True)
    wait(lambda:not any(r['rpcId']==rpc for r in call('/pending')));wait(lambda:proxy('/status')['held']>0)
    ui.text('结果尚未确认',timeout=40);assert len(requests('question-answer',rpc))==1;ui.screenshot('real-question-confirmation-timeout')
    proxy('/release',{});wait(lambda:not ui.find('question-submit-'+rpc));assert len(requests('question-answer',rpc))==1
    ui.click('page-back');ui.wait('message-draft');wait(lambda:not ui.find('message-stop'))
    done('question-timeout-late',detail='真实问题回答已由Host处理，移动端因回应延迟明确显示结果未确认；晚回应后旧入口移除，未自动重答',rpcId=rpc,clientAnswers=1)
    call('/permission',dict(id=session['id'],name='read-only'))
    for reconnect in [False,True]:
        marker='HARMONY_APPROVAL_NETWORK_'+uuid.uuid4().hex[:8].upper();target=owned/'workspace'/(marker+'.txt');ui.message(marker)
        pending=wait(lambda:next((r for r in call('/pending') if r.get('callId')=='approval-'+marker),None));rpc=pending['rpcId'];assert not target.exists()
        ui.navigate('interaction-inbox','approval-reject-'+rpc)
        if reconnect:
            proxy('/disconnect',{});ui.text('已恢复',timeout=45);ui.wait('approval-reject-'+rpc)
            assert len(requests('approval-response',rpc))==0 and not target.exists();ui.screenshot('real-network-approval-replayed')
        else:proxy('/hold',dict(kinds=['approval-response','approval-resolved'],rpcId=rpc))
        ui.click('approval-reject-'+rpc);wait(lambda:not any(r['rpcId']==rpc for r in call('/pending')))
        assert not target.exists()
        if not reconnect:
            wait(lambda:proxy('/status')['held']>0);ui.text('结果尚未确认',timeout=40);ui.screenshot('real-approval-confirmation-timeout');proxy('/release',{})
        wait(lambda:not ui.find('approval-reject-'+rpc));assert len(requests('approval-response',rpc))==1
        ui.click('page-back');ui.wait('message-draft');wait(lambda:not ui.find('message-stop'))
        assert not target.exists();assert call('/options',dict(id=session['id']))['sessionPermissions']['currentValue']=='read-only'
        done('approval-network-replay' if reconnect else 'approval-timeout-late',detail='真实只读write审批'+('经连接中断同rpc恢复，未经选择不执行，明确拒绝后不写文件' if reconnect else '拒绝回应被延迟到本地超时后再释放，旧入口移除且无自动重复提交'),rpcId=rpc,clientDecisions=1,fileExists=False)
