#!/usr/bin/env python3
# coding: utf-8
"""重启专属验收观察器，保留本轮会话归属；禁止操作用户网关。"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.request

from start_isolated_acceptance import GATEWAY, NODE, ROOT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    args = parser.parse_args()
    home = args.root.resolve()
    meta = json.loads((home / 'owned-processes.json').read_text())
    workspace = home / 'workspace'
    assert home.name.startswith('harmony-p7-owned-host-')
    assert Path(meta['root']).resolve() == home and Path(meta['workspace']).resolve() == workspace
    assert meta['port'] == 18897 and meta['observerPort'] == 18899
    pid = int(meta['observerPid'])
    command = subprocess.check_output(['ps', '-p', str(pid), '-o', 'command='], text=True)
    assert str(ROOT / 'harmony/testing/live_gateway_control.cjs') in command
    cwd = subprocess.check_output(['lsof', '-a', '-p', str(pid), '-d', 'cwd', '-Fn'], text=True)
    assert any(Path(line[1:]).resolve() == workspace for line in cwd.splitlines() if line.startswith('n'))
    assert os.getpgid(pid) == pid
    with urllib.request.urlopen('http://127.0.0.1:18899/status', timeout=5) as response:
        state = json.load(response)
    assert state['gatewayVersion'] == meta['gatewayVersion'] == '0.12.0'
    seed = home / 'configuration-observer-seed.json'
    seed.write_text(json.dumps(dict(sessions=[dict(id=row['id'], name=row['name']) for row in state['sessions']]), ensure_ascii=False))
    os.killpg(pid, signal.SIGTERM)
    end = time.monotonic() + 15
    while time.monotonic() < end:
        listener = subprocess.run(['lsof', '-t', '-iTCP:18899', '-sTCP:LISTEN'], capture_output=True, text=True)
        if not listener.stdout.strip():
            break
        time.sleep(.2)
    else:
        raise AssertionError('原专属观察器尚未退出')
    env = dict(os.environ, HARMONY_GATEWAY_PACKAGE=str(GATEWAY), HARMONY_LIVE_HTTP='http://127.0.0.1:18897', HARMONY_LIVE_PORT='18899', HARMONY_OWNED_REAL_ROOT=str(home), HARMONY_LIVE_SEED=str(seed))
    with (home / 'observer.log').open('a') as log:
        process = subprocess.Popen([str(NODE), str(ROOT / 'harmony/testing/live_gateway_control.cjs')], cwd=workspace, env=env, stdout=log, stderr=log, start_new_session=True)
    meta['observerPid'] = process.pid
    (home / 'owned-processes.json').write_text(json.dumps(meta, indent=2))
    end = time.monotonic() + 35
    while time.monotonic() < end:
        if process.poll() is not None:
            raise AssertionError('专属观察器提前退出')
        try:
            with urllib.request.urlopen('http://127.0.0.1:18899/status', timeout=2) as response:
                state = json.load(response)
            assert state['gatewayVersion'] == '0.12.0'
            print('OWNED_OBSERVER_RESTARTED', len(state['sessions']), flush=True)
            return
        except (OSError, AssertionError):
            time.sleep(.3)
    raise AssertionError('新专属观察器未就绪')


if __name__ == '__main__':
    main()
