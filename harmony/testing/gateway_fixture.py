#!/usr/bin/env python3
"""仅在本机提供确定性测试数据；不连接真实 Harness，也不执行工具命令。"""
import base64
import hashlib
import itertools
import json
import os
import socket
import socketserver
import struct
import threading
import time
import uuid

GATEWAY_ID = os.environ.get('HARMONY_FIXTURE_ID', 'a56a1098-8519-43a1-9dce-fb99863bf5bb')
PORT = int(os.environ.get('HARMONY_FIXTURE_PORT', '18783'))
LABEL = os.environ.get('HARMONY_FIXTURE_LABEL', '')
ARCHIVED = set()
WORKSPACES = []
LOCK = threading.RLock()
CLIENTS = set()
IDS = itertools.count(1)
RUN_ID = uuid.uuid4().hex[:8]
SESSIONS = {}
QUESTIONS = {}
APPROVALS = {}
CANCELLED = {}
FEATURES = os.environ.get('HARMONY_FIXTURE_FEATURES') == '1'
SLASH = os.environ.get('HARMONY_FIXTURE_SLASH') == '1'
PRODUCTS = os.environ.get('HARMONY_FIXTURE_PRODUCTS') == '1'
VOICE_TEST = os.environ.get('HARMONY_FIXTURE_VOICE') == '1'
FILES_TEST = os.environ.get('HARMONY_FIXTURE_FILES') == '1'
UPLOAD_TEST = os.environ.get('HARMONY_FIXTURE_UPLOADS') == '1'
LAST_COMMAND_SELECTION = None
MODELS = {}
PERMISSIONS = {}
AGENTS = {}
QUEUES = {'harmony-smoke': [
    dict(id='q-edit', placement='queued', message=dict(content=[dict(type='text', text='待编辑的排队原文')])),
    dict(id='q-remove', placement='queued', message=dict(content=[dict(type='text', text='待移除排队消息')])),
    dict(id='q-file', placement='queued', message=dict(content=[dict(type='file', name='fixture.txt')]))]}
DEFAULT_MODEL = dict(provider='fixture', model='read')
DEFAULTS = {'permission': 'read-only', 'agent-preset': 'build'}
PRESETS = [dict(id='build', name='构建 Agent'), dict(id='read', name='阅读 Agent')]
PERMISSION_OPTIONS = [dict(value='read-only', name='只读', description='仅限测试夹具'), dict(value='workspace-write', name='工作区写入', description='仅限测试夹具')]
MODEL_GROUPS = [dict(id='fixture', name='共享模型', models=[dict(id='read', name='读取模型'),
    dict(id='reason', name='推理模型', reasoning=dict(defaultEffort='low', efforts=[dict(id='low', name='低'), dict(id='high', name='高')])), dict(id='fail', name='不可用模型')])]


def feature_request(client, request, identifier):
    global DEFAULT_MODEL, LAST_COMMAND_SELECTION
    kind = request.get('type')
    if VOICE_TEST:
        from voice_fixture import handle as handle_voice
        if handle_voice(client, request, identifier): return True
    if UPLOAD_TEST:
        from upload_fixture import handle
        if handle(client, request, identifier): return True
    if PRODUCTS:
        from goal_fixture import handle as handle_goal
        if handle_goal(client, request, identifier): return True
        from product_fixture import handle
        if handle(client, request, identifier): return True
    if not FEATURES: return False
    if SLASH and kind == 'commands':
        client.send(dict(kind=kind, sessionId=identifier, groups=[dict(id='test', title='测试命令', items=[
            dict(id='cmd:args', name='prepare', description='输入参数后执行', ui=dict(kind='input', insertText='/prepare ', submitRequest='command-execute')),
            dict(id='cmd:switch', name='switch', description='从主机目录选择', ui=dict(kind='select', optionsRequest='command-options', selectionRequest='command-select')),
            dict(id='cmd:echo', name='echo-native', description='立即发送测试消息', ui=dict(kind='immediate', submitRequest='message', submitText='echo'))])]))
    elif SLASH and kind == 'command-options':
        client.send(dict(kind=kind, sessionId=identifier, command=request['command'], options=[dict(id='read', label='读取模型'), dict(id='reason', label='推理模型')]))
    elif SLASH and kind == 'command-select':
        MODELS[identifier] = dict(provider='fixture', model=request['optionId'])
        LAST_COMMAND_SELECTION = dict(kind='command-selected', sessionId=identifier, command=request['command'], selected=dict(id=request['optionId'], label='读取模型' if request['optionId'] == 'read' else '推理模型'), value=MODELS[identifier])
        client.send(LAST_COMMAND_SELECTION)
    elif SLASH and kind == 'command-execute':
        client.send(dict(kind='command-executed', sessionId=identifier))
        event(identifier, dict(type='assistant/message', turn=15, step=1, text='命令执行完成：' + request['line']))
    elif kind == 'models': client.send(dict(kind=kind, current=MODELS.get(identifier, dict(provider='fixture', model='read')) if identifier else None, routable=bool(identifier), groups=MODEL_GROUPS))
    elif kind == 'select-model':
        time.sleep(.5)
        if request['model'] == 'fail': client.send(dict(kind='error', requestType=kind, sessionId=identifier, message='测试模型暂不可用'))
        else:
            MODELS[identifier] = {key: request[key] for key in ['provider', 'model', 'reasoningEffort'] if key in request}
            client.send(dict(kind=kind, sessionId=identifier, selected=MODELS[identifier]))
    elif kind == 'permission-options': client.send(dict(kind=kind, sessionPermissions=dict(currentValue=PERMISSIONS.get(identifier, 'workspace-write'), options=PERMISSION_OPTIONS)))
    elif kind == 'permission':
        PERMISSIONS[identifier] = request['name']; client.send(dict(kind=kind, sessionId=identifier, set=request['name']))
    elif kind == 'context-usage': client.send(dict(kind=kind, sessionId=identifier, contextPressure=dict(contextWindow=128000, pressureTokens=1234, projectedTokens=5678)))
    elif kind == 'session-stats': client.send(dict(kind=kind, sessionId=identifier, sessionStats=dict(turns=7, steps=9, llmMs=1500.5, toolMs=100.25, ttftMs=80.5), tokenUsage=dict(totals=dict(inputTokens=4321, outputTokens=1234, cacheReadTokens=9007199254740993))))
    elif kind == 'agent-presets': client.send(dict(kind=kind, presets=PRESETS, modeSelectionEnabled=True, authorable=False, hasDocument=False))
    elif kind == 'session-agent-preset':
        value = AGENTS.setdefault(identifier, dict(agentPreset='build', locked=False)); client.send(dict(kind=kind, sessionId=identifier, requestId=request['requestId'], **value))
    elif kind == 'select-agent-preset':
        value = AGENTS.setdefault(identifier, dict(agentPreset='build', locked=False))
        if value['locked']: client.send(dict(kind='error', requestType=kind, sessionId=identifier, requestId=request['requestId'], code='agent-preset/locked', message='会话 Agent 已锁定'))
        else:
            time.sleep(.5); value['agentPreset'] = request['agentPreset']; client.send(dict(kind=kind, sessionId=identifier, requestId=request['requestId'], **value))
    elif kind == 'default-model': client.send(dict(kind=kind, selection=DEFAULT_MODEL))
    elif kind == 'save-default-model':
        DEFAULT_MODEL = {key: request[key] for key in ['provider', 'model', 'reasoningEffort'] if key in request}; client.send(dict(kind=kind, saved=DEFAULT_MODEL))
    elif kind == 'defaults': client.send(dict(kind=kind, agentPresetDefault=DEFAULTS['agent-preset'], permissionDefault=DEFAULTS['permission'], permissionDefaultOptions=PERMISSION_OPTIONS))
    elif kind == 'set-default':
        DEFAULTS[request['target']] = request['value']; client.send(dict(kind=kind, applied=True, target=request['target'], value=request['value']))
    elif kind == 'queue-update':
        item = next((row for row in QUEUES.get(identifier, []) if row['id'] == request['itemId']), None)
        time.sleep(.5)
        accepted = item is not None and (request['action'] != 'steer' or (identifier in CANCELLED and not CANCELLED[identifier].is_set()))
        client.send(dict(kind='queue-item-updated', sessionId=identifier, itemId=request['itemId'], action=request['action'], accepted=accepted))
        if accepted: QUEUES[identifier] = [row for row in QUEUES[identifier] if row['id'] != request['itemId']]
        if accepted and request['action'] == 'steer':
            print('queue-steer-confirmed', identifier, request['itemId'], flush=True)
        broadcast(dict(kind='session-queue', sessionId=identifier, items=QUEUES.get(identifier, [])))
    else: return False
    return True



def session(identifier, title):
    SESSIONS[identifier] = dict(title=title, events=[], revision=0)
    return SESSIONS[identifier]


def catalog():
    return [dict(sessionId=key, updatedAt=time.time(), blank=not value['events'], running=key in CANCELLED and not CANCELLED[key].is_set(),
        origin='subagent' if key in ['harmony-child', 'harmony-once'] else None, parentSessionId='harmony-smoke' if key in ['harmony-child', 'harmony-once'] else None, cwd='/smoke/harmony', projections=dict(values=dict(title=value['title']))) for key, value in SESSIONS.items() if key not in ARCHIVED]


def broadcast(value, channel='control'):
    for client in list(CLIENTS):
        if client.channel == channel:
            client.send(value)


def event(identifier, payload):
    records = SESSIONS[identifier]['events']
    data = {key: value for key, value in payload.items() if key != 'type'}
    if payload['type'] == 'assistant/message':
        data['message'] = dict(content=[dict(type='text', text=data.pop('text', ''))])
    elif payload['type'] == 'user/message':
        data['content'] = [dict(type='text', text=data.pop('text', ''))]
    record = dict(seq=len(records) + 1, time=time.time(), type=payload['type'], data=data)
    records.append(record)
    for client in list(CLIENTS):
        if client.selected == identifier:
            client.send(client.scoped('event', seq=record['seq'], time=record['time'], event=payload))
    return record['seq']


def pending_question(identifier):
    rpc = 'q-' + str(next(IDS))
    value = dict(kind='question-requested', sessionId=identifier, rpcId=rpc, replay=False, questions=[
        dict(id='single', question='选择一项', options=[dict(label='A'), dict(label='B')]),
        dict(id='multi', question='选择多项', multiSelect=True, options=[dict(label='X'), dict(label='Y')]),
        dict(id='text', question='填写备注')])
    QUESTIONS[rpc] = value
    broadcast(value)


def pending_approval(identifier, mode):
    rpc = 'a-' + str(next(IDS))
    call = 'call-' + rpc
    event(identifier, dict(type='tool/call', name='bash', callId=call,
        arguments=dict(command='echo harmony-approval', cwd='/smoke/harmony')))
    value = dict(kind='approval-requested', sessionId=identifier, rpcId=rpc, approvalId='approval-' + rpc,
        toolName='bash', callId=call, reason='Fixture only', displayReason={'zh-CN': '确认本机测试命令（夹具不会执行）'}, replay=False)
    APPROVALS[rpc] = (value, mode)
    broadcast(value)


def stream_reply(client, identifier, slow):
    cancelled = threading.Event()
    CANCELLED[identifier] = cancelled
    attempt = 'attempt-' + str(next(IDS))
    turn = next(IDS)
    scope = dict(sessionId=identifier, subscriptionId=client.subscription, streamId='stream-' + RUN_ID + '-' + identifier)
    text = '流式回复验证完成：Kotlin 共享状态机与鸿蒙原生界面工作正常。'
    chunks = [text[i:i + 3] for i in range(0, len(text), 3)]
    with LOCK:
        state = SESSIONS[identifier]
        state['revision'] += 1
        client.send(dict(kind='assistant-stream', **scope, frame=dict(type='start', attemptId=attempt,
            revision=state['revision'], turn=turn, step=1, startedAfterSeq=len(state['events']))))
    emitted = ''
    delivered = 0
    for index, chunk in enumerate(chunks):
        if cancelled.wait(float(os.environ.get('HARMONY_FIXTURE_STREAM_DELAY', '3.0')) if slow else 0.06):
            break
        with LOCK:
            state['revision'] += 1
            client.send(dict(kind='assistant-stream', **scope, frame=dict(type='chunk', attemptId=attempt,
                revision=state['revision'], index=index, time=time.time(), chunk=dict(type='text-delta', index=0, text=chunk))))
            emitted += chunk
            delivered += 1
    with LOCK:
        seq = event(identifier, dict(type='assistant/message', turn=turn, step=1, text=emitted + ('（已停止）' if cancelled.is_set() else '')))
        state['revision'] += 1
        client.send(dict(kind='assistant-stream', **scope, frame=dict(type='end', attemptId=attempt,
            revision=state['revision'], index=delivered,
            outcome=dict(kind='committed', eventType='assistant/message', seq=seq))))
        if CANCELLED.get(identifier) is cancelled:
            CANCELLED.pop(identifier, None)


class Handler(socketserver.StreamRequestHandler):
    channel = ''
    selected = None
    subscription = ''

    def setup(self):
        super().setup()
        self.write_lock = threading.Lock()
        self.catalog_requests = 0

    def send(self, value):
        self.send_frame(json.dumps(value, ensure_ascii=False).encode(), 1)

    def send_frame(self, payload, opcode):
        header = bytes([0x80 | opcode])
        header += bytes([len(payload)]) if len(payload) < 126 else bytes([126]) + struct.pack('!H', len(payload)) if len(payload) < 65536 else bytes([127]) + struct.pack('!Q', len(payload))
        try:
            with self.write_lock:
                self.wfile.write(header + payload)
        except OSError:
            pass

    def scoped(self, kind, **fields):
        return dict(kind=kind, sessionId=self.selected, subscriptionId=self.subscription,
                    streamId='stream-' + RUN_ID + '-' + str(self.selected), **fields)

    def handle(self):
        self.request.settimeout(180)
        request_line = self.rfile.readline().decode()
        headers = {}
        while line := self.rfile.readline().decode().strip():
            key, value = line.split(':', 1)
            headers[key.lower()] = value.strip()
        if SLASH and request_line.startswith('GET /_fixture/replay-command-selected '):
            if LAST_COMMAND_SELECTION: broadcast(LAST_COMMAND_SELECTION)
            self.wfile.write(b'HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n'); return
        paired = 'dsh-pair.harmony-smoke' in headers.get('sec-websocket-protocol', '')
        if not paired and headers.get('authorization') != 'Bearer fixture-token':
            self.wfile.write(b'HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n')
            return
        accept = base64.b64encode(hashlib.sha1((headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.wfile.write(('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Protocol: dsh-mobile-v1\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n').encode())
        self.channel = headers.get('x-dsh-channel', 'control')
        if paired:
            delay = float(os.environ.get('HARMONY_FIXTURE_PAIR_DELAY', '0'))
            if delay:
                time.sleep(delay)
                print('pair-delay-complete', flush=True)
        with LOCK:
            CLIENTS.add(self)
            if paired:
                self.send(dict(kind='paired', token='fixture-token', gatewayId=GATEWAY_ID))
            self.send(dict(kind='hello', protocol=3, authenticated=True, gatewayId=GATEWAY_ID,
                historyFormatVersion=3, capabilities=(['speech-input-v1'] if VOICE_TEST else []) + ['split-channels', 'assistant-stream-v1', 'session-create', 'session-cancel'] + (['session-agent-preset', 'queue-control'] if FEATURES else []) + (['commands'] if SLASH else []) + (['agent-team-v1', 'subagent-conversations-v1', 'tasks', 'goals'] if PRODUCTS else []) + (['file-upload-v1'] if UPLOAD_TEST else []) + (['file-downloads', 'file-references-v1'] if FILES_TEST else [])))
            if self.channel == 'control':
                if FEATURES: self.send(dict(kind='session-queues', queues=QUEUES))
                for value in list(QUESTIONS.values()) + [value[0] for value in APPROVALS.values()]:
                    self.send(dict(value, replay=True))
        print('connected', self.channel, flush=True)
        try:
            while True:
                head = self.rfile.read(2)
                if len(head) != 2 or head[0] & 15 == 8:
                    return
                length = head[1] & 127
                if length == 126:
                    length = struct.unpack('!H', self.rfile.read(2))[0]
                elif length == 127:
                    length = struct.unpack('!Q', self.rfile.read(8))[0]
                if length > 8 * 1024 * 1024:
                    return
                mask = self.rfile.read(4) if head[1] & 128 else None
                data = self.rfile.read(length)
                if mask:
                    data = bytes(value ^ mask[index % 4] for index, value in enumerate(data))
                opcode = head[0] & 15
                if opcode == 9:
                    self.send_frame(data, 10)
                    print('websocket-ping', self.channel, flush=True)
                    continue
                if opcode != 1:
                    continue
                with LOCK:
                    self.process(json.loads(data))
        except (OSError, ValueError, struct.error):
            pass
        finally:
            with LOCK:
                CLIENTS.discard(self)

    def process(self, request):
        kind = request.get('type')
        identifier = request.get('sessionId') or self.selected
        print('request', kind, request.get('rpcId', ''), request.get('outcome', ''), flush=True)
        if FILES_TEST:
            import download_fixture
            if download_fixture.handle(self, request, identifier): return
        if feature_request(self, request, identifier): return
        if kind == 'sessions':
            self.catalog_requests += 1
            if self.catalog_requests > 1:
                time.sleep(float(os.environ.get('HARMONY_FIXTURE_CATALOG_DELAY', '0')))
            self.send(dict(kind=kind, items=catalog()))
        elif kind == 'host':
            self.send(dict(kind='host', version='0.12.0-fixture', dshVersion='0.2.2-fixture', cwd='/smoke/harmony', attachedSessions=2))
        elif kind == 'workspaces':
            self.send(dict(kind=kind, items=WORKSPACES))
        elif kind == 'subscribe':
            self.selected = identifier
            if not identifier:
                return
            self.subscription = 'sub-' + RUN_ID + '-' + str(next(IDS))
            self.send(dict(kind='subscribed', sessionId=identifier, subscriptionId=self.subscription, assistantStream=True))
            state = SESSIONS[identifier]
            records = state['events'][-(1005 if os.environ.get('HARMONY_FIXTURE_PERF') == '1' else 8):]
            self.send(self.scoped('session-snapshot', historyFormatVersion=3, cursor=len(state['events']), replace=True,
                events=records, hasMore=len(state['events']) > len(records), nextBeforeSeq=records[0]['seq'] if records else None,
                assistantStream=dict(revision=state['revision'], activeAttempt=None)))
        elif kind == 'history':
            records = [record for record in SESSIONS[identifier]['events'] if record['seq'] < request.get('beforeSeq', 10**9)]
            self.send(dict(kind='history', sessionId=identifier, events=records[-8:], hasMore=len(records) > 8,
                historyFormatVersion=3, nextBeforeSeq=records[-8]['seq'] if len(records) >= 8 else (records[0]['seq'] if records else None)))
        elif kind == 'message':
            if FEATURES: AGENTS.setdefault(identifier, dict(agentPreset='build', locked=False))['locked'] = True
            self.send(dict(kind='sent', sessionId=identifier, requestId=request.get('requestId')))
            if UPLOAD_TEST:
                from upload_fixture import content
                files, images = content(request, identifier)
                event(identifier, dict(type='user/message', text=request['text'], files=files, images=images))
            else: event(identifier, dict(type='user/message', text=request['text']))
            mode = request['text'].strip()
            if mode == 'resolve':
                for rpc, (pending, _) in list(APPROVALS.items()):
                    if pending['sessionId'] == identifier:
                        APPROVALS.pop(rpc)
                        broadcast(dict(kind='approval-resolved', rpcId=rpc, sessionId=identifier))
                event(identifier, dict(type='assistant/message', text='过期审批已移除'))
            elif mode.startswith('question'):
                pending_question(identifier)
            elif mode.startswith('approval'):
                pending_approval(identifier, mode)
            elif mode == 'drop':
                for client in list(CLIENTS):
                    client.request.shutdown(socket.SHUT_RDWR)
            else:
                threading.Thread(target=stream_reply, args=(self, identifier, mode == 'slow'), daemon=True).start()
        elif kind in ('question-answer', 'question-cancel'):
            rpc = request.get('rpcId')
            pending = QUESTIONS.pop(rpc, None)
            if pending:
                if kind == 'question-answer':
                    assert [answer['id'] for answer in request['answers']] == ['single', 'multi', 'text']
                    assert len(request['answers'][0]['selected']) <= 1
                self.send(dict(kind='question-response', rpcId=rpc, sessionId=identifier, accepted=True,
                    action='answer' if kind == 'question-answer' else 'cancel'))
                broadcast(dict(kind='question-resolved', rpcId=rpc, sessionId=identifier))
                event(identifier, dict(type='assistant/message', text='问题回答已接收' if kind == 'question-answer' else '问题已取消'))
        elif kind == 'approval-response':
            rpc = request.get('rpcId')
            pending = APPROVALS.get(rpc)
            if pending and pending[1] == 'approval-timeout':
                return
            self.send(dict(kind='approval-response', rpcId=rpc, sessionId=identifier, outcome=request['outcome'],
                accepted=pending is not None, reason=None if pending else 'not-pending'))
            if pending:
                APPROVALS.pop(rpc)
                broadcast(dict(kind='approval-resolved', rpcId=rpc, sessionId=identifier))
                event(identifier, dict(type='assistant/message', text='已允许此次调用' if request['outcome'] == 'allowed-once' else '已拒绝工具调用'))
        elif kind == 'ping':
            print('manual-ping-response', self.channel, flush=True)
            self.send(dict(kind='pong'))
        elif kind == 'directories':
            path = request.get('path') or '/smoke'
            self.send(dict(kind='directories', path=path, home='/smoke', crumbs=[dict(name='smoke', path='/smoke', hidden=False)],
                entries=[dict(name='harmony', path='/smoke/harmony', hidden=False)] if path == '/smoke' else []))
        elif kind == 'workspace-create':
            path = request.get('path')
            workspace = next((item for item in WORKSPACES if item['path'] == path), None)
            if workspace is None:
                workspace = dict(workspaceId='workspace-' + str(next(IDS)), title=path.rsplit('/', 1)[-1], path=path, sessionIds=[], createdAt='2026-10-30', updatedAt='2026-10-30')
                WORKSPACES.append(workspace)
            self.send(dict(kind='workspace-create', workspace=workspace))
        elif kind == 'search':
            query = request.get('query', '')
            self.send(dict(kind='search', query=query, items=[dict(sessionId=key, snippet=value['title']) for key, value in SESSIONS.items()
                if key not in ARCHIVED and query.lower() in (value['title'] + json.dumps(value['events'], ensure_ascii=False)).lower()]))
        elif kind == 'session-rename':
            SESSIONS[identifier]['title'] = request['title']
            seq = event(identifier, dict(type='session/title', text=request['title']))
            self.send(dict(kind='session-renamed', sessionId=identifier, title=request['title'], seq=seq, time=time.time()))
        elif kind == 'session-archive':
            ARCHIVED.add(identifier)
            broadcast(dict(kind='session-archived', sessionId=identifier, archivedSessionIds=list(ARCHIVED)))
        elif kind == 'session-create':
            identifier = 'smoke-new-' + str(next(IDS))
            session(identifier, '草稿隔离验证')
            workspace = next((row for row in WORKSPACES if row['workspaceId'] == request.get('workspaceId')), None)
            if workspace:
                workspace['sessionIds'].append(identifier)
            self.send(dict(kind='session-created', sessionId=identifier, requestId=request.get('requestId')))
            broadcast(dict(kind='sessions', items=catalog()))
        elif kind == 'session-cancel':
            if identifier in CANCELLED:
                CANCELLED[identifier].set()
            self.send(dict(kind='session-cancelled', sessionId=identifier))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    WORKSPACES.append(dict(workspaceId='workspace-smoke', path='/smoke/harmony', title='本机测试工作区', sessionIds=[], createdAt='2026-10-30', updatedAt='2026-10-30'))
    session('harmony-smoke', LABEL + '鸿蒙原生会话验证')
    if PRODUCTS:
        session('harmony-child', '团队成员甲'); session('harmony-once', '一次性验收任务')
        event('harmony-child', dict(type='assistant/message', turn=1, step=1, text='成员会话的真实历史'))
        event('harmony-once', dict(type='assistant/message', turn=1, step=1, text='一次性任务的只读结果'))
    session('harmony-other', '另一个会话')
    event('harmony-other', dict(type='assistant/message', turn=1, step=1, text='用于验证独立草稿的第二个会话。'))
    for index in range(1, 1001 if os.environ.get('HARMONY_FIXTURE_PERF') == '1' else 25):
        event('harmony-smoke', dict(type='assistant/message', turn=index, step=1, text=LABEL + '历史记录 ' + str(index)))
    if os.environ.get('HARMONY_FIXTURE_PERF') == '1':
        event('harmony-smoke', dict(type='tool/call', name='fixture', callId='p7-call', args={'input': 'read only fixture'}))
        event('harmony-smoke', dict(type='tool/result', name='fixture', callId='p7-call', text='long output\n' * 10000, result='long output\n' * 10000))
        event('harmony-smoke', dict(type='assistant/message', turn=1003, step=1, text='```kotlin\n' + '\n'.join('val row%d = "长代码与 reading"' % n for n in range(1000)) + '\n```'))
        table = '|序号|说明|状态|\n|---|---|---|\n' + '\n'.join('|%d|长表格 第%d行|ready|' % (n,n) for n in range(160))
        event('harmony-smoke', dict(type='assistant/message', turn=1004, step=1, text=table))
        event('harmony-smoke', dict(type='assistant/message', turn=1005, step=1, text='一千条历史加载完成，尾部定位样本'))
    if os.environ.get('HARMONY_FIXTURE_RICH') == '1':
        session('harmony-rich', '富文本验收')
        rich_code = 'val whale = "鲸鱼🐳"\n\tprintln(whale)\n' + '\n'.join('// line ' + str(i) for i in range(1, 29)) + '\n// ' + 'horizontal_' * 18
        event('harmony-rich', dict(type='assistant/message', turn=1, step=1,
            text='# 原生富文本标题\n\n正文 **加粗**、*斜体*、~~删除线~~ 与 `inline`。\n\n> 原生引用\n\n1. 第一项\n2. 第二项\n\n- [x] 已完成\n- [ ] 待完成\n\n---\n\n[官方文档](https://developer.huawei.com)'))
        event('harmony-rich', dict(type='assistant/message', turn=2, step=1, text='```kotlin\n' + rich_code + '\n```'))
        event('harmony-rich', dict(type='assistant/message', turn=3, step=1,
            text='| 名称 | 数量 | 备注 |\n|:---|---:|:---:|\n| 鲸鱼 | 2 | 中文与 long_table_cell_that_wraps |\n| 海豚 | 3 | **粗体** |'))
    print(f'本机测试网关：ws://127.0.0.1:{PORT}/ws/mobile；测试配对码：harmony-smoke', flush=True)
    with Server(('127.0.0.1', PORT), Handler) as server:
        server.serve_forever()
