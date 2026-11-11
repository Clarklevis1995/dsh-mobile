# coding: utf-8
"""本轮专属真实Host的部署Agent、既有/新建会话与首消息锁定G。"""
import json
import time
import uuid


def run(ui,m,folder,passed,call,owned):
    initial=call('/agent-configuration');baseline=json.loads((owned/'configuration-initial.json').read_text())['agent']
    assert baseline=='standard' and any(p['id']=='minimal' for p in initial['presets'])
    def page():ui.navigate('home-settings','settings-agent-presets');ui.navigate('settings-agent-presets','agent-presets-heading')
    def choose(preset,cancel=False):
        ui.click('agent-preset-card-'+preset,scroll=True);ui.wait('confirmation-accept');ui.click('取消','text') if cancel else ui.click('confirmation-accept')
        if not cancel:ui.wait('agent-preset-current-'+preset)
    def wait(fn,timeout=30):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=fn()
            if value:return value
            time.sleep(.3)
        raise AssertionError('真实Agent预设确认未完成')
    def assistant(session,marker):
        for row in call('/history',dict(id=session['id']))['records']:
            event=row.get('event') or row.get('data') or {};kind=row.get('type') or event.get('type')
            text=event.get('text') or ''.join(b.get('text','') for b in event.get('message',{}).get('content',event.get('content',[])) if b.get('type')=='text')
            if kind=='assistant/message' and text==marker:return True
    def open_session(session):
        ui.navigate('workspace-picker','workspace-all');ui.click('workspace-all');ui.wait('home-settings');ui.click('session-refresh')
        ui.navigate('open-sidebar','sidebar-search');ui.input('sidebar-search',session['name']);ui.click('session-'+session['id'],scroll=True);ui.wait('message-draft')
    page()
    if initial['agent']!=baseline:choose(baseline)
    assert call('/agent-configuration')['agent']==baseline
    ui.navigate('page-back','settings-agent-presets');ui.navigate('page-back','home-settings')
    existing=call('/create',dict(label='P7-agent-existing-'+uuid.uuid4().hex[:6]));call('/subscribe',dict(id=existing['id']));call('/message',dict(id=existing['id'],text='HARMONY_REAL_SETUP'));wait(lambda:assistant(existing,'HARMONY_REAL_SETUP'))
    old=call('/session-agent',dict(id=existing['id']));assert old['agentPreset']==baseline and old['locked']
    page();choose('minimal',cancel=True);assert call('/agent-configuration')['agent']==baseline
    choose('minimal');assert call('/agent-configuration')['agent']=='minimal';ui.screenshot('real-agent-default-minimal-confirmed')
    assert call('/session-agent',dict(id=existing['id']))['agentPreset']==baseline
    m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('home-settings');ui.text('已连接',timeout=40);page();ui.wait('agent-preset-current-minimal')
    inherited=call('/create',dict(label='P7-agent-new-'+uuid.uuid4().hex[:6]));call('/subscribe',dict(id=inherited['id']))
    new=call('/session-agent',dict(id=inherited['id']));assert new['agentPreset']=='minimal' and not new['locked']
    ui.navigate('page-back','settings-agent-presets');ui.navigate('page-back','home-settings');open_session(inherited)
    ui.navigate('conversation-controls','session-agent-current');ui.click('session-agent-standard');wait(lambda:call('/session-agent',dict(id=inherited['id']))['agentPreset']==baseline)
    ui.navigate('page-back','message-draft');marker='HARMONY_AGENT_PRESET_'+uuid.uuid4().hex[:8].upper();ui.message(marker);wait(lambda:assistant(inherited,marker))
    ui.navigate('conversation-controls','session-agent-current');ui.text('已锁定');assert str(ui.wait('session-agent-minimal').get('enabled')).lower()=='false';assert call('/session-agent',dict(id=inherited['id']))['locked'];ui.screenshot('real-agent-session-selected-then-locked')
    ui.navigate('page-back','message-draft');ui.navigate('conversation-back','home-settings');page()
    choose(baseline);assert call('/agent-configuration')['agent']==baseline
    assert call('/session-agent',dict(id=existing['id']))['agentPreset']==baseline
    (folder/'real-agent-presets-results.json').write_text(json.dumps(dict(gatewayVersion='0.12.0',hostRuntime='DSH 0.2.2-rc.1',defaultCancelUnchanged=True,defaultConfirmedPersisted=True,coldStartRead=True,existingSessionUnchanged=True,newSessionInherited=True,blankSessionSelectionConfirmed=True,firstMessageLocked=True,lockedDisabled=True,restored=True,scope='仅本轮临时Host/受控本机上游，真实Host Agent预设服务/协议/Native/ArkUI；不代表外部模型与真机'),ensure_ascii=False,indent=2))
    passed('P4-agent-presets-G','真实默认预设确认/取消与冷启；旧会话不变、新会话继承；首条消息前选择确认、发送后真实锁定且按钮禁用；结束恢复部署原值')
