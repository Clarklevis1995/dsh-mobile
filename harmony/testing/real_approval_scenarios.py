"""真实 Host / Agent / write / sandbox / ApprovalService 的限定工作区审批。"""
import json,time,uuid,subprocess

def run(ui,m,folder,passed,call,session,owned):
    def wait(predicate,timeout=40):
        until=time.monotonic()+timeout
        while time.monotonic()<until:
            value=predicate()
            if value:return value
            time.sleep(.3)
        raise AssertionError('真实工具审批未按时完成')
    def event_type(row):return row.get('type') or (row.get('event') or {}).get('type')
    def data(row):return row.get('data') or row.get('event') or {}
    def audit():
        logs=list((owned/'sessions').glob('*/'+session['id']+'/session.v4.jsonl.zstd'));assert len(logs)==1
        result=subprocess.run(['/Users/lichaofan/.nvm/versions/node/v24.20.0/bin/node',str(m.ROOT/'harmony/testing/read_owned_approval_audit.cjs'),str(owned),str(logs[0])],capture_output=True,text=True)
        return json.loads(result.stdout) if result.returncode==0 else []
    checks=[]
    call('/permission',{'id':session['id'],'name':'read-only'})
    actual=call('/options',{'id':session['id']});assert actual['sessionPermissions']['currentValue']=='read-only'
    for mode in ['ALLOW','REJECT','EXTERNAL','REPLAY']:
        marker='HARMONY_APPROVAL_'+mode+'_'+uuid.uuid4().hex[:8].upper();target=owned/'workspace'/(marker+'.txt')
        assert not target.exists()
        ui.message(marker)
        pending=wait(lambda:next((r for r in call('/pending') if r['sessionId']==session['id'] and r['kind']=='approval-requested' and r.get('callId')=='approval-'+marker),None))
        assert pending['toolName']=='write';rpc=pending['rpcId'];assert not target.exists(),'未审批时已经写入'
        ui.navigate('interaction-inbox','approval-collapse-'+rpc)
        ui.wait('approval-allow-'+rpc)
        if mode=='ALLOW':
            ui.click('approval-collapse-'+rpc);assert not ui.find('approval-allow-'+rpc)
            ui.click('approval-collapse-'+rpc);ui.wait('approval-allow-'+rpc)
            ui.click('approval-details-'+rpc,scroll=True);ui.text(marker+'.txt');ui.screenshot('real-approval-arguments-before-allow')
        if mode=='REPLAY':
            m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE)
            ui.wait('home-settings',timeout=40);ui.text('已连接',timeout=40);ui.navigate('interaction-inbox','approval-collapse-'+rpc);ui.text('已恢复')
            assert not target.exists();ui.screenshot('real-approval-replayed-same-rpc')
        outcome='allowed-once' if mode=='ALLOW' else 'rejected'
        if mode=='EXTERNAL':call('/approval',{'rpcId':rpc,'outcome':'rejected'})
        else:ui.click(('approval-allow-' if mode=='ALLOW' else 'approval-reject-')+rpc,scroll=True)
        wait(lambda:not any(r.get('rpcId')==rpc for r in call('/pending')))
        def audited():
            rows=audit();asked=[r for r in rows if event_type(r)=='approval/asked' and data(r).get('callId')==pending['callId']]
            if len(asked)!=1:return None
            identity=data(asked[0])['id']
            return (rows,identity) if any(event_type(r)=='approval/decided' and data(r).get('id')==identity for r in rows) else None
        rows,service_id=wait(audited)
        decisions=[data(r) for r in rows if event_type(r)=='approval/decided' and data(r).get('id')==service_id]
        assert len(decisions)==1 and decisions[0]['outcome']==outcome,decisions
        (folder/('approval-audit-'+mode.lower()+'.json')).write_text(json.dumps([r for r in rows if data(r).get('id')==service_id],ensure_ascii=False,indent=2))
        if mode=='ALLOW':
            wait(target.exists);assert target.read_text()==marker+'\n'
        else:assert not target.exists(),'拒绝后仍写入了文件'
        deadline=time.monotonic()+15
        while ui.find('approval-allow-'+rpc):
            assert time.monotonic()<deadline,'已处理审批仍可提交';time.sleep(.3)
        ui.screenshot('real-approval-'+mode.lower()+'-settled')
        ui.click('page-back')
        if mode=='REPLAY':ui.wait('home-settings');ui.click('session-'+session['id'])
        ui.wait('message-draft')
        # 等到工具结果后 Agent 收束，下一项审批不会与仍在运行的前一轮竞争。
        wait(lambda:not ui.find('message-stop'),timeout=40)
        options=call('/options',{'id':session['id']});assert options['sessionPermissions']['currentValue']=='read-only','单次授权修改了持久权限'
        checks.append(dict(case=mode,rpcId=rpc,approvalId=pending['approvalId'],serviceAuditId=service_id,callId=pending['callId'],outcome=outcome,fileExists=target.exists(),standingPermission='read-only'))
        (folder/'real-approval-results.json').write_text(json.dumps(dict(gatewayVersion='0.12.0',tool='Host built-in write with workspace-write escalation',checks=checks,scope='owned temp workspace only; controlled model, real Agent and approval audit'),ensure_ascii=False,indent=2))
        passed('P1-real-approval-'+mode+'-G','真实 write工具的'+mode+'：审批前不执行，明确决定后核对实际文件与唯一服务端审计；单次授权不改变只读权限')
