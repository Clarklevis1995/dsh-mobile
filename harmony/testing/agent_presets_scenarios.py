# coding: utf-8
"""原生Agent预设确认、异常、实际TCP恢复与浅深采集；仅隔离夹具F。"""
import json
import shutil
import subprocess
import sys
import time


def run(ui,m,folder,passed,control,servers):
    m.connect(ui)
    ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-agent-presets');ui.navigate('settings-agent-presets','agent-presets-heading')
    ui.wait('agent-preset-current-build')
    log=folder/'gateway-18783.log';mode=folder/'preset-mode'
    def writes():return log.read_text().count('request set-default ')
    def reopen():ui.navigate('page-back','settings-agent-presets');ui.navigate('settings-agent-presets','agent-presets-heading')
    def choose(preset,cancel=False):
        count=writes();ui.click('agent-preset-card-'+preset,scroll=True);ui.wait('confirmation-accept');assert writes()==count
        ui.click('取消','text') if cancel else ui.click('confirmation-accept')
        if cancel:assert writes()==count
        else:ui.wait('agent-preset-current-'+preset)
    choose('read',cancel=True);ui.wait('agent-preset-current-build');choose('read');reopen();ui.wait('agent-preset-current-read')
    mode.write_text('failure');reopen();ui.wait('agent-preset-card-broken');assert str(ui.find('agent-preset-card-broken').get('enabled')).lower()=='false'
    count=writes();ui.click('agent-preset-card-fail',scroll=True);ui.wait('confirmation-accept');assert writes()==count;ui.click('confirmation-accept');ui.wait('agent-presets-error');ui.text('测试默认预设暂不可用');ui.wait('agent-preset-current-read');ui.screenshot('agent-preset-save-failed')
    mode.write_text('empty');reopen();ui.wait('agent-presets-empty-retry');assert not ui.find('agent-preset-card-read');ui.screenshot('agent-presets-empty')
    mode.write_text('normal');ui.click('agent-presets-empty-retry');ui.wait('agent-preset-current-read')
    mode.write_text('unsupported');reopen();ui.wait('agent-presets-error');ui.text('测试主机不支持Agent预设');ui.screenshot('agent-presets-unsupported')
    mode.write_text('normal');ui.click('agent-presets-retry');ui.wait('agent-preset-current-read');assert not ui.find('agent-presets-error')
    choose('build')
    process=control['process'];assert process.args==[sys.executable,str(m.ROOT/'harmony/testing/gateway_fixture.py')] and control['env']['HARMONY_FIXTURE_PORT']=='18783'
    count=writes();process.terminate();process.wait(timeout=6);ui.wait('agent-presets-disconnected',timeout=40)
    assert all(str(r.get('enabled')).lower()=='false' for r in ui.nodes() if r.get('id','').startswith('agent-preset-card-'))
    replacement=subprocess.Popen(process.args,env=dict(control['env'],HARMONY_FIXTURE_DEFAULT_AGENT='read'),stdout=control['log'],stderr=control['log']);servers.append(replacement)
    ui.wait('agent-preset-current-read',timeout=40);assert writes()==count;ui.screenshot('agent-presets-reconnected')
    m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(.5)
    replacement.terminate();replacement.wait(timeout=6)
    restored=subprocess.Popen(process.args,env=control['env'],stdout=control['log'],stderr=control['log']);servers.append(restored)
    m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('agent-preset-current-build',timeout=40);assert writes()==count
    ui.screenshot('agent-presets-foreground-refreshed')
    m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('home-settings');ui.text('已连接',timeout=40)
    ui.navigate('home-settings','settings-agent-presets');ui.navigate('settings-agent-presets','agent-presets-heading');ui.wait('agent-preset-current-build')
    for theme in ['light','dark']:
        ui.navigate('page-back','settings-style');ui.navigate('settings-style','appearance-system');ui.choose('appearance-'+theme)
        ui.navigate('page-back','home-settings');ui.navigate('home-settings','settings-agent-presets');ui.navigate('settings-agent-presets','agent-presets-heading');ui.wait('agent-preset-current-build')
        ui.nodes();shutil.copy2(folder/'layout.json',folder/('agent-presets-'+theme+'-layout.json'));ui.screenshot('agent-presets-'+theme)
    (folder/'agent-presets-results.json').write_text(json.dumps(dict(cancelNoWrite=True,confirmedRetained=True,failedSaveRollback=True,brokenDisabled=True,emptyRetry=True,unsupportedRetry=True,actualTcpDisconnectDisabled=True,reconnectedServerDefaultRead=True,actualHomeReturnRefreshed=True,coldStartRead=True,noAutomaticWrite=True,themes=['light','dark'],scope='API21模拟器生产Native/ArkUI，隔离夹具F；真实Host与V另验'),ensure_ascii=False,indent=2))
    passed('P4-agent-presets-recovery-F','Agent默认确认/取消、失败保旧值、损坏禁用、空/不支持重试、真实TCP断开/重连和Home返回读取Host实际值、冷启与浅深切换通过，读取无自动写入')
