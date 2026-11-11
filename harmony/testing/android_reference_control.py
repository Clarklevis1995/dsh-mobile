#!/usr/bin/env python3
"""仅控制隔离 Android 参考包；截图命令后必须立即用 view_image 查看原图。"""
import argparse,json,os,re,shutil,subprocess,time,urllib.request
from pathlib import Path
ADB='/Users/lichaofan/Library/Android/sdk/platform-tools/adb'
ANDROID='/Users/lichaofan/.local/bin/android'
DEVICE='emulator-5554'
BUNDLE='com.clarklevis.dsh.android.harmonyv1111'
FOLDER=Path('/private/tmp/harmony-visual-1111')

def adb(*args):return subprocess.check_output([ADB,'-s',DEVICE,*args],text=True)
def nodes():
    foreground=adb('shell','dumpsys','activity','activities')
    assert any(BUNDLE in line for line in foreground.splitlines() if 'mResumedActivity' in line or 'topResumedActivity' in line),'参考包不在前台'
    subprocess.run([ANDROID,'layout','--device='+DEVICE,'--no-idle','--flat','--full','-o',str(FOLDER/'reference-current.json')],check=True,stdout=subprocess.DEVNULL)
    return [row for window in json.loads((FOLDER/'reference-current.json').read_text()) for row in window.get('content',[]) if not row.get('off-screen') and not row.get('hidden')]
def click(label):
    rows=nodes();row=next(r for r in reversed(rows) if label in [r.get('text'),r.get('content-desc')] and r.get('center'))
    x,y=re.findall(r'-?\d+',row['center']);adb('shell','input','tap',x,y);print('CLICK',label,flush=True);time.sleep(.5)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['click','back','pair-owned','layout','capture','scroll']);parser.add_argument('value',nargs='?');parser.add_argument('--ready-text');args=parser.parse_args();FOLDER.mkdir(exist_ok=True)
    if args.action=='click':click(args.value)
    elif args.action=='scroll':
        row=next(r for r in nodes() if 'SCROLLABLE' in r.get('interactions',[]));l,t,r,b=map(int,re.findall(r'-?\d+',row['bounds']));x=(l+r)//2;a=t+(b-t)//4;z=t+(b-t)*3//4
        adb('shell','input','swipe',str(x),str(z if args.value!='up' else a),str(x),str(a if args.value!='up' else z),'1200');time.sleep(.5)
    elif args.action=='back':nodes();adb('shell','input','keyevent','KEYCODE_BACK')
    elif args.action=='layout':
        for row in nodes():
            text=row.get('text') or row.get('content-desc')
            if text:print(json.dumps(dict(text=text if len(text)<160 else '[long text]',center=row.get('center'),state=row.get('state'),interactions=row.get('interactions')),ensure_ascii=False))
    elif args.action=='pair-owned':
        # 只允许本轮创建的隔离 Host；不访问用户3080等管理接口。
        owned=Path(os.environ['HARMONY_OWNED_REAL_ROOT']).resolve();meta=json.loads((owned/'owned-processes.json').read_text());assert owned.name.startswith('harmony-p7-owned-host-') and meta['port']==18897 and meta['observerPort']==18899
        rows=nodes();row=next(r for r in rows if 'EDITABLE'in r.get('interactions',[]));x,y=re.findall(r'-?\d+',row['center']);adb('shell','input','tap',x,y)
        assert any('FOCUSED'in r.get('state',[]) and 'EDITABLE'in r.get('interactions',[]) for r in nodes())
        adb('shell','input','keycombination','113','29');adb('shell','input','keyevent','67')
        payload=json.load(urllib.request.urlopen('http://127.0.0.1:18899/bootstrap',timeout=10))['qrPayload']
        adb('shell','input','text',payload)
        actual=next(r.get('text','') for r in nodes() if 'EDITABLE'in r.get('interactions',[]));assert actual==payload,'IME改写了配对内容，禁止继续提交'
        labels=[r.get('text') for r in nodes()];click('重新配对并连接' if '重新配对并连接' in labels else '连接');print('OWNED_PAIR_SUBMITTED',flush=True)
    else:
        assert re.fullmatch(r'[a-zA-Z0-9_-]+',args.value or '')
        if args.ready_text:
            deadline=time.monotonic()+18
            while not any(row.get('text')==args.ready_text for row in nodes()):
                assert time.monotonic()<deadline, '截图前预期内容未就绪'
                time.sleep(.3)
        nodes();shutil.copyfile(FOLDER/'reference-current.json',FOLDER/(args.value+'-layout.json'));subprocess.run([ANDROID,'screen','capture','--device='+DEVICE,'-o',str(FOLDER/(args.value+'.png'))],check=True)
if __name__=='__main__':main()
