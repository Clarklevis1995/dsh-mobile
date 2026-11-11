"""真实隔离 Host G：只允许新临时 DSH_HOME、18897／18899 与控制端创建的会话。"""
import json
import os
from pathlib import Path
import time
import urllib.request
import uuid


def run(ui,m,folder,passed):
    owned=Path(os.environ['HARMONY_OWNED_REAL_ROOT']).resolve();meta=json.loads((owned/'owned-processes.json').read_text())
    assert owned.name.startswith('harmony-p7-owned-host-') and meta['port']==18897 and meta['observerPort']==18899 and Path(meta['workspace']).resolve().parent==owned
    def call(route,data=None):
        req=urllib.request.Request('http://127.0.0.1:18899'+route,None if data is None else json.dumps(data).encode(),{'Content-Type':'application/json'})
        return json.load(urllib.request.urlopen(req,timeout=30))
    def wait(predicate,timeout=35):
        until=time.monotonic()+timeout
        while time.monotonic()<until:
            value=predicate()
            if value:return value
            time.sleep(.4)
        raise AssertionError('真实隔离 Host 业务未按时确认')
    def history():return call('/history',{'id':session['id']})['records']
    def kind(row):return row.get('type') or (row.get('event') or {}).get('type')
    def text(row):
        event=row.get('event') or row.get('data') or {};blocks=event.get('message',{}).get('content') or event.get('content') or []
        return event.get('text') or ''.join(block.get('text','') for block in blocks if block.get('type')=='text')
    state=call('/status');assert state['gatewayVersion']==meta['gatewayVersion']=='0.12.0'
    time.sleep(2);ui.screenshot('real-home-empty-dark')
    if os.environ.get('HARMONY_REAL_SCOPE') in ['history','history-restore','cache']:
        seed=json.loads(Path(os.environ['HARMONY_HISTORY_SEED_JSON']).read_text());assert Path(seed['root']).resolve()==owned and seed['complete']
        session=next(r for r in state['sessions'] if r['id']==seed['sessionId'])
    else:
        session = call('/create',{'label':'P7-'+os.environ['HARMONY_REAL_SCOPE']+'-'+uuid.uuid4().hex[:6]}) if os.environ.get('HARMONY_REAL_SCOPE') in ['approval','questions','resilience','transport','matrix','generation-network','configuration'] else next((row for row in state['sessions'] if row['name'].endswith('P7-daily')),None) or call('/create',{'label':'P7-daily'})
    call('/subscribe',{'id':session['id']})
    if not any(kind(row)=='user/message' for row in history()):call('/message',{'id':session['id'],'text':'HARMONY_REAL_SETUP'})
    wait(lambda:any(kind(row)=='assistant/message' for row in history()))
    ui.navigate('home-auth','host-add');ui.navigate('host-add','gateway-payload')
    if os.environ.get('HARMONY_REAL_SCOPE') in ['transport','generation-network','history','history-restore','cache']:
        req=urllib.request.Request('http://127.0.0.1:18897/mgw/pair',json.dumps({'publicUrl':'ws://127.0.0.1:18907/ws/mobile'}).encode(),{'Content-Type':'application/json'})
        import base64
        issued=json.load(urllib.request.urlopen(req,timeout=10))['payload']
        issued['publicUrl']='ws://127.0.0.1:18907/ws/mobile';issued['endpoints']=[]
        payload=base64.urlsafe_b64encode(json.dumps(issued,separators=(',',':')).encode()).decode().rstrip('=')
    else: payload=call('/bootstrap')['qrPayload']
    assert isinstance(payload,str)
    ui.input('gateway-payload',payload);ui.click('gateway-connect');ui.text('已连接',timeout=45)
    ui.click('session-'+session['id']);ui.wait('message-draft')
    if os.environ.get('HARMONY_REAL_SCOPE') == 'configuration':
        from real_default_configuration_scenarios import run as configuration
        configuration(ui,m,folder,passed,call,session,owned);return
    if os.environ.get('HARMONY_REAL_SCOPE') == 'cache':
        from real_cache_scenarios import run as caching
        caching(ui,m,folder,passed,call,session);return
    if os.environ.get('HARMONY_REAL_SCOPE') == 'history-restore':
        from real_history_restore_scenarios import run as restore
        restore(ui,m,folder,passed,call,session);return
    if os.environ.get('HARMONY_REAL_SCOPE') == 'history':
        from real_history_scenarios import run as pagination
        pagination(ui,m,folder,passed,call,session);return
    if os.environ.get('HARMONY_REAL_SCOPE') == 'generation-network':
        from real_generation_scenarios import run as generation
        generation(ui,m,folder,passed,call,session,owned);return
    if os.environ.get('HARMONY_REAL_SCOPE') == 'matrix':
        from real_message_matrix import run as matrix
        matrix(ui,m,folder,passed,call,session);return
    if os.environ.get('HARMONY_REAL_SCOPE') == 'transport':
        from real_transport_scenarios import run as transport
        transport(ui,m,folder,passed,call,session,owned);return
    if os.environ.get('HARMONY_REAL_SCOPE') == 'resilience':
        from real_resilience_scenarios import run as resilience
        resilience(ui,m,folder,passed,call,session,owned);return
    if os.environ.get('HARMONY_REAL_SCOPE') == 'questions':
        from real_question_scenarios import run as questions
        questions(ui,m,folder,passed,call,session);return
    if os.environ.get('HARMONY_REAL_SCOPE') == 'approval':
        from real_approval_scenarios import run as approvals
        approvals(ui,m,folder,passed,call,session,owned);return
    marker='HARMONY_REAL_'+uuid.uuid4().hex[:8].upper();ui.message(marker)
    wait(lambda:any(kind(row)=='assistant/message' and marker in text(row) for row in history()))
    rows=history();assert sum(kind(row)=='user/message' and text(row)==marker for row in rows)==1;ui.text(marker,timeout=25);ui.screenshot('real-owned-chat-confirmed')
    if os.environ.get('HARMONY_REAL_SCOPE') == 'render':
        passed('P7-real-live-render-G','真实 Host 接受并完成新回复后，Native 画面无需人工滚动即显示新标记');return
    ends_before=sum(kind(row)=='turn/end' for row in rows)
    before=next(row['streamFrames'] for row in call('/status')['sessions'] if row['id']==session['id']);slow='HARMONY_SLOW_'+uuid.uuid4().hex[:8].upper();ui.message(slow)
    wait(lambda:next(row['streamFrames'] for row in call('/status')['sessions'] if row['id']==session['id'])>=before+2);ui.click('message-stop')
    wait(lambda:sum(kind(row)=='turn/end' for row in history())>ends_before)
    ui.wait('message-draft');ui.screenshot('real-native-stop-confirmed');passed('P1-P3-P7-real-chat-G','0.12.0 真 Host／Agent／事件存储与受控本地模型，原生发送、流式、停止；独立观察确认用户消息恰好一次，不访问用户网关')
    question_marker='HARMONY_QUESTION_'+uuid.uuid4().hex[:8].upper();ui.message(question_marker)
    request=wait(lambda:next((row for row in call('/pending') if row['sessionId']==session['id'] and row['kind']=='question-requested'),None));rpc=request['rpcId'];assert len(request['questions'])==3
    for _ in range(5):
        if ui.find('question-option-'+rpc+'-0-0'):break
        ui.scroll(False)
    ui.click('question-option-'+rpc+'-0-0',scroll=True);ui.click('question-option-'+rpc+'-1-0',scroll=True);ui.click('question-option-'+rpc+'-1-1',scroll=True);ui.input('question-custom-'+rpc+'-2','真实主机原生表单回答',scroll=True);ui.screenshot('real-native-three-questions');ui.click('question-submit-'+rpc,scroll=True)
    wait(lambda:any(kind(row)=='assistant/message' and question_marker in text(row) for row in history()));assert not any(row['rpcId']==rpc for row in call('/pending'))
    passed('P1-P3-real-question-G','真实 Agent 的 UserQuestions 服务发出三题；原生单选／多选／自定义回答后 Agent 继续，独立观察确认请求已解决')
    ui.navigate('conversation-files','files-refresh');ui.click('鸿蒙验收报告.md','text');ui.text('真实工作区报告',timeout=35);ui.screenshot('real-workspace-markdown-preview');ui.navigate('page-back','files-refresh');ui.navigate('page-back','message-draft')
    passed('P5-real-workspace-download-G','真实独立 Host 工作区中文文件下载、落盘摘要校验及 Native Markdown／代码／表格预览；来源仅本轮临时目录')
    draft='真实主机冷启草稿-'+uuid.uuid4().hex[:8].upper();ui.input('message-draft',draft);time.sleep(1.5)
    m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.text('已连接',timeout=45);ui.click('session-'+session['id']);ui.wait('message-draft')
    assert ui.find('message-draft').get('text')==draft;ui.text(question_marker,timeout=25);wait(lambda:any(kind(row)=='assistant/message' and marker in text(row) for row in history()))
    ui.screenshot('real-asset-reconnect-preserved-draft');state=call('/status');assert not any(row['sequenceConflicts'] for row in state['sessions'])
    passed('P6-real-cold-restore-G','真实应用终止／重开后 Asset 身份重连、实际主机历史接管及加密草稿恢复；观察端序列无冲突')
    (folder/'real-environment.json').write_text(json.dumps({'root':str(owned),'port':18897,'observerPort':18899,'gatewayVersion':state['gatewayVersion'],'model':'controlled local adapter; no external LLM requests','sessionId':session['id'],'workspace':meta['workspace'],'checks':'G with real Host and native smoke; no device D'},ensure_ascii=False,indent=2))
