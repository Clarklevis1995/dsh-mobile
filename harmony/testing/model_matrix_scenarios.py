# coding: utf-8
"""实际夹具空目录/不支持与TCP断开；不替换生产设备或网关适配器。"""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time


def run(ui,m,folder,passed,control,servers):
    m.connect(ui);ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-defaults');ui.click('settings-defaults');ui.wait('default-model-current-fixture-read')
    mode=folder/'model-mode'
    def reopen():ui.navigate('page-back','settings-defaults');ui.click('settings-defaults');ui.wait('default-model-heading')
    mode.write_text('empty');reopen();ui.text('暂无可用模型');assert not ui.find('control-model-fixture-read');ui.screenshot('models-empty')
    mode.write_text('normal');ui.click('control-refresh');ui.wait('default-model-current-fixture-read')
    mode.write_text('unsupported');reopen();ui.wait('control-error');ui.text('测试主机不支持模型列表');ui.wait('control-refresh');ui.screenshot('models-unsupported')
    mode.write_text('normal');ui.click('control-refresh');ui.wait('default-model-current-fixture-read');assert not ui.find('control-error')
    # 只停止由本次product_ui创建并传入的子进程，不按全局端口杀进程。
    process=control['process'];assert process.args==[sys.executable,str(m.ROOT/'harmony/testing/gateway_fixture.py')] and control['env']['HARMONY_FIXTURE_PORT']=='18783'
    process.terminate();process.wait(timeout=6);ui.text('已断开')
    rows=ui.nodes();buttons=[r for r in rows if r.get('id','').startswith('control-model-')]
    if buttons:assert all(str(r.get('enabled')).lower()=='false' for r in buttons)
    else:
        ui.text('暂无可用模型');assert str(ui.wait('control-refresh').get('enabled')).lower()=='false'
    ui.screenshot('models-disconnected')
    # 服务端在离线期间默认值变化，必须重新查询，不能把旧值重新标为已确认。
    next_env=dict(control['env'],HARMONY_FIXTURE_DEFAULT_MODEL='reason')
    replacement=subprocess.Popen(process.args,env=next_env,stdout=control['log'],stderr=control['log']);servers.append(replacement)
    end=time.monotonic()+40
    while True:
        assert replacement.poll() is None,'重启夹具未成功'
        row=ui.find('control-model-fixture-read')
        if row and str(row.get('enabled')).lower()=='true':break
        if ui.find('control-refresh') and str(ui.find('control-refresh').get('enabled')).lower()=='true':ui.click('control-refresh')
        assert time.monotonic()<end,'连接恢复后模型操作未恢复';time.sleep(.5)
    ui.wait('default-model-current-fixture-reason');ui.screenshot('models-reconnected')
    assert (folder/'gateway-18783.log').read_text().count('request save-default-model ')==0, '读取/重连不得自动写入默认配置'
    ui.navigate('page-back','settings-defaults');ui.text('推理模型')
    m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(.5)
    replacement.terminate();replacement.wait(timeout=6)
    root_server=subprocess.Popen(process.args,env=control['env'],stdout=control['log'],stderr=control['log']);servers.append(root_server)
    m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('settings-defaults')
    ui.text('已连接',timeout=40);ui.text('读取模型');ui.screenshot('settings-default-refreshed-after-reconnect')
    ui.navigate('page-back','home-settings');ui.click('session-harmony-smoke',scroll=True);ui.wait('message-draft')
    session_cases(ui,m,folder,passed,control,servers,root_server)
    (folder/'model-matrix-results.json').write_text(json.dumps(dict(emptyCatalog=True,emptyRetry=True,unsupportedError=True,errorRetry=True,actualTcpDisconnect=True,offlineModelDisabled=True,reconnected=True,changedServerDefaultRefreshed=True,settingsRootRefreshed=True,actualBackgroundReturn=True,noAutomaticWrite=True,scope='真实生产页面/Native；空/不支持来自显式夹具数据，网络断开为本轮拥有的服务子进程退出，F不替代G'),ensure_ascii=False,indent=2))
    passed('P4-model-capability-network-F','空目录/不支持错误及重试；真实TCP断开当前默认页禁用模型操作、恢复服务后重连恢复')


def run_session(ui,m,folder,passed,control,servers):
    m.connect(ui)
    session_cases(ui,m,folder,passed,control,servers,control['process'])


def session_cases(ui,m,folder,passed,control,servers,root_server):
    mode=folder/'model-mode'
    process=control['process']
    mode.write_text('empty');ui.navigate('conversation-controls','control-permission-read-only');ui.text('主机未提供可选模型');ui.screenshot('session-models-empty')
    mode.write_text('normal');ui.click('control-refresh',scroll=True)
    for _ in range(5):
        if ui.find('control-model-fixture-read'):break
        ui.scroll(False)
    ui.wait('control-model-fixture-read')
    mode.write_text('unsupported');ui.navigate('page-back','message-draft');ui.navigate('conversation-controls','control-error');ui.text('测试主机不支持模型列表');ui.screenshot('session-models-unsupported')
    mode.write_text('normal');ui.click('control-refresh',scroll=True)
    for _ in range(5):
        if ui.find('control-model-fixture-read'):break
        ui.scroll(False)
    ui.wait('control-model-fixture-read');assert not ui.find('control-error')
    until=time.monotonic()+15
    while str(ui.wait('control-model-fixture-read').get('enabled')).lower()!='true':
        assert time.monotonic()<until;time.sleep(.3)
    root_server.terminate();root_server.wait(timeout=6)
    for _ in range(4):
        if ui.find('已断开','text'):break
        ui.scroll(False)
    ui.text('已断开',timeout=40)
    known=[r for r in ui.nodes() if r.get('id','').startswith('control-model-')]
    assert all(str(r.get('enabled')).lower()=='false' for r in known)
    session_server=subprocess.Popen(process.args,env=dict(control['env'],HARMONY_FIXTURE_CURRENT_MODEL='reason'),stdout=control['log'],stderr=control['log']);servers.append(session_server)
    end=time.monotonic()+40
    while True:
        row=ui.find('control-model-fixture-reason')
        if row and str(row.get('enabled')).lower()=='true' and row.get('text','').startswith('✓ '):break
        assert time.monotonic()<end,'会话控制重连没有重新读取服务端模型';time.sleep(.5)
    assert (folder/'gateway-18783.log').read_text().count('request select-model ')==0, '恢复时不得自动切模型'
    ui.screenshot('session-model-refreshed-after-reconnect')
    ui.click('control-model-fixture-read');ui.text('✓ 读取模型')
    (folder/'session-model-matrix-results.json').write_text(json.dumps(dict(emptyCatalog=True,unsupportedError=True,errorRetry=True,actualTcpDisconnect=True,offlineDisabled=True,reconnectedSelectionRefreshed=True,noAutomaticSelectionWrite=True,manualSelectionRestored=True),indent=2))
    passed('P4-session-model-network-F','会话模型空目录/不支持重试、实际TCP断开禁用、服务端改变模型后重连重新读取且不自动写入，手动选择仍可用')
