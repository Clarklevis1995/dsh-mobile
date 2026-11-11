# coding: utf-8
"""仅专属真实0.12 Host部署配置G；受控模型仍经真实Host配置服务。"""
import json
import time


def run(ui,m,folder,passed,call,session,owned):
    before=call('/default-configuration');original=before['selection'];permission=before['permission']
    assert original==dict(provider='harmony-controlled',model='harmony-controlled')
    assert any(model['id']=='harmony-controlled-alt' for group in before['models'] for model in group['models'])
    session_permission=call('/options',dict(id=session['id']))['sessionPermissions']['currentValue']
    def snapshot():return call('/default-configuration')
    def model_page():
        ui.navigate('home-settings','settings-defaults');ui.click('settings-defaults');ui.wait('default-model-heading')
    def accept():ui.wait('confirmation-accept');ui.click('confirmation-accept')
    def choose_model(model):
        ui.click('control-model-harmony-controlled-'+model);accept();ui.wait('default-model-current-harmony-controlled-'+model)
    ui.navigate('conversation-back','home-settings');model_page();ui.wait('default-model-current-harmony-controlled-harmony-controlled')
    ui.click('control-model-harmony-controlled-harmony-controlled-alt');ui.wait('confirmation-accept');ui.click('取消','text')
    assert snapshot()['selection']==original,'取消后真实部署默认模型发生变化'
    choose_model('harmony-controlled-alt');assert snapshot()['selection']==dict(provider='harmony-controlled',model='harmony-controlled-alt')
    ui.screenshot('real-default-model-confirmed')
    m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE)
    ui.wait('home-settings');ui.text('已连接',timeout=40);model_page();ui.wait('default-model-current-harmony-controlled-harmony-controlled-alt')
    ui.navigate('page-back','settings-default-permission');ui.click('settings-default-permission');ui.wait('default-permission-read-only');ui.click('default-permission-read-only');ui.wait('confirmation-accept');ui.click('取消','text')
    assert snapshot()['permission']==permission,'取消后真实部署默认权限发生变化'
    ui.click('settings-default-permission');ui.wait('default-permission-read-only');ui.click('default-permission-read-only');accept();assert snapshot()['permission']=='read-only'
    ui.navigate('page-back','home-settings');ui.click('session-'+session['id'],scroll=True);ui.wait('message-draft');ui.navigate('conversation-controls','control-model-harmony-controlled-harmony-controlled')
    assert ui.wait('control-model-harmony-controlled-harmony-controlled')['text'].startswith('✓ '),'全局默认影响了运行中的会话模型'
    assert call('/options',dict(id=session['id']))['sessionPermissions']['currentValue']==session_permission,'全局默认影响了运行中的会话权限'
    ui.screenshot('real-default-existing-session-unchanged')
    ui.navigate('page-back','message-draft');ui.navigate('conversation-back','home-settings');model_page();choose_model('harmony-controlled')
    ui.navigate('page-back','settings-default-permission');ui.click('settings-default-permission');ui.wait('default-permission-'+permission);ui.click('default-permission-'+permission);accept()
    after=snapshot();assert after['selection']==original and after['permission']==permission
    (folder/'real-default-results.json').write_text(json.dumps(dict(gatewayVersion='0.12.0',hostRuntime='DSH 0.2.2-rc.1',scope='仅本轮临时DSH_HOME/18897，真实配置服务/网关/Native/UI；受控本机模型不调用外部LLM',modelBefore=original,modelConfirmed=dict(provider='harmony-controlled',model='harmony-controlled-alt'),permissionBefore=permission,permissionConfirmed='read-only',cancelUnchanged=True,coldStartRetained=True,existingSessionModelUnchanged=True,existingSessionPermissionUnchanged=True,restored=True),ensure_ascii=False,indent=2))
    passed('P4-default-model-permission-G','真实Host模型/权限取消不变、确认持久回执、冷启读取、运行中会话隔离通过；结束恢复原部署配置')
