#!/usr/bin/env python3
"""只重启本轮临时主机；保留独立观察器和持久身份，不接受用户主机目录。"""
import argparse,json,os,signal,subprocess,time,urllib.request
from pathlib import Path
from start_isolated_acceptance import NODE,PACKAGE,ROOT

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--root',type=Path,required=True);args=parser.parse_args()
    home=args.root.resolve();meta=json.loads((home/'owned-processes.json').read_text());workspace=home/'workspace'
    assert home.name.startswith('harmony-p7-owned-host-') and meta['port']==18897 and meta['observerPort']==18899 and Path(meta['root']).resolve()==home
    pid=int(meta['hostPid'])
    listing=subprocess.check_output(['lsof','-a','-p',str(pid),'-d','cwd','-Fn'],text=True)
    assert 'n'+str(workspace) in listing.splitlines(),'主机进程工作目录不属于本轮'
    command=subprocess.check_output(['ps','-p',str(pid),'-o','command='],text=True)
    assert str(PACKAGE/'lib/bin.js') in command and '--port 18897' in command
    os.killpg(pid,signal.SIGTERM)
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        result=subprocess.run(['lsof','-t','-iTCP:18897','-sTCP:LISTEN'],capture_output=True,text=True)
        if not result.stdout.strip():break
        time.sleep(.2)
    else:raise AssertionError('原专属主机未退出')
    env=dict(os.environ,DSH_HOME=str(home),DSH_AGENTS_HOME=str(home/'agents'),HARMONY_DSH_NODE_MODULES=str(PACKAGE/'node_modules'))
    host=subprocess.Popen([str(NODE),str(PACKAGE/'lib/bin.js'),'web','--host','127.0.0.1','--port','18897','--no-open'],cwd=workspace,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
    with (home/'host-redacted.log').open('a') as log:
        redactor=subprocess.Popen([str(NODE),str(ROOT/'harmony/testing/redact_stream.cjs')],stdin=host.stdout,stdout=log,stderr=log,start_new_session=True)
    host.stdout.close();meta.update(hostPid=host.pid,redactorPid=redactor.pid);(home/'owned-processes.json').write_text(json.dumps(meta,indent=2))
    deadline=time.monotonic()+40
    while time.monotonic()<deadline:
        try:
            state=json.load(urllib.request.urlopen('http://127.0.0.1:18897/mgw/status',timeout=2))
            if state.get('gatewayEnabled'):print('OWNED_HOST_RESTARTED',state.get('version'),flush=True);return
        except Exception:time.sleep(.5)
    raise AssertionError('专属主机重启未就绪')
if __name__=='__main__':main()
