#!/usr/bin/env python3
"""构建隔离包并通过 HDC 操作真实 ArkUI；只使用本机夹具数据。"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
DEVECO = Path(os.environ.get('DEVECO_STUDIO_PATH', '/Applications/DevEco-Studio.app/Contents'))
HDC = str(DEVECO / 'sdk/default/openharmony/toolchains/hdc')
BUNDLE = 'com.clarklevis.whaleness.harmony.smoke'
TARGET = os.environ.get('HARMONY_TEST_TARGET', '127.0.0.1:5559')


def hdc(*args):
    command = [HDC, '-t', TARGET, *args]
    output = subprocess.check_output(command, text=True)
    # HDC 在设备离线等失败时仍可能返回 0；不能继续读取上次遗留的布局。
    if re.search(r'^\[Fail\]', output, re.MULTILINE):
        raise subprocess.CalledProcessError(1, command, output=output)
    return output


def require_device():
    targets = subprocess.check_output([HDC, 'list', 'targets'], text=True).splitlines()
    if TARGET not in {line.strip() for line in targets}:
        raise RuntimeError('验收设备未连接：' + TARGET + '；先恢复原模拟器，再重跑，不计为业务验收结果')


class UI:
    def __init__(self, folder):
        self.folder = folder

    def nodes(self, system=False, include_offscreen=False):
        remote = '/data/local/tmp/whaleness-system.json' if system else '/data/local/tmp/whaleness-smoke.json'
        args = ['shell', 'uitest', 'dumpLayout', '-p', remote]
        if not system: args += ['-b', BUNDLE]
        hdc(*args)
        destination = self.folder / ('system-layout.json' if system else 'layout.json')
        hdc('file', 'recv', remote, str(destination))
        result = []
        def walk(node):
            if isinstance(node, dict):
                if 'attributes' in node:
                    result.append(node['attributes'])
                for key, value in node.items():
                    if key != 'attributes':
                        walk(value)
            elif isinstance(node, list):
                for child in node:
                    walk(child)
        walk(json.loads(destination.read_text()))
        if include_offscreen: return result
        visible = []
        for row in result:
            box = list(map(int, re.findall(r'-?\d+', row.get('bounds', ''))))
            if str(row.get('visible')).lower() != 'false' and (len(box) != 4 or (box[2] > box[0] and box[3] > box[1])):
                visible.append(row)
        return visible

    def find(self, value, key='id'):
        # 合并布局按底层到上层排列；覆盖抽屉与底层可有同名业务节点。
        return next((row for row in reversed(self.nodes()) if row.get(key) == value), None)

    def wait(self, value, key='id', timeout=12):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            row = self.find(value, key)
            if row:
                return row
            time.sleep(0.3)
        raise AssertionError('未找到控件：' + value)

    def scroll(self, down):
        rows = self.nodes()
        row = next((row for row in rows if row.get('id') == 'conversation-list'), None) or next(row for row in rows if row.get('type') in ['Scroll', 'List'])
        left, top, right, bottom = map(int, re.findall(r'-?\d+', row['bounds']))
        x = (left + right) // 2
        a, b = top + (bottom - top) // 4, top + (bottom - top) * 3 // 4
        hdc('shell', 'uitest', 'uiInput', 'swipe', str(x), str(b if down else a), str(x), str(a if down else b), '4000')
        time.sleep(0.4)

    def control(self, value, key='id', scroll=False):
        if key == 'id' and value.startswith('settings-') and not self.find(value, key): scroll = True
        if key == 'id' and value in ['conversation-controls','conversation-files','conversation-team','conversation-tab-trajectory','interaction-inbox'] and not self.find(value,key) and self.find('conversation-overflow'):
            self.click('conversation-overflow')
        for _ in range(8):
            row = self.find(value, key)
            if row is None and not scroll:
                row = self.wait(value, key)
            if row:
                bounds = list(map(int, re.findall(r'-?\d+', row['bounds'])))
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    original = list(map(int, re.findall(r'-?\d+', row.get('origBounds', row['bounds']))))
                    if scroll and (original[1] < bounds[1] or original[3] > bounds[3]):
                        self.scroll(original[3] > bounds[3])
                        continue
                    return row, (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2
            if not scroll:
                break
            hidden = next((r for r in reversed(self.nodes(include_offscreen=True)) if r.get(key) == value), None)
            viewport = next((r for r in self.nodes() if r.get('type') in ['Scroll','List']), None)
            original = list(map(int,re.findall(r'-?\d+', hidden.get('origBounds',hidden.get('bounds',''))))) if hidden else []
            bounds = list(map(int,re.findall(r'-?\d+',viewport.get('bounds','')))) if viewport else []
            self.scroll(not (len(original)==4 and len(bounds)==4 and original[1]<bounds[1]))
        raise AssertionError('控件不可点击：' + value)

    def click(self, value, key='id', scroll=False):
        print('操作 ' + value, flush=True)
        row, x, y = self.control(value, key, scroll)
        assert str(row.get('enabled')).lower() != 'false', '控件尚不可用：' + value
        if row.get('type') == 'MenuItem':
            # 原生弹出动画期间已能读到节点，但其可点击区域还在变化。
            time.sleep(0.8)
            row, x, y = self.control(value, key, scroll)
        hdc('shell', 'uitest', 'uiInput', 'click', str(x), str(y))
        time.sleep(0.5)

    def navigate(self, value, ready):
        # 仅用于无副作用的页面／菜单入口，不用于发送、认证、保存或确认。
        for attempt in range(2):
            self.click(value)
            try:
                if ready.startswith('settings-'): return self.control(ready, scroll=True)[0]
                return self.wait(ready, timeout=3)
            except AssertionError:
                if attempt or value in ['page-back','conversation-back'] or not self.find(value):
                    raise
                print('导航帧未就绪，重新确认原入口后再点击：' + value, flush=True)
                time.sleep(0.8)

    def choose(self, value):
        assert value.startswith(('appearance-', 'language-'))
        for attempt in range(2):
            self.click(value)
            until = time.monotonic() + 3
            while time.monotonic() < until:
                if not self.find(value):
                    return
                time.sleep(0.3)
            if attempt:
                raise AssertionError('偏好菜单选择后未关闭：' + value)
            print('偏好菜单仍存在，重新读取节点后重试同值选择：' + value, flush=True)

    def input(self, value, text, scroll=False, dismiss=True):
        print('输入 ' + value, flush=True)
        # 只编辑本次回归拥有的输入框；清空和提交均回读，避免一次 Ctrl+A 丢失后拼接旧稿。
        password = value == 'gateway-pairing-code'
        cleared = False
        for attempt in range(3):
            _, x, y = self.control(value, scroll=scroll)
            hdc('shell', 'uitest', 'uiInput', 'click', str(x), str(y))
            until = time.monotonic() + 8
            while time.monotonic() < until:
                row = self.find(value)
                if row and str(row.get('focused')).lower() == 'true': break
                time.sleep(.3)
            else: raise AssertionError('输入框未获得焦点：' + value)
            hdc('shell', 'uitest', 'uiInput', 'keyEvent', '2072', '2017')
            hdc('shell', 'uitest', 'uiInput', 'keyEvent', '2055')
            if password: time.sleep(.5); cleared = True; break
            until = time.monotonic() + 4
            while time.monotonic() < until:
                row = self.find(value)
                if row and row.get('text', '') == '': cleared = True; break
                time.sleep(.3)
            if cleared: break
            print('全选未清空，确认仍在同一输入框后重新清空：' + value, flush=True)
        assert cleared, '输入框未清空：' + value
        parts = text.split('\n')
        hdc('shell', 'uitest', 'uiInput', 'text', parts[0])
        for part in parts[1:]:
            hdc('shell', 'uitest', 'uiInput', 'keyEvent', '2054')
            hdc('shell', 'uitest', 'uiInput', 'text', part)
        if not password:
            until = time.monotonic() + 12
            while time.monotonic() < until:
                row = self.find(value)
                if row and row.get('text', '') == text: break
                time.sleep(.4)
            else: raise AssertionError('输入法提交未完成：' + value)
        else: time.sleep(2.5)
        if dismiss: hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
        time.sleep(.4)
        if not password: assert self.find(value).get('text', '') == text, '输入文本未完整替换：' + value

    def text(self, value, timeout=12):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            if any(value in (row.get('text', '') + row.get('accessibilityText', '') + row.get('description', '')) for row in self.nodes()):
                return
            time.sleep(0.4)
        raise AssertionError('未看到文本：' + value)

    def screenshot(self, name):
        remote = '/data/local/tmp/whaleness-' + name + '.png'
        hdc('shell', 'uitest', 'screenCap', '-p', remote)
        hdc('file', 'recv', remote, str(self.folder / (name + '.png')))

    def message(self, text):
        self.input('message-draft', text)
        self.click('message-send')
        until = time.monotonic() + 5
        while time.monotonic() < until:
            row = self.find('message-draft')
            assert row is not None, '发送后输入区消失，请核实独立包进程与当前输入模式'
            if row.get('text', '') == '':
                return
            time.sleep(0.2)
        raise AssertionError('发送确认未清空原草稿')


def question_rpc(ui):
    for row in ui.nodes():
        identifier = row.get('id', '')
        if identifier.startswith('question-submit-'):
            return identifier.removeprefix('question-submit-')
        if identifier.startswith('question-option-'):
            return identifier.removeprefix('question-option-').rsplit('-', 2)[0]
        if identifier.startswith('question-custom-'):
            return identifier.removeprefix('question-custom-').rsplit('-', 1)[0]
    raise AssertionError('未看到问题表单')

def connect(ui):
    if not ui.find('gateway-endpoint'):
        ui.wait('home-auth', timeout=18)
        ui.navigate('home-auth', 'host-add')
        ui.navigate('host-add', 'gateway-endpoint')
    ui.input('gateway-endpoint', 'ws://127.0.0.1:18783/ws/mobile')
    ui.input('gateway-pairing-code', 'harmony-smoke')
    ui.click('gateway-connect')
    ui.text('已连接', timeout=35)
    ui.wait('鸿蒙原生会话验证', 'text')
    ui.click('鸿蒙原生会话验证', 'text')


def run_timeout(ui):
    ui.message('approval-timeout')
    ui.click('interaction-inbox')
    rows = ui.nodes()
    control = next(row['id'] for row in rows if row.get('id', '').startswith('approval-allow-'))
    count = (ui.folder / 'gateway.log').read_text().count('request approval-response')
    ui.click(control, scroll=True)
    assert str(ui.find(control).get('enabled')).lower() == 'false'
    ui.text('请求失败或超时，结果尚未确认，请核对后重试', timeout=26)
    assert str(ui.find(control).get('enabled')).lower() == 'true'
    assert (ui.folder / 'gateway.log').read_text().count('request approval-response') == count + 1
    ui.screenshot('approval-timeout')
    ui.click('interaction-inbox')
    ui.message('resolve')
    ui.text('过期审批已移除')
    print('PASS 审批超时可见、禁止自动重试、服务端解决后移除', flush=True)


def run(ui):
    connect(ui)
    ui.text('历史记录 24')
    for _ in range(4):
        ui.scroll(False)
    ui.click('history-older')
    for _ in range(4): ui.scroll(False)
    ui.text('历史记录 9')
    print('PASS 配对、双通道、快照、历史分页', flush=True)
    ui.input('message-draft', 'draft-A')
    hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
    time.sleep(0.4)
    ui.click('另一个会话', 'text')
    assert ui.find('message-draft').get('text', '') == ''
    ui.input('message-draft', 'draft-B')
    hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
    time.sleep(0.4)
    ui.click('鸿蒙原生会话验证', 'text')
    assert ui.find('message-draft').get('text') == 'draft-A'
    ui.message('hello')
    ui.text('流式回复验证完成：Kotlin 共享状态机与鸿蒙原生界面工作正常。')
    time.sleep(0.3)
    print('PASS 会话草稿隔离、发送确认、流式输出', flush=True)
    ui.message('slow')
    partials = set()
    until = time.monotonic() + 7
    while time.monotonic() < until and len(partials) < 2:
        for row in ui.nodes():
            text = row.get('text', '')
            if row.get('id', '').startswith('message-body-') and text.startswith('流式') and len(text) < len('流式回复验证完成：Kotlin 共享状态机与鸿蒙原生界面工作正常。'):
                partials.add(text)
        time.sleep(0.2)
    assert len(partials) >= 2, '未观察到稳定消息 ID 的连续流式更新'
    ui.click('停止', 'text')
    ui.text('已停止')
    print('PASS 停止生成', flush=True)
    ui.message('question')
    ui.click('interaction-inbox')
    rows = ui.nodes()
    rpc = question_rpc(ui)
    ui.click('question-option-' + rpc + '-0-0', scroll=True)
    ui.click('question-option-' + rpc + '-1-0', scroll=True)
    ui.click('question-option-' + rpc + '-1-1', scroll=True)
    ui.input('question-custom-' + rpc + '-2', 'harmony-note', scroll=True)
    ui.screenshot('question')
    ui.click('question-submit-' + rpc, scroll=True)
    ui.text('当前没有待处理事项')
    ui.click('interaction-inbox')
    ui.text('问题回答已接收')
    print('PASS 多题回答：单选、多选、自定义答案', flush=True)
    ui.message('question')
    ui.click('interaction-inbox')
    rows = ui.nodes()
    rpc = question_rpc(ui)
    ui.click('question-cancel-' + rpc, scroll=True)
    ui.text('当前没有待处理事项')
    ui.click('interaction-inbox')
    ui.text('问题已取消')
    print('PASS 取消回答', flush=True)
    for outcome, prefix, expected in [('allow', 'approval-allow-', '已允许此次调用'), ('reject', 'approval-reject-', '已拒绝工具调用')]:
        ui.message('approval-' + outcome)
        ui.click('interaction-inbox')
        ui.text('echo harmony-approval')
        ui.screenshot('approval-' + outcome)
        rows = ui.nodes()
        control = next(row['id'] for row in rows if row.get('id', '').startswith(prefix))
        ui.click(control, scroll=True)
        ui.text('当前没有待处理事项')
        ui.click('interaction-inbox')
        ui.text(expected)
    print('PASS 工具参数与原因展示、单次允许、拒绝', flush=True)
    run_timeout(ui)
    run_recovery(ui)


def run_recovery(ui):
    ui.input('message-draft', 'foreground-draft')
    hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Home')
    time.sleep(1)
    hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', BUNDLE)
    ui.text('已连接')
    assert ui.wait('message-draft').get('text') == 'foreground-draft'
    connected_before = (ui.folder / 'gateway.log').read_text().count('connected ')
    message_count = (ui.folder / 'gateway.log').read_text().count('request message')
    ui.input('message-draft', 'drop')
    ui.click('message-send')
    time.sleep(2)
    ui.text('已连接', timeout=15)
    assert (ui.folder / 'gateway.log').read_text().count('connected ') >= connected_before + 2
    ui.text('drop')
    assert (ui.folder / 'gateway.log').read_text().count('request message') == message_count + 1, '恢复后不应自动重发消息'
    print('PASS 前后台恢复、断线重连、保留草稿', flush=True)
    ui.screenshot('conversation')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--app-dir', type=Path, help='使用已有隔离工程；仍会执行编译')
    parser.add_argument('--keep-app', action='store_true')
    parser.add_argument('--scenario', choices=['all', 'timeout', 'recovery'], default='all')
    args = parser.parse_args()
    folder = Path(tempfile.mkdtemp(prefix='whaleness-smoke-'))
    app = args.app_dir or folder / 'app'
    print('测试产物：' + str(folder), flush=True)
    if not args.app_dir:
        shutil.copytree(ROOT / 'harmonyApp', app, ignore=shutil.ignore_patterns('build', '.hvigor', 'oh_modules', '.idea'))
        profile = app / 'AppScope/app.json5'
        data = json.loads(profile.read_text())
        data['app']['bundleName'] = BUNDLE
        profile.write_text(json.dumps(data, indent=2))
    assert json.loads((app / 'AppScope/app.json5').read_text())['app']['bundleName'] == BUNDLE, '拒绝覆盖真实应用'
    env = dict(os.environ, DEVECO_SDK_HOME=str(DEVECO / 'sdk'))
    env['PATH'] = str(DEVECO / 'tools/node/bin') + ':' + str(DEVECO / 'tools/ohpm/bin') + ':' + env['PATH']
    with (folder / 'build.log').open('w') as log:
        subprocess.run([str(DEVECO / 'tools/ohpm/bin/ohpm'), 'install', '--all'], cwd=app, env=env, stdout=log, stderr=log, check=True)
        subprocess.run([str(DEVECO / 'tools/node/bin/node'), str(DEVECO / 'tools/hvigor/bin/hvigorw.js'), '--mode', 'module', '-p', 'module=entry@default', '-p', 'product=default', '-p', 'buildMode=debug', 'assembleHap', '--no-daemon'], cwd=app, env=env, stdout=log, stderr=log, check=True)
    log = (folder / 'gateway.log').open('w')
    fixture = subprocess.Popen([sys.executable, str(ROOT / 'harmony/testing/gateway_fixture.py')], stdout=log, stderr=log)
    forwarded = False
    installed = False
    try:
        time.sleep(0.3)
        assert fixture.poll() is None, '测试网关启动失败'
        existing = hdc('fport', 'ls')
        if 'tcp:18783 tcp:18783' not in existing:
            hdc('rport', 'tcp:18783', 'tcp:18783')
            forwarded = True
        hdc('uninstall', BUNDLE)
        hdc('install', str(app / 'entry/build/default/outputs/default/entry-default-unsigned.hap'))
        installed = True
        hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', BUNDLE)
        time.sleep(1)
        ui = UI(folder)
        if args.scenario == 'timeout':
            connect(ui)
            run_timeout(ui)
        elif args.scenario == 'recovery':
            connect(ui)
            ui.message('hello')
            ui.text('流式回复验证完成：Kotlin 共享状态机与鸿蒙原生界面工作正常。')
            run_recovery(ui)
        else:
            run(ui)
        assert 'Traceback' not in (folder / 'gateway.log').read_text()
        print('全部模拟器回归通过', flush=True)
    finally:
        fixture.terminate()
        fixture.wait(timeout=5)
        log.close()
        if forwarded:
            hdc('fport', 'rm', 'tcp:18783', 'tcp:18783')
        if installed and not args.keep_app:
            hdc('uninstall', BUNDLE)


if __name__ == '__main__':
    main()
