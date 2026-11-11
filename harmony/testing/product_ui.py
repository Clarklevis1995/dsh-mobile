#!/usr/bin/env python3
"""P2 原生产品回归：两台独立夹具使用相同 session/message ID；不覆盖正式应用。"""
import argparse
import importlib.util
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
spec = importlib.util.spec_from_file_location('smoke', ROOT / 'harmony/testing/smoke_ui.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
folder = Path(tempfile.mkdtemp(prefix='harmony-product-p2-'))
ui = m.UI(folder)
results = []


def passed(task, detail):
    results.append({'task': task, 'detail': detail}); print('PASS', task, detail, flush=True)
    (folder / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))


def back(ready='home-settings'):
    time.sleep(0.8)
    ui.navigate('page-back', ready)


def last_text(value):
    rows = [row for row in ui.nodes() if row.get('text') == value]
    assert rows, '未看到弹层操作：' + value
    box = list(map(int, re.findall(r'-?\d+', rows[-1]['bounds'])))
    m.hdc('shell', 'uitest', 'uiInput', 'click', str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2))
    time.sleep(0.7)


def profile_id(label=None):
    rows = ui.nodes()
    if label:
        assert any(row.get('text') == label for row in rows), '主机别名未显示'
    return next(row['id'].removeprefix('host-select-') for row in rows if row.get('id', '').startswith('host-select-'))


def test_settings():
    ui.wait('home-settings'); ui.screenshot('home-empty')
    ui.navigate('home-settings', 'settings-style'); ui.wait('settings-style'); ui.screenshot('settings-light')
    ui.navigate('settings-style', 'appearance-system'); ui.wait('appearance-dark'); ui.screenshot('appearance-menu-light'); ui.choose('appearance-dark'); ui.wait('settings-language'); assert ui.find('settings-style').get('backgroundColor') == '#FF111720'; ui.screenshot('settings-dark')
    ui.navigate('settings-language', 'language-system'); ui.choose('language-en'); ui.wait('settings-font')
    ui.navigate('settings-font', 'font-follow-system'); ui.wait('font-follow-system')
    if str(ui.find('font-follow-system').get('checked')).lower() == 'true': ui.click('font-follow-system')
    row, x, y = ui.control('font-scale'); bounds = list(map(int, re.findall(r'-?\d+', row['bounds'])))
    m.hdc('shell', 'uitest', 'uiInput', 'click', str(bounds[2] - 7), str(y)); time.sleep(1)
    ui.text('150%'); ui.screenshot('font-150')
    ui.click('font-scale-reset'); ui.text('Standard')
    row, x, y = ui.control('font-scale'); bounds = list(map(int, re.findall(r'-?\d+', row['bounds'])))
    m.hdc('shell', 'uitest', 'uiInput', 'click', str(bounds[2] - 7), str(y)); ui.text('150%')
    m.hdc('shell', 'aa', 'force-stop', m.BUNDLE)
    m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
    ui.wait('home-settings'); ui.navigate('home-settings', 'settings-style'); ui.text('English'); ui.text('150%')
    ui.navigate('settings-style', 'appearance-system'); ui.text('Dark'); ui.screenshot('appearance-selected-dark'); ui.choose('appearance-dark'); ui.wait('settings-language')
    ui.navigate('settings-language', 'language-system'); ui.wait('language-zh'); ui.screenshot('language-selected-en'); ui.choose('language-zh'); ui.wait('settings-font')
    ui.navigate('settings-font', 'font-follow-system'); ui.click('font-follow-system'); back('settings-style')
    ui.navigate('settings-style', 'appearance-system'); ui.choose('appearance-light'); ui.wait('settings-about')
    ui.click('settings-about'); ui.text('0.1.0'); ui.screenshot('about'); back('settings-style')
    ui.click('settings-privacy'); ui.text('系统安全存储'); back('settings-style'); back(); ui.wait('home-settings')
    passed('P2-08', '主题、语言与150%字号在杀进程重启后保留；隐私与真实构建版本可见')


def test_navigation():
    m.connect(ui)
    ui.input('message-draft', 'navigation-draft-A')
    ui.click('conversation-sidebar'); ui.wait('sidebar-search'); ui.screenshot('drawer-conversation')
    ui.input('sidebar-search', '另一个'); ui.click('session-harmony-other'); ui.wait('message-draft')
    assert ui.find('message-draft').get('text', '') == ''
    ui.input('message-draft', 'navigation-draft-B')
    ui.click('conversation-sidebar'); ui.wait('sidebar-search'); ui.input('sidebar-search', '')
    ui.click('session-harmony-smoke'); assert ui.wait('message-draft').get('text') == 'navigation-draft-A'
    ui.click('conversation-sidebar'); ui.wait('sidebar-search'); ui.click('sidebar-dismiss')
    assert not ui.find('sidebar-search'), '点击遮罩后抽屉未关闭'
    ui.click('conversation-sidebar'); ui.wait('sidebar-search')
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back'); time.sleep(.6)
    assert not ui.find('sidebar-search'), '返回键没有先关闭抽屉'
    assert ui.find('message-draft').get('text') == 'navigation-draft-A'
    ui.navigate('conversation-back', 'home-settings'); ui.click('open-sidebar'); ui.wait('sidebar-search'); ui.screenshot('drawer-home')
    ui.click('page-back'); ui.wait('home-settings'); assert not ui.find('sidebar-search')
    passed('P2-03-drawer', '原生覆盖抽屉保持底层页面，选择A/B草稿隔离、遮罩关闭、返回键关闭、首页入口和工具栏关闭均可用')


def test_native_navigation():
    def trace(expected):
        row = ui.wait('test-route-trace')
        assert row.get('text') == expected, row.get('text')
        print('STACK', expected, flush=True)
    def edge(ready, side='left', short=False):
        nodes = ui.nodes()
        boxes = [list(map(int, re.findall(r'-?\d+', n['bounds']))) for n in nodes if len(re.findall(r'-?\d+', n.get('bounds', ''))) == 4]
        width = max(box[2] for box in boxes); height = max(box[3] for box in boxes)
        start = 2 if side == 'left' else width - 2
        end = (10 if side == 'left' else width - 10) if short else int(width * (0.7 if side == 'left' else 0.3))
        # Mate 70 Pro 系统返回热区在屏幕下半部；避开上半部应用内手势区。
        y = int(height * 0.65)
        # UITest 的应用级 swipe 不触发此模拟器系统边缘导航；使用 Input Kit 触摸注入。
        m.hdc('shell', 'uinput', '-T', '-m', str(start), str(y), str(end), str(y), '160')
        time.sleep(.7)
        ui.wait(ready)
    if os.environ.get('HARMONY_NATIVE_START') != 'reading':
        ui.navigate('home-settings', 'settings-style'); trace('settings')
        ui.navigate('settings-font', 'font-follow-system'); trace('settings|settings/font')
        edge('font-follow-system', short=True); trace('settings|settings/font')
        ui.screenshot('gesture-font-canceled')
        edge('settings-style', 'right'); trace('settings')
        edge('home-settings'); trace('<home>')
        passed('native-settings-gesture', '原生两层栈、短边缘尝试未出栈、右侧返回字号、左侧返回首页')
        ui.navigate('home-settings', 'settings-style'); ui.navigate('settings-manage-hosts', 'host-add')
        trace('settings|hosts'); ui.navigate('host-add', 'gateway-endpoint'); trace('settings|hosts|hosts/add')
        edge('host-add'); trace('settings|hosts')
        ui.navigate('host-add', 'gateway-endpoint')
        ui.input('gateway-endpoint', 'ws://127.0.0.1:18785/ws/mobile')
        ui.input('gateway-pairing-code', 'harmony-smoke'); ui.click('gateway-connect')
        assert str(ui.find('gateway-connect').get('enabled')).lower() == 'false'
        edge('gateway-endpoint', short=True); trace('settings|hosts|hosts/add')
        assert str(ui.find('gateway-connect').get('enabled')).lower() == 'false', '短手势错误取消了在途认证'
        edge('host-add', 'right'); trace('settings|hosts')
        until = time.monotonic() + 55
        while 'pair-delay-complete' not in (folder / 'gateway-18785.log').read_text():
            assert time.monotonic() < until, '延迟认证夹具没有完成'
            time.sleep(.5)
        time.sleep(1)
        assert not any(row.get('id', '').startswith('host-select-') for row in ui.nodes()), '侧滑取消后迟到认证重建了主机'
        trace('settings|hosts'); ui.screenshot('native-auth-canceled')
        passed('native-auth-cancel', '在途认证短边缘不取消，右边缘实际出栈取消；延迟配对到达后无新增主机或迟到激活')
        edge('settings-style', 'right'); trace('settings'); edge('home-settings')
        passed('native-host-origin', '主机认证、主机列表侧滑逐级返回真实设置来源')
    m.connect(ui); trace('conversation')
    ui.input('message-draft', 'native-navigation-draft')
    # 分页入口在列表顶部，向较早消息方向滚动；通用 control(scroll=True) 默认查找下方控件。
    for _ in range(8):
        if ui.find('history-older'): break
        ui.scroll(False)
    ui.click('history-older'); ui.scroll(False)
    rows = [row for row in ui.nodes() if row.get('id', '').startswith('message-body-') and '历史记录' in row.get('text', '')]
    anchor = next(row for row in rows if re.findall(r'-?\d+', row['bounds'])[1] == re.findall(r'-?\d+', row.get('origBounds', row['bounds']))[1])
    anchor_id = anchor['id']; anchor_top = int(re.findall(r'-?\d+', anchor['bounds'])[1])
    ui.screenshot('native-reading-before')
    edge('home-settings'); trace('<home>')
    ui.click('鸿蒙原生会话验证', 'text'); ui.wait('message-draft'); trace('conversation')
    assert ui.find('message-draft').get('text') == 'native-navigation-draft'
    restored = ui.wait(anchor_id); restored_top = int(re.findall(r'-?\d+', restored['bounds'])[1])
    assert abs(restored_top - anchor_top) <= 5, (anchor_id, anchor_top, restored_top)
    (folder / 'reading-anchor.json').write_text(json.dumps(dict(id=anchor_id, before=anchor_top, after=restored_top)))
    ui.screenshot('native-reading-after')
    passed('native-reading-anchor', '聊天出栈重建后保留内存草稿和分页历史阅读锚点，位置偏差不超过5px')
    ui.navigate('conversation-tab-trajectory','trajectory-all');trace('conversation|conversation/trajectory');edge('message-draft');trace('conversation')
    assert ui.find('message-draft').get('text') == 'native-navigation-draft'
    passed('native-trajectory-gesture','更多菜单进入轨迹的独立 NavDestination，系统边缘逐级返回聊天且保留草稿')
    ui.navigate('conversation-sidebar', 'sidebar-search')
    edge('message-draft'); assert not ui.find('sidebar-search'); trace('conversation')
    assert ui.find('message-draft').get('text') == 'native-navigation-draft'
    ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-settings', 'settings-style')
    ui.navigate('settings-manage-hosts', 'host-add'); host = profile_id()
    ui.navigate('host-edit-' + host, 'host-alias'); trace('settings|hosts|hosts/edit')
    ui.input('host-alias', '未保存编辑'); edge('host-add'); trace('settings|hosts')
    assert not any(row.get('text') == '未保存编辑' for row in ui.nodes())
    ui.navigate('host-edit-' + host, 'host-alias'); ui.input('host-alias', '原生导航主机')
    ui.click('host-save'); ui.wait('host-add'); trace('settings|hosts'); ui.text('原生导航主机')
    edge('settings-style'); trace('settings'); edge('home-settings', 'right'); trace('<home>')
    ui.click('鸿蒙原生会话验证', 'text'); ui.wait('message-draft')
    passed('native-host-edit', '主机编辑侧滑放弃未保存更改，保存返回原列表，再逐级回设置与首页')
    ui.input('message-draft', 'question'); ui.click('message-send'); ui.wait('conversation-pending-badge')
    ui.navigate('interaction-inbox', 'page-back'); trace('conversation|inbox'); edge('message-draft'); trace('conversation')
    ui.navigate('conversation-back', 'home-settings'); ui.navigate('interaction-inbox', 'page-back'); trace('inbox')
    edge('home-settings', 'right'); trace('<home>')
    passed('native-conversation-gesture', '聊天出栈重入保留草稿，抽屉先关闭，待处理按聊天或首页真实来源返回')
    ui.navigate('workspace-picker', 'workspace-all'); ui.navigate('workspace-browse', 'directory-path'); trace('workspaces|directory')
    edge('workspace-all'); trace('workspaces'); ui.navigate('page-back', 'home-settings'); trace('<home>')
    ui.screenshot('native-navigation-home')
    passed('native-directory-gesture', '目录侧滑回工作区，工具栏返回与系统返回共享原生栈')


def test_hosts_and_workspace():
    ui.navigate('home-auth', 'host-add'); ui.wait('host-add'); ui.click('host-add')
    ui.input('gateway-endpoint', 'ws://127.0.0.1:18785/ws/mobile')
    ui.input('gateway-pairing-code', 'harmony-smoke'); ui.click('gateway-connect'); ui.click('gateway-cancel')
    assert not any(row.get('id', '').startswith('host-select-') for row in ui.nodes())
    back()
    passed('P2-04-cancel', '连接失败／取消后没有新增主机资料或迟到激活')
    m.connect(ui)
    ui.input('message-draft', 'draft-host-A')
    ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-auth', 'host-add'); ui.wait('host-add'); a = profile_id()
    ui.click('host-edit-' + a); ui.input('host-alias', 'P2 主机 A'); ui.click('host-save'); ui.text('P2 主机 A')
    ui.click('host-add'); ui.input('gateway-endpoint', 'ws://127.0.0.1:18784/ws/mobile')
    ui.input('gateway-pairing-code', 'harmony-smoke'); ui.click('gateway-connect'); ui.text('已连接'); ui.click('B 鸿蒙原生会话验证', 'text')
    ui.text('B 历史记录 24'); assert ui.find('message-draft').get('text', '') == ''
    ui.input('message-draft', 'draft-host-B'); ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-auth', 'host-add')
    ids = [row['id'].removeprefix('host-select-') for row in ui.nodes() if row.get('id', '').startswith('host-select-')]
    b = next(value for value in ids if value != a)
    ui.click('host-select-' + a); ui.click('鸿蒙原生会话验证', 'text'); ui.text('历史记录 24')
    assert ui.find('message-draft').get('text') == 'draft-host-A'
    assert not any(row.get('id', '').startswith('message-body-') and row.get('text', '').startswith('B 历史记录') for row in ui.nodes())
    ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-auth', 'host-add'); ui.wait('host-add'); ui.click('host-select-' + b); ui.click('B 鸿蒙原生会话验证', 'text')
    assert ui.find('message-draft').get('text') == 'draft-host-B'; ui.text('B 历史记录 24')
    passed('P2-05', '独立网关相同会话/消息ID：A→B→A→B正文与内存草稿隔离，迟到回包不改当前主机')
    ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-auth', 'host-add'); ui.wait('host-add'); ui.click('host-select-' + a); ui.navigate('home-auth', 'host-add')
    ui.click('host-edit-' + a)
    ui.input('host-addresses', 'ws://127.0.0.1:18785/ws/mobile\nws://127.0.0.1:18783/ws/mobile')
    ui.click('host-save'); ui.wait('host-add'); ui.text('已连接', timeout=25)
    back(); ui.navigate('home-auth', 'host-add'); ui.wait('host-add'); ui.screenshot('hosts'); ui.click('host-remove-' + b); last_text('取消')
    ui.wait('host-select-' + b); ui.click('host-remove-' + b); last_text('移除')
    assert not ui.find('host-select-' + b)
    back(); ui.text('P2 主机 A')
    ui.navigate('home-auth', 'host-add'); ui.wait('host-add'); ui.click('host-edit-' + a)
    ui.input('host-addresses', 'ws://127.0.0.1:18784/ws/mobile'); ui.click('host-save')
    ui.text('网关身份不一致', timeout=20); ui.screenshot('identity-mismatch')
    back(); ui.navigate('home-auth', 'host-add'); ui.wait('host-add'); ui.click('host-edit-' + a)
    ui.input('host-addresses', 'ws://127.0.0.1:18783/ws/mobile'); ui.click('host-save'); ui.wait('host-add'); ui.text('已连接', timeout=20); back()
    passed('P2-04-identity', '错误网关身份被拒绝，原凭据仍可恢复原主机，未静默降级')
    passed('P2-04-part', '主机别名、独立凭据、多地址失败回退、移除取消与确认；迁移另有核心测试')
    test_workspace_and_catalog()


def test_workspace_and_catalog():
    ui.click('workspace-picker'); ui.click('workspace-browse'); ui.wait('directory-harmony')
    ui.click('directory-harmony'); ui.text('/smoke/harmony'); ui.screenshot('directory-picker')
    ui.click('workspace-create'); ui.wait('workspace-picker')
    ui.click('workspace-picker'); ui.click('workspace-ungrouped')
    ui.click('open-sidebar'); ui.input('sidebar-search', '鸿蒙')
    ui.wait('session-harmony-smoke')
    row, x, y = ui.control('session-harmony-smoke')
    m.hdc('shell', 'uitest', 'uiInput', 'longClick', str(x), str(y)); time.sleep(0.7)
    ui.click('重命名', 'text'); ui.input('session-rename-input', 'P2 重命名验证'); ui.click('session-rename-save')
    ui.input('sidebar-search', 'P2 重命名验证'); ui.text('P2 重命名验证'); ui.screenshot('sidebar-renamed')
    row, x, y = ui.control('session-harmony-smoke'); m.hdc('shell', 'uitest', 'uiInput', 'longClick', str(x), str(y)); time.sleep(0.7)
    ui.click('归档', 'text'); last_text('取消'); ui.wait('session-harmony-smoke')
    row, x, y = ui.control('session-harmony-smoke'); m.hdc('shell', 'uitest', 'uiInput', 'longClick', str(x), str(y)); time.sleep(0.7)
    ui.click('归档', 'text'); last_text('归档'); time.sleep(1)
    assert not ui.find('session-harmony-smoke')
    passed('P2-06-part', '目录选择、新建工作区、会话搜索接通协议；真实Host业务结果仍需单独验收')
    passed('P2-07-part', '原生上下文菜单重命名、归档取消与确认；真实Host无恢复接口，恢复保持未完成')


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--scenario', choices=['all', 'hosts', 'settings', 'navigation', 'catalog', 'native-navigation', 'richtext', 'controls', 'slash', 'products', 'uploads', 'cache', 'files', 'goals', 'perf', 'voice', 'reading', 'adaptation', 'photos', 'scan', 'real'], default='all'); parser.add_argument('--app-dir', type=Path); parser.add_argument('--keep-app', action='store_true'); args = parser.parse_args()
    print('REPORT_DIR', folder, flush=True)
    app = args.app_dir or folder / 'app'
    if not args.app_dir:
        shutil.copytree(ROOT / 'harmonyApp', app, ignore=shutil.ignore_patterns('build', '.hvigor', 'oh_modules', '.idea'))
        p = app / 'AppScope/app.json5'; value = json.loads(p.read_text()); value['app']['bundleName'] = m.BUNDLE; p.write_text(json.dumps(value, indent=2))
    assert json.loads((app / 'AppScope/app.json5').read_text())['app']['bundleName'] == m.BUNDLE
    if args.scenario == 'real' and os.environ.get('HARMONY_REAL_SCOPE') in ['history','history-restore','cache']:
        from history_probe import instrument
        instrument(app)
    if args.scenario == 'files' and os.environ.get('HARMONY_FILES_SAVE') == '1':
        from file_actions_probe import instrument
        instrument(app)
    if args.scenario == 'scan' and os.environ.get('HARMONY_SCAN_GALLERY') == '1':
        from scan_gallery_probe import instrument
        instrument(app)
    if args.scenario == 'controls' and os.environ.get('HARMONY_CONTROL_START') == 'notifications':
        from notification_product_probe import instrument
        instrument(app)
    if args.scenario == 'photos':
        from photo_probe import instrument
        instrument(app)
    if args.scenario == 'voice':
        from voice_probe import instrument
        instrument(app)
    if args.scenario == 'adaptation':
        from adaptation_probe import instrument
        instrument(app)
    if args.scenario == 'perf':
        from perf_probe import instrument
        instrument(app, ROOT)
    if args.scenario in ['native-navigation','cache']:
        source = app / 'entry/src/main/ets/pages/Index.ets'
        text = source.read_text()
        marker = "    Column() {\n      if (this.errorText)"
        assert marker in text or ".id('test-route-trace')" in text
        text = text.replace(marker, "    Column() {\n      Text(this.nav.paths.getAllPathName().join('|') || '<home>').id('test-route-trace').fontSize(1).height(1)\n      if (this.errorText)", 1)
        source.write_text(text)
    env = dict(os.environ, DEVECO_SDK_HOME=str(m.DEVECO / 'sdk')); env['PATH'] = str(m.DEVECO / 'tools/node/bin') + ':' + str(m.DEVECO / 'tools/ohpm/bin') + ':' + env['PATH']
    with (folder / 'build.log').open('w') as log:
        subprocess.run([str(m.DEVECO / 'tools/ohpm/bin/ohpm'), 'install', '--all'], cwd=app, env=env, stdout=log, stderr=log, check=True)
        subprocess.run([str(m.DEVECO / 'tools/node/bin/node'), str(m.DEVECO / 'tools/hvigor/bin/hvigorw.js'), '--mode', 'module', '-p', 'module=entry@default', '-p', 'product=default', '-p', 'buildMode=debug', 'assembleHap', '--no-daemon'], cwd=app, env=env, stdout=log, stderr=log, check=True)
    servers = []; logs = []; forwards = []
    try:
        fixtures = [(18783, '', 'a56a1098-8519-43a1-9dce-fb99863bf5bb'), (18784, 'B ', 'b56a1098-8519-43a1-9dce-fb99863bf5bb')]
        if args.scenario == 'real':
            fixtures = []; route = 'tcp:18897'
            if os.environ.get('HARMONY_REAL_SCOPE') in ['transport','generation-network','history','history-restore','cache']:
                import urllib.request,socket
                for proxyPort in [18907,18908]:
                    with socket.socket() as guard:guard.bind(('127.0.0.1',proxyPort))
                proxyLog=(folder/'owned-proxy.log').open('w');logs.append(proxyLog)
                proxy=subprocess.Popen(['/Users/lichaofan/.nvm/versions/node/v24.20.0/bin/node',str(ROOT/'harmony/testing/owned_transport_proxy.cjs')],env=os.environ.copy(),stdout=proxyLog,stderr=proxyLog);servers.append(proxy)
                end=time.monotonic()+10
                while True:
                    try:
                        assert json.load(urllib.request.urlopen('http://127.0.0.1:18908/status',timeout=1))['owned'];break
                    except Exception:
                        if time.monotonic()>end:raise
                        time.sleep(.2)
                route='tcp:18907'
            if route + ' ' + route not in m.hdc('fport','ls'): m.hdc('rport',route,route); forwards.append(route)
        if args.scenario == 'native-navigation': fixtures.append((18785, '延迟 ', 'c56a1098-8519-43a1-9dce-fb99863bf5bb'))
        for port, label, identity in fixtures:
            log = (folder / ('gateway-' + str(port) + '.log')).open('w'); logs.append(log)
            process = subprocess.Popen([sys.executable, str(ROOT / 'harmony/testing/gateway_fixture.py')], env=dict(os.environ, HARMONY_FIXTURE_PORT=str(port), HARMONY_FIXTURE_LABEL=label, HARMONY_FIXTURE_ID=identity, HARMONY_FIXTURE_PAIR_DELAY='45' if port == 18785 else '0', HARMONY_FIXTURE_VOICE_RELEASE=str(folder/'release-transcript'), HARMONY_FIXTURE_VOICE='1' if args.scenario == 'voice' else '0', HARMONY_FIXTURE_PERF='1' if args.scenario in ['perf','reading'] else '0', HARMONY_FIXTURE_FILES='1' if args.scenario == 'files' else '0', HARMONY_FIXTURE_UPLOADS='1' if args.scenario in ['uploads', 'photos'] else '0', HARMONY_FIXTURE_PRODUCTS='1' if args.scenario in ['products', 'goals'] else '0', HARMONY_FIXTURE_SLASH='1' if args.scenario == 'slash' else '0', HARMONY_FIXTURE_FEATURES='1' if args.scenario in ['controls', 'slash', 'products', 'goals'] else '0', HARMONY_FIXTURE_RICH='1' if args.scenario in ['richtext', 'controls'] else '0'), stdout=log, stderr=log); servers.append(process)
            route = 'tcp:' + str(port)
            if route + ' ' + route not in m.hdc('fport', 'ls'): m.hdc('rport', route, route); forwards.append(route)
        time.sleep(0.4); assert all(server.poll() is None for server in servers)
        m.hdc('uninstall', m.BUNDLE); m.hdc('install', str(app / 'entry/build/default/outputs/default/entry-default-unsigned.hap'))
        m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE); time.sleep(2)
        if args.scenario == 'native-navigation': test_native_navigation()
        elif args.scenario == 'real':
            from real_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'adaptation':
            from adaptation_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'reading':
            from reading_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'voice':
            from voice_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'perf':
            from perf_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'goals':
            from goal_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'files':
            from file_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'cache':
            from cache_scenarios import run
            run(ui, m, folder, passed, servers)
        elif args.scenario == 'scan':
            from scan_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'photos':
            from photo_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'uploads':
            from upload_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'products':
            from product_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'slash':
            from slash_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'controls':
            from controls_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'richtext':
            from richtext_scenarios import run
            run(ui, m, folder, passed)
        elif args.scenario == 'navigation': test_navigation()
        elif args.scenario == 'catalog':
            m.connect(ui); ui.navigate('conversation-back', 'home-settings'); test_workspace_and_catalog()
        else:
            if args.scenario != 'hosts': test_settings()
            if args.scenario != 'settings': test_hosts_and_workspace()
        print('DONE', folder, flush=True)
    finally:
        for server in servers: server.terminate(); server.wait(timeout=5)
        for log in logs: log.close()
        for route in forwards: m.hdc('fport', 'rm', route, route)
        if not args.keep_app: m.hdc('uninstall', m.BUNDLE)

if __name__ == '__main__': main()
