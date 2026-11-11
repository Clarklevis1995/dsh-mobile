#!/usr/bin/env python3
"""只重启专属 DSH_HOME 的真实测试 Host；不触碰 3080／3081 或用户主服务。"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
HOST_ROOT = Path('/private/tmp/harmony-isolated-host')
WORKSPACE = HOST_ROOT / 'workspace'
NODE = Path('/Users/lichaofan/.nvm/versions/node/v24.20.0/bin/node')
PACKAGE = NODE.parent.parent / 'lib/node_modules/@deepseek-ai/dsh'
spec = importlib.util.spec_from_file_location('smoke', ROOT / 'harmony/testing/smoke_ui.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.BUNDLE = 'com.clarklevis.whaleness.harmony.restart'
folder = Path(tempfile.mkdtemp(prefix='harmony-live-restart-'))
ui = m.UI(folder)


def call(route='/status', data=None, port=18893):
    request = urllib.request.Request('http://127.0.0.1:' + str(port) + route,
        None if data is None else json.dumps(data).encode(), {'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(request, timeout=15))


def wait_for(predicate, timeout=35):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        try:
            result = predicate()
            if result: return result
        except (OSError, ValueError): pass
        time.sleep(0.5)
    raise AssertionError('独立真实 Host 恢复超时')


def records(identifier): return call('/history', {'id': identifier})['records']


def kind(row): return row.get('type') or row.get('event', {}).get('type')


def restart_owned_host():
    pids = subprocess.check_output(['lsof', '-t', '-iTCP:18891', '-sTCP:LISTEN'], text=True).splitlines()
    assert len(pids) == 1, '18891 监听者不唯一，拒绝重启'
    pid = int(pids[0]); details = subprocess.check_output(['lsof', '-a', '-p', str(pid), '-d', 'cwd', '-Fn'], text=True)
    cwd = next(line[1:] for line in details.splitlines() if line.startswith('n'))
    assert Path(cwd).resolve() == WORKSPACE.resolve(), '监听者不是本轮临时 Host，拒绝重启'
    os.kill(pid, signal.SIGTERM)
    wait_for(lambda: subprocess.run(['lsof', '-t', '-iTCP:18891', '-sTCP:LISTEN'], capture_output=True).returncode != 0, 15)
    env = dict(os.environ, DSH_HOME=str(HOST_ROOT), DSH_AGENTS_HOME=str(HOST_ROOT / 'agents'), HARMONY_DSH_NODE_MODULES=str(PACKAGE / 'node_modules'))
    log = (folder / 'host-restarted.log').open('w')
    host = subprocess.Popen([str(NODE), str(PACKAGE / 'lib/bin.js'), 'web', '--host', '127.0.0.1', '--port', '18891', '--no-open'], cwd=WORKSPACE, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    redactor = subprocess.Popen([str(NODE), str(ROOT / 'harmony/testing/redact_stream.cjs')], stdin=host.stdout, stdout=log, stderr=log, start_new_session=True)
    host.stdout.close(); log.close()
    (folder / 'owned-processes.json').write_text(json.dumps({'hostPid': host.pid, 'redactorPid': redactor.pid}))
    wait_for(lambda: call('/mgw/status', port=18891).get('gatewayEnabled'), 40)
    return host.pid


def main():
    print('REPORT_DIR', folder, flush=True)
    seed = json.loads((HOST_ROOT / 'own-sessions.json').read_text())
    state = call(); own = next(row for row in state['sessions'] if row['id'] == seed['sessions'][0]['id'])
    identifier = own['id']; assert 'isolated' in own['name']
    added = []
    try:
        for port in [18891, 18893]:
            route = 'tcp:' + str(port)
            if route + ' ' + route not in m.hdc('fport', 'ls'): m.hdc('rport', route, route); added.append(route)
        hap = Path('/private/tmp/harmony-restart-latest-app/entry/build/default/outputs/default/entry-default-unsigned.hap')
        m.hdc('install', '-r', str(hap)); m.hdc('shell', 'aa', 'force-stop', m.BUNDLE); m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
        ui.text('已连接', timeout=30); ui.click(own['name'], 'text'); ui.wait('message-draft')
        call('/subscribe', {'id': identifier})
        suffix = uuid.uuid4().hex[:8].upper()
        marker = 'HARMONY_RESTART_SLOW_' + suffix
        before_frames = next(row for row in call()['sessions'] if row['id'] == identifier)['streamFrames']
        ui.message(marker)
        wait_for(lambda: next(row for row in call()['sessions'] if row['id'] == identifier)['streamFrames'] > before_frames + 2)
        before = records(identifier); before_seqs = {row['seq'] for row in before}
        (folder / 'before.json').write_text(json.dumps(before, ensure_ascii=False))
        print('RESTART owned 18891 Host during generation', flush=True)
        pid = restart_owned_host()
        ui.text('已连接', timeout=35)
        wait_for(lambda: any(marker in json.dumps(row) for row in records(identifier)))
        after = records(identifier)
        users = [row for row in after if kind(row) == 'user/message' and marker in json.dumps(row)]
        assert len(users) == 1, 'Host 重启后出现重复发送'
        assert before_seqs.issubset({row['seq'] for row in after}), '恢复历史遗漏已有事件'
        next_marker = 'HARMONY_AFTER_RESTART_' + suffix
        ui.message(next_marker)
        wait_for(lambda: any(kind(row) == 'assistant/message' and next_marker in json.dumps(row) for row in records(identifier)), 40)
        ui.text(next_marker); ui.screenshot('real-host-restarted')
        summary = next(row for row in call()['sessions'] if row['id'] == identifier)
        assert summary['sequenceConflicts'] == 0
        result = {'task': 'P1-07-G-part', 'gatewayVersion': call('/mgw/status', port=18891).get('version'), 'upstream': 'controlled-local-adapter; no external model requests', 'bundle': m.BUNDLE, 'hapSha256': hashlib.sha256(hap.read_bytes()).hexdigest(), 'sessionId': identifier, 'hostPid': pid, 'checks': ['generation-time real Host restart', 'native credential reconnect', 'persisted event sequence coverage', 'one user message; no automatic resend', 'new message and persisted assistant reply after restart'], 'limits': ['ACK-window and approval-window restart not covered']}
        (folder / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)); print('DONE', folder, flush=True)
    finally:
        for route in added: m.hdc('fport', 'rm', route, route)


if __name__ == '__main__': main()
