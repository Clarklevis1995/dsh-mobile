# coding: utf-8
"""仅专属真实0.12 Host部署配置G；受控模型仍经真实Host配置服务。"""
import json
import time
import uuid


def run(ui,m,folder,passed,call,session,owned):
    before=call('/default-configuration');baseline=json.loads((owned/'configuration-initial.json').read_text());original=baseline['selection'];permission=baseline['permission']
    assert before['selection']['provider']=='harmony-controlled' and before['selection']['model'] in ['harmony-controlled','harmony-controlled-alt']
    assert any(model['id']=='harmony-controlled-alt' for group in before['models'] for model in group['models'])
    def snapshot():return call('/default-configuration')
    def model_page():
        ui.navigate('home-settings','settings-defaults');ui.click('settings-defaults');ui.wait('default-model-heading')
    def accept():ui.wait('confirmation-accept');ui.click('confirmation-accept')
    def open_session():
        # 会话由另一客户端创建；先通过产品刷新目录，再以全部工作区+侧栏搜索定位，不依赖最近12项或滚动位置。
        ui.navigate('workspace-picker','workspace-all');ui.click('workspace-all');ui.wait('home-settings');ui.click('session-refresh')
        ui.navigate('open-sidebar','sidebar-search');ui.input('sidebar-search',session['name']);ui.click('session-'+session['id'],scroll=True);ui.wait('message-draft')
    def choose_model(model):
        ui.click('control-model-harmony-controlled-'+model);accept();ui.wait('default-model-current-harmony-controlled-'+model)
    model_page()
    if before['selection']!=original:choose_model('harmony-controlled')
    assert snapshot()['selection']==original
    ui.navigate('page-back','settings-default-permission')
    if before['permission']!=permission:
        ui.click('settings-default-permission');ui.wait('default-permission-'+permission);ui.click('default-permission-'+permission);accept()
    assert snapshot()['permission']==permission
    ui.navigate('page-back','home-settings')
    session=call('/create',dict(label='P7-configuration-'+uuid.uuid4().hex[:6]));call('/subscribe',dict(id=session['id']));call('/message',dict(id=session['id'],text='HARMONY_REAL_SETUP'))
    end=time.monotonic()+30
    while not any((row.get('type') or (row.get('event') or {}).get('type'))=='assistant/message' for row in call('/history',dict(id=session['id']))['records']):
        assert time.monotonic()<end;time.sleep(.3)
    session_permission=call('/options',dict(id=session['id']))['sessionPermissions']['currentValue']
    assert call('/session-configuration',dict(id=session['id']))['current']==original
    open_session();ui.navigate('conversation-back','home-settings');model_page();ui.wait('default-model-current-harmony-controlled-harmony-controlled')
    ui.click('control-model-harmony-controlled-harmony-controlled-alt');ui.wait('confirmation-accept');ui.click('取消','text')
    assert snapshot()['selection']==original,'取消后真实部署默认模型发生变化'
    choose_model('harmony-controlled-alt');assert snapshot()['selection']==dict(provider='harmony-controlled',model='harmony-controlled-alt',reasoningEffort='low')
    ui.click('default-effort-harmony-controlled-harmony-controlled-alt-high');accept();assert snapshot()['selection']['reasoningEffort']=='high'
    ui.screenshot('real-default-model-confirmed')
    m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE)
    ui.wait('home-settings');ui.text('已连接',timeout=40);model_page();ui.wait('default-model-current-harmony-controlled-harmony-controlled-alt')
    ui.navigate('page-back','settings-default-permission');ui.click('settings-default-permission');ui.wait('default-permission-read-only');ui.click('default-permission-read-only');ui.wait('confirmation-accept');ui.click('取消','text')
    assert snapshot()['permission']==permission,'取消后真实部署默认权限发生变化'
    ui.click('settings-default-permission');ui.wait('default-permission-read-only');ui.click('default-permission-read-only');accept();assert snapshot()['permission']=='read-only'
    ui.navigate('page-back','home-settings');open_session();ui.navigate('conversation-controls','control-model-harmony-controlled-harmony-controlled')
    assert ui.wait('control-model-harmony-controlled-harmony-controlled')['text'].startswith('✓ '),'全局默认影响了运行中的会话模型'
    assert call('/options',dict(id=session['id']))['sessionPermissions']['currentValue']==session_permission,'全局默认影响了运行中的会话权限'
    ui.screenshot('real-default-existing-session-unchanged')
    ui.navigate('page-back','message-draft');ui.navigate('conversation-back','home-settings');model_page();choose_model('harmony-controlled')
    ui.navigate('page-back','settings-default-permission');ui.click('settings-default-permission');ui.wait('default-permission-'+permission);ui.click('default-permission-'+permission);accept()
    after=snapshot();assert after['selection']==original and after['permission']==permission
    # Host明确契约：会话模型选择也异步尝试更新新会话默认值，不回写补偿来伪造双向隔离。
    ui.navigate('page-back','home-settings');open_session();ui.navigate('conversation-controls','control-model-harmony-controlled-harmony-controlled')
    assert not any(r.get('id','').startswith('control-effort-') for r in ui.nodes()), '当前模型没有思考能力时不应显示等级按钮'
    ui.click('control-model-harmony-controlled-harmony-controlled-alt',scroll=True);ui.text('✓ Harmony controlled alternate')
    ui.click('control-effort-high',scroll=True);ui.text('✓ High')
    selected=call('/session-configuration',dict(id=session['id']))['current'];assert selected==dict(provider='harmony-controlled',model='harmony-controlled-alt',reasoningEffort='high')
    end=time.monotonic()+15
    while snapshot()['selection']!=selected:
        assert time.monotonic()<end,'Host没有按契约同步新会话默认模型';time.sleep(.3)
    m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('home-settings');ui.text('已连接',timeout=40)
    open_session();ui.navigate('conversation-controls','control-model-harmony-controlled-harmony-controlled-alt');ui.control('control-effort-high',scroll=True);ui.text('✓ High')
    ui.navigate('page-back','message-draft');marker='HARMONY_CONFIG_ROUTING_'+uuid.uuid4().hex[:8].upper();ui.message(marker)
    audit=owned/'configuration-routing-audit.jsonl';end=time.monotonic()+20
    while True:
        rows=[json.loads(line) for line in audit.read_text().splitlines()] if audit.exists() else []
        matched=[row for row in rows if row.get('marker')==marker]
        if matched:break
        assert time.monotonic()<end,'真实LLM路由没有收到配置标记';time.sleep(.3)
    assert len(matched)==1 and matched[0]['provider']=='harmony-controlled' and matched[0]['model']=='harmony-controlled-alt' and matched[0]['reasoningEffort']=='high'
    end=time.monotonic()+30
    while True:
        records=call('/history',dict(id=session['id']))['records']
        def text(row):
            event=row.get('event') or row.get('data') or {}
            return event.get('text') or ''.join(block.get('text','') for block in event.get('message',{}).get('content',event.get('content',[])) if block.get('type')=='text')
        replies=[row for row in records if (row.get('type') or (row.get('event') or {}).get('type'))=='assistant/message' and text(row)==marker]
        if replies:break
        assert time.monotonic()<end,'真实Host未完成配置验证回复';time.sleep(.3)
    assert len(replies)==1
    assert sum((row.get('type') or (row.get('event') or {}).get('type'))=='user/message' and text(row)==marker for row in records)==1
    ui.text(marker,timeout=30)
    while ui.find('message-stop'):
        assert time.monotonic()<end,'真实回复没有完成';time.sleep(.3)
    (folder/'real-configuration-routing.json').write_text(json.dumps(matched,indent=2))
    ui.navigate('conversation-controls','control-model-harmony-controlled-harmony-controlled-alt');ui.screenshot('real-session-reasoning-confirmed')
    ui.click('control-model-harmony-controlled-harmony-controlled',scroll=True);ui.text('✓ Harmony controlled upstream')
    assert call('/session-configuration',dict(id=session['id']))['current']==original
    end=time.monotonic()+15
    while snapshot()['selection']!=original:
        assert time.monotonic()<end,'恢复会话选择后默认模型没有同步恢复';time.sleep(.3)
    assert snapshot()['permission']==permission
    (folder/'real-default-results.json').write_text(json.dumps(dict(gatewayVersion='0.12.0',hostRuntime='DSH 0.2.2-rc.1',scope='仅本轮临时DSH_HOME/18897，真实配置服务/网关/Native/UI；受控本机模型不调用外部LLM',modelBefore=original,modelConfirmed=dict(provider='harmony-controlled',model='harmony-controlled-alt',reasoningEffort='high'),permissionBefore=permission,permissionConfirmed='read-only',cancelUnchanged=True,coldStartRetained=True,existingSessionModelUnchanged=True,existingSessionPermissionUnchanged=True,globalReasoningConfirmed=True,sessionModelSwitchConfirmed=True,sessionReasoningConfirmed=True,sessionColdStartRetained=True,actualModelRoutingVerified=True,hostSessionChoiceAlsoUpdatesDefault=True,setupSelectionBefore=before['selection'],unsupportedReasoningHidden=True,restored=True),ensure_ascii=False,indent=2))
    passed('P4-default-model-permission-G','真实Host部署/会话模型与思考等级分别持久、默认不改已有会话、会话选择按Host契约同步新默认、冷启读取；权限确认与取消不变；实际上游路由收到high且仅一次；结束恢复原部署和会话配置')
