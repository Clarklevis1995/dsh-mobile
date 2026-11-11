# coding: utf-8
"""部署默认配置的原生确认/取消/失败回滚与独立目的页。"""
import json
import shutil
import time


def run(ui,m,folder,passed):
    m.connect(ui)
    ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-defaults');ui.click('settings-defaults')
    ui.wait('default-model-current-fixture-read');ui.wait('default-model-heading')
    assert not ui.find('control-permission-workspace-write') and not ui.find('control-preset-read'), '默认模型页面仍混入权限或Agent设置'
    log=folder/'gateway-18783.log'
    def count(kind):return log.read_text().count('request '+kind+' ')
    def save(model,effort=None,cancel=False):
        previous=count('save-default-model')
        ui.click('default-effort-fixture-'+model+'-'+effort if effort else 'control-model-fixture-'+model)
        ui.wait('confirmation-accept');assert count('save-default-model')==previous,'确认前不得提交部署设置'
        ui.click('取消','text') if cancel else ui.click('confirmation-accept')
        if cancel:assert count('save-default-model')==previous,'取消修改不得发出保存请求'
    save('reason',cancel=True);ui.wait('default-model-current-fixture-read')
    save('reason');ui.wait('default-model-current-fixture-reason')
    save('reason','high');ui.wait('default-model-current-fixture-reason')
    ui.navigate('page-back','settings-defaults');ui.click('settings-defaults');ui.wait('default-model-current-fixture-reason')
    # 服务端拒绝保留已确认模型，不能用本地选中代替确认。
    save('fail');ui.wait('control-error');ui.text('测试默认模型暂不可用');ui.wait('default-model-current-fixture-reason')
    ui.navigate('page-back','settings-default-permission')
    previous=count('set-default');ui.click('settings-default-permission');ui.wait('default-permission-workspace-write')
    ui.click('default-permission-workspace-write');ui.wait('confirmation-accept');assert count('set-default')==previous
    ui.click('取消','text');assert count('set-default')==previous;ui.text('只读')
    ui.click('settings-default-permission');ui.wait('default-permission-workspace-write');ui.click('default-permission-workspace-write');ui.wait('confirmation-accept');ui.click('confirmation-accept');ui.text('工作区写入')
    ui.navigate('page-back','home-settings');ui.navigate('home-settings','settings-default-permission');ui.text('工作区写入')
    ui.click('settings-default-permission');ui.wait('default-permission-workspace-write');assert str(ui.find('default-permission-workspace-write').get('selected')).lower()=='true'
    m.hdc('shell','uitest','uiInput','keyEvent','Back')
    # 恢复夹具默认值，保证两端静态V仍是同一初始数据。
    ui.click('settings-default-permission');ui.wait('default-permission-read-only');ui.click('default-permission-read-only');ui.wait('confirmation-accept');ui.click('confirmation-accept')
    ui.click('settings-defaults');save('read');ui.wait('default-model-current-fixture-read')
    capture(ui,m,folder)
    (folder/'default-model-results.json').write_text(json.dumps(dict(scope='原生UI和真实Native共享Store；隔离协议夹具F，V单独对照',modelCancelNoRequest=True,permissionCancelNoRequest=True,modelFailureRollback=True,confirmedDefaultsPersist=True,permissionNativeMenu=True,restoredFixtureDefaults=True,themes=['light','dark']),ensure_ascii=False,indent=2))
    passed('P4-default-model-native-F','默认模型独立压栈、模型/权限确认前与取消零写入、确认后重开保持、错误回滚、原生权限菜单选中与浅深截图通过')


def capture(ui,m,folder):
    for theme in ['light','dark']:
        ui.navigate('page-back','settings-style');ui.navigate('settings-style','appearance-system');ui.choose('appearance-'+theme)
        ui.navigate('page-back','home-settings');ui.navigate('home-settings','settings-defaults')
        ui.click('settings-defaults',scroll=True);ui.wait('default-model-current-fixture-read');ui.nodes();shutil.copy2(folder/'layout.json',folder/('default-model-'+theme+'-layout.json'));ui.screenshot('default-model-'+theme)
