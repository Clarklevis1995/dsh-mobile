#!/usr/bin/env python3
"""用独立 smoke 包实际执行 SDK 专项，不与产品 UI 回归并行操作设备。"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import smoke_ui as m


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--probe', choices=['rdb','photo','notification','engine','audio','scan'], required=True); args = parser.parse_args()
    folder = Path(tempfile.mkdtemp(prefix='harmony-platform-' + args.probe + '-')); print('REPORT_DIR', folder, flush=True)
    app = folder / 'app'; shutil.copytree(m.ROOT / 'harmonyApp', app, ignore=shutil.ignore_patterns('build', '.hvigor', 'oh_modules', '.idea'))
    config = app / 'AppScope/app.json5'; data = json.loads(config.read_text()); data['app']['bundleName'] = m.BUNDLE; config.write_text(json.dumps(data))
    shutil.copyfile(m.ROOT / ('harmony/testing/compatibility/' + {'rdb':'RdbProbe.ets','photo':'PhotoProbe.ets','notification':'NotificationProbe.ets','engine':'EngineProbe.ets','audio':'AudioProbe.ets','scan':'ScanProbe.ets'}[args.probe]), app / 'entry/src/main/ets/pages/Index.ets')
    if args.probe == 'photo':
        from generate_photo_fixtures import generate
        generate(app / 'entry/src/main/resources/rawfile')
    if args.probe == 'scan':
        import base64
        def payload(expires):
            value=dict(version=2,publicUrl='ws://127.0.0.1:18783/ws/mobile',pairingCode='harmony-smoke',expiresAt=expires,gatewayId='a56a1098-8519-43a1-9dce-fb99863bf5bb')
            return base64.urlsafe_b64encode(json.dumps(value,separators=(',',':')).encode()).decode().rstrip('=')
        rows=[dict(label='valid pairing',value=payload(time.time()*1000+3600000),valid=True),dict(label='expired pairing',value=payload(time.time()*1000-10000),valid=False),dict(label='invalid pairing',value='not-a-pairing-payload',valid=False)]
        raw=app/'entry/src/main/resources/rawfile';raw.mkdir(exist_ok=True,parents=True);(raw/'scan-fixtures.json').write_text(json.dumps(rows))
        subprocess.run(['swift', str(m.ROOT/'harmony/testing/generate_qr_fixtures.swift'), str(raw/'scan-fixtures.json'), str(raw/'scan-images')], check=True)
    done = 'DONE ' + args.probe.upper() + ' probe'
    env = dict(os.environ, DEVECO_SDK_HOME=str(m.DEVECO / 'sdk')); env['PATH'] = str(m.DEVECO / 'tools/node/bin') + ':' + str(m.DEVECO / 'tools/ohpm/bin') + ':' + env['PATH']
    with (folder / 'build.log').open('w') as log:
        subprocess.run([str(m.DEVECO / 'tools/ohpm/bin/ohpm'), 'install', '--all'], cwd=app, env=env, stdout=log, stderr=log, check=True)
        subprocess.run([str(m.DEVECO / 'tools/node/bin/node'), str(m.DEVECO / 'tools/hvigor/bin/hvigorw.js'), '--mode', 'module', '-p', 'module=entry@default', '-p', 'product=default', '-p', 'buildMode=debug', 'assembleHap', '--no-daemon'], cwd=app, env=env, stdout=log, stderr=log, check=True)
    ui = m.UI(folder)
    try:
        m.hdc('uninstall', m.BUNDLE); m.hdc('install', str(app / 'entry/build/default/outputs/default/entry-default-unsigned.hap'))
        m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
        if args.probe in ['notification','audio']:
            if args.probe == 'notification':
                from notification_probe_ui import run
            else:
                from audio_probe_ui import run
            lines = run(ui, m, folder)
            (folder/'results.json').write_text(json.dumps(dict(method='F actual ' + args.probe + ' SDK on isolated API21 emulator', lines=lines), ensure_ascii=False, indent=2))
            print('DONE',folder,flush=True); return
        end = time.monotonic() + 65; lines = []
        while time.monotonic() < end:
            lines = [row.get('text', '') for row in ui.nodes() if row.get('text', '')]
            if any(line.startswith('FAIL') for line in lines) or done in lines: break
            time.sleep(.6)
        ui.screenshot(args.probe + '-probe')
        (folder / 'results.json').write_text(json.dumps(dict(method='F actual ' + args.probe + ' SDK on API21 ARM64 emulator', lines=lines), ensure_ascii=False, indent=2))
        assert done in lines and not any(line.startswith('FAIL') for line in lines), lines
        print('DONE', folder, flush=True)
    finally:
        m.hdc('shell', 'aa', 'force-stop', m.BUNDLE); m.hdc('uninstall', m.BUNDLE)


if __name__ == '__main__': main()
