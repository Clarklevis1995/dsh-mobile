"""真实长生成/待确认/待审批的网络与Host重启；不操作用户网关。"""
import json,subprocess,sys,time,uuid,urllib.request


def run(ui,m,folder,passed,call,session,owned):
    checks=[]
    def proxy(route,data=None):
        req=urllib.request.Request('http://127.0.0.1:18908'+route,None if data is None else json.dumps(data).encode(),{'Content-Type':'application/json'})
        return json.load(urllib.request.urlopen(req,timeout=10))
    def wait(fn,timeout=55):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            try:
                value=fn()
                if value:return value
            except urllib.error.URLError:pass
            time.sleep(.4)
        raise AssertionError('真实网关恢复矩阵未按时确认')
    def history():return call('/history',{'id':session['id']})['records']
    def kind(r):return r.get('type') or (r.get('event') or {}).get('type')
    def text(r):
        e=r.get('event') or r.get('data') or {};return e.get('text') or ''.join(b.get('text','') for b in e.get('message',{}).get('content',e.get('content',[])) if b.get('type')=='text')
    def users(marker):return sum(kind(r)=='user/message' and text(r)==marker for r in history())
    def reply(marker):return next((text(r) for r in history() if kind(r)=='assistant/message' and marker in text(r)),None)
    def count_requests(kind_name,rpc=None):return len([r for r in proxy('/audit') if r['direction']=='request' and r['kind']==kind_name and (not rpc or r.get('rpcId')==rpc)])
    def restart():
        subprocess.run([sys.executable,str(m.ROOT/'harmony/testing/restart_owned_acceptance.py'),'--root',str(owned)],check=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=85)
        wait(lambda:call('/options',{'id':session['id']}).get('sessionPermissions'))
    def done(case,detail,**data):
        checks.append(dict(case=case,**data));(folder/'real-generation-network.json').write_text(json.dumps(dict(gatewayVersion='0.12.0',checks=checks,method='real owned Host with controlled local upstream and transparent local transport fault; API21 simulator, not lock/background D'),ensure_ascii=False,indent=2))
        (folder/'transport-structural-audit.json').write_text(json.dumps(proxy('/audit'),indent=2));passed('P1-generation-'+case+'-G',detail)
    def send_after_restart(label):
        marker='HARMONY_REAL_CONTINUE_'+uuid.uuid4().hex[:8].upper();ui.message(marker);wait(lambda:reply(marker));assert users(marker)==1;wait(lambda:not ui.find('message-stop'));return marker
    for mode in ['network','host']:
        marker='HARMONY_SLOW_RECOVER_'+uuid.uuid4().hex[:8].upper();before=next(r['streamFrames'] for r in call('/status')['sessions'] if r['id']==session['id']);requests_before=count_requests('message')
        ui.message(marker);wait(lambda:next(r['streamFrames'] for r in call('/status')['sessions'] if r['id']==session['id'])>=before+3)
        draft='生成恢复保留草稿-'+uuid.uuid4().hex[:6];ui.input('message-draft',draft)
        if mode=='network':proxy('/disconnect',{})
        else:restart()
        wait(lambda:ui.find('message-send') and str(ui.find('message-send').get('enabled')).lower()=='true')
        assert ui.find('message-draft').get('text')==draft;assert users(marker)==1;assert count_requests('message')==requests_before+1
        if mode=='network':
            final=wait(lambda:reply(marker),timeout=70);assert final==''.join(marker+':'+str(i)+'\n' for i in range(200))
        else:send_after_restart(mode)
        ui.screenshot('real-generating-'+mode+'-recovered')
        done(mode+'-during-stream','真实生成中'+('断开连接后补齐200段精确终帧' if mode=='network' else '重启专属Host后能继续新一轮')+'；用户消息及原发送请求唯一，草稿保持且不自动重发',userMessages=1,originalClientRequests=1,draftPreserved=True)
    marker='HARMONY_REAL_ACK_RESTART_'+uuid.uuid4().hex[:8].upper();proxy('/hold',dict(kinds=['sent'],sessionId=session['id']));before=count_requests('message');ui.input('message-draft',marker);ui.click('message-send');wait(lambda:users(marker)==1);wait(lambda:proxy('/status')['held']>0)
    draft='确认中网关重启草稿-'+uuid.uuid4().hex[:6];ui.input('message-draft',draft);restart();proxy('/release',{})
    wait(lambda:ui.find('message-send') and str(ui.find('message-send').get('enabled')).lower()=='true');assert ui.find('message-draft').get('text')==draft;assert users(marker)==1 and count_requests('message')==before+1;send_after_restart('ack')
    done('host-during-confirmation','真实Host接受但移动端未收到sent时重启，旧消息不重发，确认前新草稿保留且后续发送可用',userMessages=1,originalClientRequests=1,draftPreserved=True)
    call('/permission',{'id':session['id'],'name':'read-only'});marker='HARMONY_APPROVAL_RESTART_'+uuid.uuid4().hex[:8].upper();target=owned/'workspace'/(marker+'.txt');ui.message(marker)
    item=wait(lambda:next((r for r in call('/pending') if r.get('callId')=='approval-'+marker),None));rpc=item['rpcId'];assert not target.exists();ui.navigate('interaction-inbox','approval-allow-'+rpc);restart()
    pending=wait(lambda:call('/options',{'id':session['id']}).get('sessionPermissions'));current=next((r for r in call('/pending') if r['rpcId']==rpc),None)
    assert not target.exists() and count_requests('approval-response',rpc)==0
    if current:
        ui.wait('approval-reject-'+rpc);ui.click('approval-reject-'+rpc);wait(lambda:not any(r['rpcId']==rpc for r in call('/pending')));resolved='replayed then explicitly rejected'
    else:resolved='Host canceled interrupted request on restart'
    wait(lambda:not ui.find('approval-allow-'+rpc));assert not target.exists();ui.screenshot('real-host-restart-pending-approval-settled');ui.click('page-back');ui.wait('message-draft');send_after_restart('approval')
    done('host-during-approval','待审批重启专属Host，与服务端实际待办同步且不自动决定，目标文件不存在，随后可继续聊天',rpcId=rpc,serverResolution=resolved,fileExists=False,automaticDecisions=0)
    ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-connection');ui.click('settings-connection',scroll=True);ui.text('已断开');wait(lambda:proxy('/status')['connections']==0);before=len([r for r in proxy('/audit') if r['direction']=='response' and r['kind']=='hello']);time.sleep(8)
    assert proxy('/status')['connections']==0;assert len([r for r in proxy('/audit') if r['direction']=='response' and r['kind']=='hello'])==before;ui.text('已断开');ui.screenshot('real-explicit-disconnect-no-retry');ui.click('settings-connection',scroll=True);ui.text('已连接',timeout=35)
    done('explicit-disconnect','真实设置主动断开8秒内没有重连或新hello，手动连接后恢复，保持原设置页面',automaticReconnects=0)
