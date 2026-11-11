#!/usr/bin/env python3
# coding: utf-8
"""仅更新已有证据确认的独立候选；真实旧包建草稿，再原位升级，不清资料。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time
import uuid
import smoke_ui as m


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--previous-result', required=True, type=Path)
    parser.add_argument('--version-code', required=True, type=int)
    args = parser.parse_args()
    previous = json.loads(args.previous_result.read_text())
    m.BUNDLE = previous['bundle']
    assert re.fullmatch(r'com\.clarklevis\.whaleness\.harmony\.preview[0-9a-f]+', m.BUNDLE)
    assert args.version_code > previous['versionCode']
    assert os.environ.get('HARMONY_TEST_TARGET') == previous['target']
    def version():
        dump = m.hdc('shell', 'bm', 'dump', '-n', m.BUNDLE)
        info = json.loads(dump[dump.index('{'):])
        assert info['name'] == m.BUNDLE
        return int(info['versionCode'])
    assert version() == previous['versionCode'], '候选版本已变化，必须先核对新证据'
    with socket.socket() as guard:
        guard.bind(('127.0.0.1', 18783))
    folder = Path(tempfile.mkdtemp(prefix='harmony-candidate-refresh-'))
    print('REPORT_DIR', folder, flush=True)
    app = folder / 'app'
    shutil.copytree(m.ROOT / 'harmonyApp', app, ignore=shutil.ignore_patterns('build', '.hvigor', 'oh_modules', '.idea'))
    value = json.loads((app / 'AppScope/app.json5').read_text())
    value['app'].update(bundleName=m.BUNDLE, versionCode=args.version_code, versionName='0.1.0-preview')
    (app / 'AppScope/app.json5').write_text(json.dumps(value, indent=2))
    resource = app / 'AppScope/resources/base/element/string.json'
    strings = json.loads(resource.read_text())
    for row in strings['string']:
        if row['name'] == 'app_name': row['value'] = 'Whaleness 鸿蒙候选'
    resource.write_text(json.dumps(strings, ensure_ascii=False, indent=2))
    manifest = []
    for path in sorted((app / 'entry/src/main').rglob('*')):
        if path.is_file() and path.suffix in ['.ets', '.cpp', '.h', '.so']:
            relative = path.relative_to(app)
            assert path.read_bytes() == (m.ROOT / 'harmonyApp' / relative).read_bytes()
            if path.suffix == '.ets':
                assert not any(marker in path.read_text() for marker in ['test-p7-stats', 'test-history-projection', 'test-route-trace', 'test-notification-inspect', 'PerfStats'])
            manifest.append(dict(path=str(relative), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    env = dict(os.environ, DEVECO_SDK_HOME=str(m.DEVECO / 'sdk'))
    env['PATH'] = str(m.DEVECO / 'tools/node/bin') + ':' + str(m.DEVECO / 'tools/ohpm/bin') + ':' + env['PATH']
    with (folder / 'build.log').open('w') as log:
        subprocess.run([str(m.DEVECO / 'tools/ohpm/bin/ohpm'), 'install', '--all'], cwd=app, env=env, stdout=log, stderr=log, check=True)
        subprocess.run([str(m.DEVECO / 'tools/node/bin/node'), str(m.DEVECO / 'tools/hvigor/bin/hvigorw.js'), '--mode', 'module', '-p', 'module=entry@default', '-p', 'product=default', '-p', 'buildMode=debug', 'assembleHap', '--no-daemon'], cwd=app, env=env, stdout=log, stderr=log, check=True)
    ui = m.UI(folder)
    log = (folder / 'fixture.log').open('w')
    server = subprocess.Popen([os.sys.executable, str(m.ROOT / 'harmony/testing/gateway_fixture.py')], env=dict(os.environ, HARMONY_FIXTURE_PORT='18783', HARMONY_FIXTURE_ID=str(uuid.uuid4())), stdout=log, stderr=log)
    added = False
    profile = None
    route = 'tcp:18783'
    try:
        time.sleep(.5); assert server.poll() is None
        if route + ' ' + route not in m.hdc('fport', 'ls'):
            m.hdc('rport', route, route); added = True
        m.hdc('shell', 'aa', 'force-stop', m.BUNDLE)
        m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
        ui.wait('home-settings')
        ui.navigate('home-auth', 'host-add')
        existing = {r['id'] for r in ui.nodes() if r.get('id', '').startswith('host-select-')}
        ui.navigate('page-back', 'home-settings')
        m.connect(ui)
        draft = '候选源码升级草稿 ' + uuid.uuid4().hex[:8]
        ui.input('message-draft', draft); ui.text('历史记录 24'); time.sleep(1.5)
        ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-auth', 'host-add')
        created = {r['id'] for r in ui.nodes() if r.get('id', '').startswith('host-select-')} - existing
        assert len(created) == 1; profile = created.pop().removeprefix('host-select-')
        m.hdc('shell', 'aa', 'force-stop', m.BUNDLE)
        m.hdc('install', '-r', str(app / 'entry/build/default/outputs/default/entry-default-unsigned.hap'))
        assert version() == args.version_code
        m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
        ui.wait('home-settings'); ui.text('已连接', timeout=35)
        ui.click('session-harmony-smoke', scroll=True); ui.wait('message-draft')
        assert ui.find('message-draft')['text'] == draft
        ui.text('历史记录 24'); ui.screenshot('candidate-source-upgrade-preserved')
        ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-auth', 'host-add')
        ui.click('host-remove-' + profile); ui.wait('confirmation-accept'); ui.click('confirmation-accept')
        assert not ui.find('host-select-' + profile); profile = None
        assert {r['id'] for r in ui.nodes() if r.get('id', '').startswith('host-select-')} == existing
        ui.navigate('page-back', 'home-settings'); ui.screenshot('candidate-refreshed-home')
        destination = m.ROOT / 'harmonyApp/build/acceptance'
        destination.mkdir(parents=True, exist_ok=True)
        final = destination / (m.BUNDLE + '-v' + str(args.version_code) + '-unsigned.hap')
        assert not final.exists(), '禁止覆盖已有历史产物'
        shutil.copy2(app / 'entry/build/default/outputs/default/entry-default-unsigned.hap', final)
        result = dict(bundle=m.BUNDLE, target=m.TARGET, previousVersionCode=previous['versionCode'], versionCode=args.version_code, versionName='0.1.0-preview', path=str(final), sha256=hashlib.sha256(final.read_bytes()).hexdigest(), bytes=final.stat().st_size, productionSourceWithoutHooks=True, sourceCheckpoint=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=m.ROOT, text=True).strip(), inPlaceSourceUpdate=True, assetIdentityRetained=True, draftHistoryRetained=True, ownedTestHostRemoved=True, existingProfilesUntouched=True, signed=False)
        (folder / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        (folder / 'source-manifest.json').write_text(json.dumps(dict(comparedFiles=len(manifest), files=manifest), indent=2))
        print('PASS CANDIDATE_REFRESH', m.BUNDLE, flush=True)
    finally:
        # 在成功路径已通过产品移除本轮唯一Host；失败时仍保留profile标识便于定向清理，绝不删除全部主机。
        if profile: print('OWNED_PROFILE_REQUIRES_CLEANUP', profile, flush=True)
        server.terminate(); server.wait(timeout=6); log.close()
        if added: m.hdc('fport', 'rm', route, route)
    print('DONE', folder, flush=True)


if __name__ == '__main__': main()
