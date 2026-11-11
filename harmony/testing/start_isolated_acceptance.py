#!/usr/bin/env python3
"""只在空闲 18897／18899 创建本轮独占真实 Host；不访问用户的 3080／3081／3082。"""
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
NODE = Path('/Users/lichaofan/.nvm/versions/node/v24.20.0/bin/node')
PACKAGE = NODE.parent.parent / 'lib/node_modules/@deepseek-ai/dsh'
GATEWAY = Path('/Users/lichaofan/DeepseekHarnessProject/dsh-plugin-mobile-gateway')
PORT = 18897
OBSERVER_PORT = 18899


def wait(url, timeout=60):
    until = time.monotonic()+timeout
    while time.monotonic()<until:
        try:
            with urllib.request.urlopen(url,timeout=2) as response: return json.load(response)
        except Exception: time.sleep(.5)
    raise AssertionError('专属真实 Host 未按时就绪')


def main():
    for port in [PORT,OBSERVER_PORT]:
        with socket.socket() as guard: guard.bind(('127.0.0.1',port))
    home=Path(tempfile.mkdtemp(prefix='harmony-p7-owned-host-'));workspace=home/'workspace';workspace.mkdir();profile=home/'profiles/web';profile.mkdir(parents=True)
    (workspace/'鸿蒙验收报告.md').write_text('# 真实工作区报告\n\n此文件只在本轮临时主机中存在。\n\n```kotlin\nval result = "真实网关"\n```\n\n| 项目 | 状态 |\n| --- | --- |\n| 原生下载 | ready |\n')
    (profile/'package.json').write_text(json.dumps({'name':'dsh-profile-web','private':True,'dependencies':{},'dsh':{'profile':{'bundles':['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app']}}},indent=2))
    (profile/'cordis.yml').write_text('[]\n')
    (profile/'cordis.patch.yml').write_text('''- id: agent-default-model
  config:
    provider: harmony-controlled
    model: harmony-controlled
- id: desktop-product-telemetry
  disabled: true
- id: product-analytics
  disabled: true
- insert:
    - id: harmony-controlled-upstream
      name: '''+json.dumps(str(ROOT/'harmony/testing/isolated_model.mjs'))+'''
    - id: harmony-mobile-gateway
      name: '''+json.dumps(str(GATEWAY/'lib/index.mjs'))+'''
      config:
        gatewayEnabled: true
        requireAuth: true
        gatewayMode: persistent
        gatewayStateFile: '''+json.dumps(str(home/'gateway-state.json'))+'''
        gatewayName: Harmony P7 isolated real Host
        gatewayWaitTimeoutMs: 30000
        cloudflarePort: 18898
        fileUploadsEnabled: true
        fileDownloadsEnabled: true
''')
    environment=dict(os.environ,DSH_HOME=str(home),DSH_AGENTS_HOME=str(home/'agents'),HARMONY_DSH_NODE_MODULES=str(PACKAGE/'node_modules'))
    host=subprocess.Popen([str(NODE),str(PACKAGE/'lib/bin.js'),'web','--host','127.0.0.1','--port',str(PORT),'--no-open'],cwd=workspace,env=environment,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
    with (home/'host-redacted.log').open('w') as log:
        redactor=subprocess.Popen([str(NODE),str(ROOT/'harmony/testing/redact_stream.cjs')],stdin=host.stdout,stdout=log,stderr=log,start_new_session=True)
    host.stdout.close(); processes=dict(hostPid=host.pid,redactorPid=redactor.pid,root=str(home),workspace=str(workspace),port=PORT,observerPort=OBSERVER_PORT)
    (home/'owned-processes.json').write_text(json.dumps(processes,indent=2))
    try:
        state=wait('http://127.0.0.1:'+str(PORT)+'/mgw/status');assert state.get('gatewayEnabled')
        # 验收只走本机，不创建对外隧道候选地址。
        request=urllib.request.Request('http://127.0.0.1:'+str(PORT)+'/mgw/cloudflare',json.dumps({'enabled':False}).encode(),{'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=15) as response:json.load(response)
        with (home/'observer.log').open('w') as log:
            observer=subprocess.Popen([str(NODE),str(ROOT/'harmony/testing/live_gateway_control.cjs')],cwd=workspace,env=dict(environment,HARMONY_GATEWAY_PACKAGE=str(GATEWAY),HARMONY_LIVE_HTTP='http://127.0.0.1:'+str(PORT),HARMONY_LIVE_PORT=str(OBSERVER_PORT)),stdout=log,stderr=log,start_new_session=True)
        processes['observerPid']=observer.pid;processes['gatewayVersion']=state.get('version');(home/'owned-processes.json').write_text(json.dumps(processes,indent=2))
        wait('http://127.0.0.1:'+str(OBSERVER_PORT)+'/status');print('OWNED_REAL_HOST',home,flush=True)
    except Exception:
        import signal
        for key in ['observerPid','hostPid','redactorPid']:
            if key in processes:
                try:os.killpg(processes[key],signal.SIGTERM)
                except ProcessLookupError:pass
        raise


if __name__=='__main__':main()
