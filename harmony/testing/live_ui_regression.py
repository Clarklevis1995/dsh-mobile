#!/usr/bin/env python3
"""P1 真实 Host 验收；辅助端限制所有请求为本轮专用会话。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('smoke', ROOT / 'harmony/testing/smoke_ui.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
m.BUNDLE = 'com.clarklevis.whaleness.harmony.acceptance'
folder = Path(tempfile.mkdtemp(prefix='harmony-live-p1-'))
ui = m.UI(folder)
results = []


def call(route, data=None):
    request = urllib.request.Request('http://127.0.0.1:18885' + route,
        None if data is None else json.dumps(data).encode(), {'Content-Type': 'application/json'})
    return json.load(urllib.request.urlopen(request, timeout=35))


def wait_for(predicate, timeout=100):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        value = predicate()
        if value:
            return value
        time.sleep(0.6)
    raise AssertionError('真实场景等待超时')


def records(identifier):
    return call('/history', {'id': identifier})['records']


def text_of(record):
    event = record.get('event') or record.get('data') or {}
    blocks = event.get('message', {}).get('content') or event.get('content') or []
    return event.get('text') or ''.join(block.get('text', '') for block in blocks if block.get('type') == 'text')


def kind_of(record):
    return record.get('type') or (record.get('event') or {}).get('type')


def assistant_count(identifier, marker):
    return sum(kind_of(row) == 'assistant/message' and marker in text_of(row) for row in records(identifier))


def passed(identifier, detail):
    results.append({'task': identifier, 'result': detail})
    print('PASS', identifier, detail, flush=True)
    (folder / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))


def select(name):
    if ui.find('message-draft'):
        m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
        time.sleep(0.3)
    for _ in range(4): ui.scroll(False)
    ui.click('刷新', 'text')
    ui.click(name, 'text', scroll=True)
    ui.wait('message-draft')


def run():
    print('REPORT_DIR ' + str(folder), flush=True)
    state = call('/status')
    sessions = state['sessions']
    a = sessions[0] if sessions else call('/create', {'label': 'A'})
    b = next((item for item in sessions if item['name'].endswith('-B')), None) or call('/create', {'label': 'B'})
    for item in [a, b]:
        call('/subscribe', {'id': item['id']})
        if not any(kind_of(row) == 'user/message' for row in records(item['id'])):
            call('/message', {'id': item['id'], 'text': '专用验收会话初始化：只回复P1_SETUP，不调用任何工具或读写文件。'})
            wait_for(lambda: assistant_count(item['id'], 'P1_SETUP') > 0)
    call('/subscribe', {'id': a['id']})
    m.hdc('shell', 'aa', 'force-stop', m.BUNDLE)
    m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
    time.sleep(0.7)
    if ui.find('gateway-connect'):
        ui.click('gateway-connect')
    ui.text('已连接', timeout=20)
    passed('P1-01', '真实网关版本 ' + state['gatewayVersion'] + '；专用 A/B 会话和只读观测连接已建立')
    select(a['name'])
    baseline = assistant_count(a['id'], 'P1_READY')
    ui.message('只回复P1_READY，不调用工具，不读取或修改任何文件。')
    wait_for(lambda: assistant_count(a['id'], 'P1_READY') > baseline)
    same = '同文不同消息回归：只回复P1_SAME，不调用工具。'
    old_user = sum(kind_of(row) == 'user/message' and text_of(row) == same for row in records(a['id']))
    old_answer = assistant_count(a['id'], 'P1_SAME')
    ui.message(same)
    ui.message(same)
    wait_for(lambda: assistant_count(a['id'], 'P1_SAME') >= old_answer + 2)
    actual_user = [row for row in records(a['id']) if kind_of(row) == 'user/message' and text_of(row) == same]
    assert len(actual_user) == old_user + 2 and len({row['seq'] for row in actual_user}) == len(actual_user)
    passed('P1-02-part', '两条同文消息有不同持久序号，两次真实回复均已收到')
    ui.input('message-draft', 'P1-draft-A')
    select(b['name'])
    assert ui.find('message-draft').get('text', '') == ''
    ui.input('message-draft', 'P1-draft-B')
    select(a['name'])
    assert ui.find('message-draft').get('text') == 'P1-draft-A'
    ui.message('请从0001到0200逐行输出序号，连续输出，不调用任何工具。')
    before = next(row for row in call('/status')['sessions'] if row['id'] == a['id'])['streamFrames']
    wait_for(lambda: next(row for row in call('/status')['sessions'] if row['id'] == a['id'])['streamFrames'] >= before + 3)
    ui.click('停止', 'text')
    time.sleep(1)
    old = assistant_count(a['id'], 'P1_AFTER_STOP')
    ui.message('只回复P1_AFTER_STOP，不调用任何工具。')
    wait_for(lambda: assistant_count(a['id'], 'P1_AFTER_STOP') > old)
    passed('P1-03-part', '真实生成中停止后可继续发送并收到回复；终止事件详单待最终核对')
    ui.input('message-draft', 'P1-foreground-draft')
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Home')
    time.sleep(2)
    m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
    ui.text('已连接', timeout=18)
    assert ui.wait('message-draft').get('text') == 'P1-foreground-draft'
    passed('P1-08-part', '真实网关前后台返回后当前会话与内存草稿保留')
    # 本轮只回答自己新建会话中的问题，不进入含其他请求的全局待办。
    ui.message('请调用提问工具，一次提出三题：第一题单选A或B，第二题多选X和Y，第三题自由文字。收到全部回答后只回复P1_ANSWERS。不要调用其他工具或读写文件。')
    request = wait_for(lambda: next((item for item in call('/pending') if item['sessionId'] == a['id'] and item['kind'] == 'question-requested'), None))
    rpc = request['rpcId']
    assert len(request['questions']) == 3
    # 从当前滚动位置向上找表单首题，控件坐标始终来自实时布局。
    for _ in range(4):
        if ui.find('question-option-' + rpc + '-0-0'):
            break
        ui.scroll(False)
    ui.click('question-option-' + rpc + '-0-0', scroll=True)
    ui.click('question-option-' + rpc + '-1-0', scroll=True)
    ui.click('question-option-' + rpc + '-1-1', scroll=True)
    ui.input('question-custom-' + rpc + '-2', 'P1-native-answer', scroll=True)
    ui.click('question-submit-' + rpc, scroll=True)
    wait_for(lambda: assistant_count(a['id'], 'P1_ANSWERS') > 0)
    passed('P1-05-part', '真实三题单选、多选、自定义文字由鸿蒙表单提交，Agent 已继续')
    summary = call('/status')
    (folder / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    (folder / 'own-session-events.json').write_text(json.dumps(call('/history', {'id': a['id']}), ensure_ascii=False, indent=2))
    print('DONE_PARTIAL ' + str(folder), flush=True)


if __name__ == '__main__':
    run()
