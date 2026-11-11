#!/usr/bin/env python3
"""只管理可丢弃的独立 Host 测试会话；不接触用户主 Host。"""
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
HOST = Path('/private/tmp/harmony-isolated-host')
spec = importlib.util.spec_from_file_location('smoke', ROOT / 'harmony/testing/smoke_ui.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.BUNDLE = 'com.clarklevis.whaleness.harmony.restart'
folder = Path(tempfile.mkdtemp(prefix='harmony-isolated-catalog-')); ui = m.UI(folder)


def status(): return json.load(urllib.request.urlopen('http://127.0.0.1:18896/status', timeout=25))


def wait_for(predicate):
    until = time.monotonic() + 30
    while time.monotonic() < until:
        value = predicate()
        if value: return value
        time.sleep(0.5)
    raise AssertionError('隔离 Host 元数据确认超时')


def menu(value):
    row = [row for row in ui.nodes() if row.get('text') == value][-1]
    left, top, right, bottom = map(int, re.findall(r'-?\d+', row['bounds']))
    m.hdc('shell', 'uitest', 'uiInput', 'click', str((left + right) // 2), str((top + bottom) // 2)); time.sleep(0.7)


def context(identifier, action):
    _, x, y = ui.control('session-' + identifier)
    m.hdc('shell', 'uitest', 'uiInput', 'longClick', str(x), str(y)); time.sleep(0.5); ui.click(action, 'text')


def main():
    print('REPORT_DIR', folder, flush=True)
    seed = json.loads((HOST / 'own-sessions.json').read_text()); own = seed['sessions'][0]
    assert 'isolated' in own['name']; identifier = own['id']; before = status()
    assert before['session']['sessionId'] == identifier and not before['archived']
    pids = subprocess.check_output(['lsof', '-t', '-iTCP:18891', '-sTCP:LISTEN'], text=True).splitlines(); assert len(pids) == 1
    details = subprocess.check_output(['lsof', '-a', '-p', pids[0], '-d', 'cwd', '-Fn'], text=True)
    assert next(line[1:] for line in details.splitlines() if line.startswith('n')) == str(HOST / 'workspace')
    # 独立目录只有本轮数据；先保留设置和会话文件，任何测试数据变更均可恢复／删除重建。
    backup = folder / 'before-test'; backup.mkdir()
    shutil.copy2(HOST / 'profiles/web/cordis.patch.yml', backup / 'cordis.patch.yml')
    shutil.copytree(HOST / 'sessions', backup / 'sessions')
    added = False; route = 'tcp:18891'
    try:
        if route + ' ' + route not in m.hdc('fport', 'ls'): m.hdc('rport', route, route); added = True
        m.hdc('shell', 'aa', 'force-stop', m.BUNDLE); m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
        ui.text('已连接', timeout=25); ui.click('workspace-picker'); ui.click('workspace-browse')
        ui.input('directory-path', str(HOST / 'workspace')); ui.click('directory-go'); ui.text(str(HOST / 'workspace'))
        ui.click('workspace-create'); ui.wait('workspace-picker')
        wait_for(lambda: status().get('workspace', {}).get('path') == str(HOST / 'workspace'))
        # 先前创建的 A 未指定 workspaceId；新工作区不会自动收编这条旧会话。
        ui.click('workspace-picker'); ui.click('workspace-ungrouped')
        ui.click('open-sidebar'); ui.wait('session-' + identifier)
        title = '鸿蒙 P2-隔离管理-' + uuid.uuid4().hex[:8]
        context(identifier, '重命名'); ui.input('session-rename-input', title); ui.click('session-rename-save')
        ui.input('sidebar-search', title); ui.wait('session-' + identifier)
        wait_for(lambda: status().get('session', {}).get('projections', {}).get('values', {}).get('title') == title)
        ui.screenshot('real-isolated-renamed')
        context(identifier, '归档'); menu('取消'); assert not status()['archived']; ui.wait('session-' + identifier)
        context(identifier, '归档'); menu('归档'); wait_for(lambda: status()['archived'])
        assert not ui.find('session-' + identifier); ui.screenshot('real-isolated-archived')
        (folder / 'results.json').write_text(json.dumps({'tasks': ['P2-06-G-part', 'P2-07-G-part'], 'gatewayVersion': '0.11.1', 'upstream': 'controlled-local-adapter', 'hostRoot': str(HOST), 'sessionId': identifier, 'checks': ['native directory and workspace-create', 'native search and rename; independent server title matches', 'archive cancel has no side effect', 'native archive confirmed by independent archivedSessionIds'], 'limits': ['restore protocol is not provided by current Host', 'production Host unchanged'], 'rollbackData': str(backup)}, ensure_ascii=False, indent=2))
        print('DONE', folder, flush=True)
    finally:
        if added: m.hdc('fport', 'rm', route, route)


if __name__ == '__main__': main()
