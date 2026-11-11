# coding: utf-8
#!/usr/bin/env python3
"""无测试注入的独立候选包，验证首次安装和仅本轮数据的原位升级；不发布。"""
import hashlib,json,os,re,shutil,socket,subprocess,tempfile,time
from pathlib import Path
import smoke_ui as m


def main():
    assert os.environ.get('HARMONY_TEST_TARGET'), '必须显式选择独占模拟器'
    for port in [18783]:
        with socket.socket() as guard:guard.bind(('127.0.0.1',port))
    folder=Path(tempfile.mkdtemp(prefix='harmony-candidate-upgrade-'));print('REPORT_DIR',folder,flush=True)
    app=folder/'app';shutil.copytree(m.ROOT/'harmonyApp',app,ignore=shutil.ignore_patterns('build','.hvigor','oh_modules','.idea'))
    m.BUNDLE='com.clarklevis.whaleness.harmony.preview'+time.strftime('%Y%m%d')+os.urandom(2).hex()
    before=m.hdc('shell','bm','dump','-n',m.BUNDLE);assert not re.search(r'"bundleName"\s*:\s*"'+re.escape(m.BUNDLE)+r'"',before),'拒绝覆盖已有包'
    value=json.loads((app/'AppScope/app.json5').read_text());value['app']['bundleName']=m.BUNDLE
    resource=app/'AppScope/resources/base/element/string.json';strings=json.loads(resource.read_text())
    for row in strings['string']:
        if row['name']=='app_name':row['value']='Whaleness 鸿蒙候选'
    resource.write_text(json.dumps(strings,ensure_ascii=False,indent=2))
    for path in (app/'entry/src/main/ets').rglob('*.ets'):
        data=path.read_text();assert 'test-notification-inspect' not in data and 'test-history-projection' not in data and 'test-seed-photos' not in data, '候选包含测试入口'
    env=dict(os.environ,DEVECO_SDK_HOME=str(m.DEVECO/'sdk'));env['PATH']=str(m.DEVECO/'tools/node/bin')+':'+str(m.DEVECO/'tools/ohpm/bin')+':'+env['PATH']
    def build(code):
        value['app']['versionCode']=code;value['app']['versionName']='0.1.0-preview';(app/'AppScope/app.json5').write_text(json.dumps(value,indent=2))
        with (folder/('build-'+str(code)+'.log')).open('w') as log:
            subprocess.run([str(m.DEVECO/'tools/ohpm/bin/ohpm'),'install','--all'],cwd=app,env=env,stdout=log,stderr=log,check=True)
            subprocess.run([str(m.DEVECO/'tools/node/bin/node'),str(m.DEVECO/'tools/hvigor/bin/hvigorw.js'),'--mode','module','-p','module=entry@default','-p','product=default','-p','buildMode=debug','assembleHap','--no-daemon'],cwd=app,env=env,stdout=log,stderr=log,check=True)
        p=folder/('candidate-'+str(code)+'-unsigned.hap');shutil.copy2(app/'entry/build/default/outputs/default/entry-default-unsigned.hap',p);return p
    one=build(1);ui=m.UI(folder);log=(folder/'fixture.log').open('w');server=subprocess.Popen([os.sys.executable,str(m.ROOT/'harmony/testing/gateway_fixture.py')],env=dict(os.environ,HARMONY_FIXTURE_PORT='18783'),stdout=log,stderr=log);route='tcp:18783';added=False
    try:
        time.sleep(.5);assert server.poll() is None
        if route+' '+route not in m.hdc('fport','ls'):m.hdc('rport',route,route);added=True
        m.hdc('install',str(one));m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('home-settings');m.connect(ui);draft='候选原位升级草稿 '+os.urandom(4).hex();ui.input('message-draft',draft);time.sleep(1.5)
        ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-style');ui.navigate('settings-style','appearance-dark');ui.choose('appearance-dark');ui.navigate('settings-font','font-follow-system')
        if str(ui.find('font-follow-system')['checked']).lower()=='true':ui.click('font-follow-system')
        row,x,y=ui.control('font-scale');bounds=list(map(int,re.findall(r'-?\d+',row['bounds'])));m.hdc('shell','uitest','uiInput','click',str(bounds[2]-7),str(y));ui.text('150%');ui.screenshot('candidate-before-upgrade-preferences');m.hdc('shell','aa','force-stop',m.BUNDLE)
        two=build(2);m.hdc('install','-r',str(two));m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('home-settings');ui.text('已连接',timeout=35);ui.click('session-harmony-smoke',scroll=True);ui.wait('message-draft');assert ui.find('message-draft')['text']==draft;ui.text('历史记录 24');ui.screenshot('candidate-upgraded-draft-and-auth')
        ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-style');assert ui.find('settings-style')['backgroundColor']=='#FF111720';ui.text('150%');ui.screenshot('candidate-upgraded-preferences');ui.navigate('settings-font','font-follow-system');ui.click('font-scale-reset');ui.text('标准');ui.navigate('page-back','settings-style');ui.navigate('settings-style','appearance-light');ui.choose('appearance-light');ui.navigate('page-back','home-settings')
        ui.navigate('home-auth','host-add');profile=next(r['id'].removeprefix('host-select-') for r in ui.nodes() if r.get('id','').startswith('host-select-'));ui.click('host-remove-'+profile);ui.wait('confirmation-accept');ui.click('confirmation-accept');assert not any(r.get('id','').startswith('host-select-') for r in ui.nodes());ui.navigate('page-back','home-settings');ui.screenshot('candidate-clean-home')
        destination=m.ROOT/'harmonyApp/build/acceptance';destination.mkdir(parents=True,exist_ok=True);final=destination/(m.BUNDLE+'-unsigned.hap');shutil.copy2(two,final)
        result=dict(bundle=m.BUNDLE,target=m.TARGET,versionCode=2,versionName='0.1.0-preview',signed=False,path=str(final),sha256=hashlib.sha256(final.read_bytes()).hexdigest(),bytes=final.stat().st_size,productionSourceWithoutHooks=True,firstInstall=True,inPlaceUpdate=True,assetIdentityRetained=True,draftHistoryAndPreferencesRetained=True,ownedTestHostRemoved=True,scope='同一生产实现vCode1→2，验证系统包更新保留数据；不替代旧schema/旧Native兼容和真机签名')
        (folder/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('PASS CANDIDATE_UPGRADE',m.BUNDLE,flush=True)
    finally:
        server.terminate();server.wait(timeout=6);log.close()
        if added:m.hdc('fport','rm',route,route)
    print('DONE',folder,flush=True)

if __name__=='__main__':main()
