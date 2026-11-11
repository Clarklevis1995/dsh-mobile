#!/usr/bin/env python3
"""P2 真实只读业务回归。仅升级本轮 acceptance 包，浏览既有专用工作区，不发送新建、重命名或归档请求。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('smoke', ROOT / 'harmony/testing/smoke_ui.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.BUNDLE = 'com.clarklevis.whaleness.harmony.acceptance'
folder = Path(tempfile.mkdtemp(prefix='harmony-live-product-'))
ui = m.UI(folder)
results = []


def call(port, route='/status'):
    return json.load(urllib.request.urlopen('http://127.0.0.1:' + str(port) + route, timeout=30))


def wait_for(predicate, timeout=30):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        value = predicate()
        if value: return value
        time.sleep(0.5)
    raise AssertionError('真实业务等待超时')


def passed(task, detail):
    results.append({'task': task, 'detail': detail})
    (folder / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print('PASS', task, detail, flush=True)


def main():
    print('REPORT_DIR', folder, flush=True)
    scoped = call(18887); identifier = scoped['sessionId']; path = scoped['workspacePath']
    assert '/.harmony-validation/' in path and identifier == call(18895)['session']['sessionId']
    hap = Path('/private/tmp/harmony-p2-live-latest-app/entry/build/default/outputs/default/entry-default-unsigned.hap')
    m.hdc('install', '-r', str(hap))
    m.hdc('shell', 'aa', 'force-stop', m.BUNDLE)
    m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
    ui.text('已连接', timeout=25)
    ui.click('home-auth'); ui.wait('host-select-legacy-host')
    # 不读取 Token；自动连接与真实主机响应证明原 Asset 凭据已经可用。
    ui.screenshot('legacy-migrated-host'); ui.click('page-back')
    passed('P2-04-G', '本轮旧单主机验收包原位升级，真实 Asset 迁移为 legacy-host 后自动连接 0.11.0 Host，无需再次输入配对码')
    ui.click('workspace-picker'); ui.click('workspace-' + scoped['workspaceId'], scroll=True)
    ui.click('workspace-picker'); ui.click('workspace-browse')
    ui.input('directory-path', path); ui.click('directory-go'); ui.text(path)
    ui.screenshot('real-directory'); ui.click('page-back'); ui.click('page-back')
    authoritative = call(18895)
    assert authoritative['workspace']['path'] == path and identifier in authoritative['workspace']['sessionIds']
    ui.click('open-sidebar'); ui.input('sidebar-search', scoped['name']); ui.wait('session-' + identifier)
    ui.click('session-' + identifier); ui.wait('message-draft')
    ui.screenshot('real-own-conversation')
    passed('P2-06-G-readonly', '真实主机已有工作区选择、目录浏览、工作区会话筛选和搜索可用；未发送新建、重命名或归档请求')
    (folder / 'environment.json').write_text(json.dumps({'gatewayVersion': call(18885)['gatewayVersion'], 'bundle': m.BUNDLE, 'hapSha256': hashlib.sha256(hap.read_bytes()).hexdigest(), 'sessionId': identifier, 'workspacePath': path, 'evidence': 'G-real-host-native-ui'}, ensure_ascii=False, indent=2))
    print('DONE', folder, flush=True)


if __name__ == '__main__': main()
