#!/usr/bin/env python3
"""真实独立 Host 的问题取消、冷重连重放及外端处理；受控模型只触发 ask_user_question。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('smoke', ROOT / 'harmony/testing/smoke_ui.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
m.BUNDLE = 'com.clarklevis.whaleness.harmony.restart'
folder = Path(tempfile.mkdtemp(prefix='harmony-real-questions-')); ui = m.UI(folder)


def call(route, data=None):
    request = urllib.request.Request('http://127.0.0.1:18893' + route, None if data is None else json.dumps(data).encode(), {'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(request, timeout=20))


def wait_pending(identifier, timeout=30):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        value = [row for row in call('/pending') if row['sessionId'] == identifier and row['kind'] == 'question-requested']
        if value: return value[0]
        time.sleep(0.4)
    raise AssertionError('真实 Host 未发起本轮问题')


def main():
    print('REPORT_DIR', folder, flush=True)
    state = call('/status'); session = state['sessions'][0]; assert 'isolated' in session['name']
    identifier = session['id']; added = []
    try:
        for port in [18891, 18893]:
            route = 'tcp:' + str(port)
            if route + ' ' + route not in m.hdc('fport', 'ls'): m.hdc('rport', route, route); added.append(route)
        m.hdc('shell', 'aa', 'force-stop', m.BUNDLE); m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
        ui.text('已连接', timeout=25); ui.click(session['name'], 'text'); ui.wait('message-draft')
        call('/subscribe', {'id': identifier})
        marker = 'HARMONY_QUESTION_CANCEL_' + uuid.uuid4().hex[:8].upper()
        ui.message(marker); pending = wait_pending(identifier); ui.click('interaction-inbox')
        ui.wait('question-option-' + pending['rpcId'] + '-0-0'); ui.screenshot('real-question-cancel')
        ui.click('question-cancel-' + pending['rpcId'], scroll=True); ui.text('当前没有待处理事项'); ui.click('interaction-inbox')
        ui.message('HARMONY_QUESTION_REPLAY_' + uuid.uuid4().hex[:8].upper()); replay = wait_pending(identifier)
        ui.click('interaction-inbox'); ui.wait('question-option-' + replay['rpcId'] + '-0-0')
        m.hdc('shell', 'aa', 'force-stop', m.BUNDLE); time.sleep(1); m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
        ui.text('已连接', timeout=25); ui.click('interaction-inbox'); ui.wait('question-option-' + replay['rpcId'] + '-0-0')
        ui.screenshot('real-question-replayed')
        answers = [{'id': row['id'], 'selected': [row['options'][0]['label']] if row.get('options') else [], **({'custom': 'HARMONY_EXTERNAL_ANSWER'} if not row.get('options') else {})} for row in replay['questions']]
        call('/question', {'rpcId': replay['rpcId'], 'answers': answers})
        ui.text('当前没有待处理事项'); assert not ui.find('question-cancel-' + replay['rpcId'])
        (folder / 'results.json').write_text(json.dumps({'task': 'P1-05-G-part', 'gatewayVersion': state['gatewayVersion'], 'upstream': 'controlled-local-adapter', 'sessionId': identifier, 'checks': ['native cancel resolves real question', 'same rpcId replayed after cold client reconnect', 'other client answers and native stale card is removed'], 'limits': ['question timeout and transport ACK window not covered']}, ensure_ascii=False, indent=2))
        print('DONE', folder, flush=True)
    finally:
        for route in added: m.hdc('fport', 'rm', route, route)


if __name__ == '__main__': main()
