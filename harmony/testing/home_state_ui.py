#!/usr/bin/env python3
"""只用独立夹具和测试包检查首页状态，F 与视觉取样，不代表真实网关 G。"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import smoke_ui as m

folder = Path(tempfile.mkdtemp(prefix='harmony-home-state-'))
ui = m.UI(folder)
print('REPORT_DIR', folder, flush=True)
app = Path(sys.argv[1]).resolve()
assert json.loads((app / 'AppScope/app.json5').read_text())['app']['bundleName'] == m.BUNDLE
port = 'tcp:18783'
owned_route = port + ' ' + port not in m.hdc('fport', 'ls')
log = (folder / 'fixture.log').open('w')
server = subprocess.Popen([sys.executable, str(m.ROOT / 'harmony/testing/gateway_fixture.py')],
    env=dict(os.environ, HARMONY_FIXTURE_CATALOG_DELAY='6', HARMONY_FIXTURE_STREAM_DELAY='8'), stdout=log, stderr=log)
results = []
def capture(name, assertion):
    ui.screenshot(name)
    results.append({'state': name, 'assertion': assertion, 'method': 'F native UI with explicit local fixture'})
    (folder / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
try:
    if owned_route: m.hdc('rport', port, port)
    time.sleep(.5)
    assert server.poll() is None
    m.hdc('uninstall', m.BUNDLE)
    m.hdc('install', str(app / 'entry/build/default/outputs/default/entry-default-unsigned.hap'))
    m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
    time.sleep(2)
    m.connect(ui)
    ui.navigate('conversation-back', 'home-settings'); ui.wait('session-search')
    capture('normal', '真实目录回包后显示本轮会话')
    ui.input('session-search', 'no-match-owned-harmony')
    ui.text('搜索结果为空'); capture('search-empty', '正文／标题均无匹配')
    ui.input('session-search', '')
    ui.navigate('workspace-picker', 'workspace-all'); ui.navigate('workspace-workspace-smoke', 'workspace-picker'); ui.text('暂无已知会话')
    capture('empty', '服务端空工作区的sessionIds为空')
    ui.navigate('workspace-picker', 'workspace-all'); ui.navigate('workspace-ungrouped', 'workspace-picker')
    ui.click('鸿蒙原生会话验证', 'text'); ui.message('slow')
    ui.navigate('conversation-back', 'home-settings'); ui.wait('session-refresh'); ui.click('session-refresh')
    ui.wait('LoadingProgress', 'type')
    capture('loading', '本轮刷新目录延迟6秒，截图时仍有在途目录请求')
    ui.wait('session-running-harmony-smoke', timeout=18)
    capture('running', '服务端目录包含当前受控流的真实运行标记')
    ui.click('鸿蒙原生会话验证', 'text'); ui.click('message-stop'); ui.navigate('conversation-back', 'home-settings')
    server.terminate(); server.wait(timeout=5)
    ui.text('连接中断', timeout=15)
    capture('offline', '断开本轮夹具后保留当前内存会话并显示重连状态')
    print('DONE', folder, flush=True)
finally:
    if server.poll() is None:
        server.terminate(); server.wait(timeout=5)
    log.close()
    m.hdc('shell', 'aa', 'force-stop', m.BUNDLE)
    if owned_route: m.hdc('fport', 'rm', port, port)
