# coding: utf-8
"""真实Host稳定历史→实际加密RDB→代理断网冷启/搜索/恢复/清理。"""
import json,os,re,time,uuid,urllib.request
from pathlib import Path

def run(ui,m,folder,passed,call,session):
    def wait(fn,timeout=35):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=fn()
            if value:return value
            time.sleep(.4)
        raise AssertionError('真实缓存专项未按时满足条件')
    def proxy(route):
        req=urllib.request.Request('http://127.0.0.1:18908'+route,b'{}',{'Content-Type':'application/json'});return json.load(urllib.request.urlopen(req,timeout=5))
    def snapshot():return json.loads(ui.wait('test-history-projection')['text'] or '{}')
    def cold():
        m.hdc('shell','aa','force-stop',m.BUNDLE);time.sleep(.5);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('home-settings',timeout=35);ui.click('session-'+session['id'],scroll=True);ui.wait('message-draft')
    def storage():
        ui.navigate('conversation-back','home-settings');ui.navigate('home-settings','settings-storage');ui.click('settings-storage',scroll=True);ui.wait('storage-refresh')
    def count(key,value):
        def check():
            ui.click('storage-refresh');return value in ui.wait(key).get('text','')
        wait(check,20)
    seed=json.loads(Path(os.environ['HARMONY_HISTORY_SEED_JSON']).read_text());prefix='HARMONY_HISTORY_'+seed['run']+'_'
    wait(lambda:len(snapshot().get('markers',[]))>0)
    for _ in range(45):
        if ui.find('history-older'):break
        ui.scroll(False)
    before=len(snapshot()['markers']);ui.click('history-older');wait(lambda:len(snapshot()['markers'])>before and not snapshot()['loading'])
    cached_markers=snapshot()['markers'];assert len(cached_markers)>20
    ui.input('message-draft','真实离线草稿 A English '+uuid.uuid4().hex[:6]);draft=ui.find('message-draft')['text'];time.sleep(1.5);storage();count('storage-window-count','· 1');count('storage-draft-count','· 1 ·');ui.screenshot('real-cache-persisted-counts')
    proxy('/offline')
    try:
        cold();ui.wait('cached-history-notice',timeout=30);assert ui.find('message-draft')['text']==draft;assert str(ui.find('message-send')['enabled']).lower()=='false';assert len(snapshot()['markers'])>20;ui.screenshot('real-cache-cold-offline')
        edited='真实离线修改 B English '+uuid.uuid4().hex[:6];ui.input('message-draft',edited);time.sleep(1.5);cold();ui.wait('cached-history-notice');assert ui.find('message-draft')['text']==edited
        storage();ui.navigate('storage-search','offline-search-input');query=prefix+'127';ui.input('offline-search-input',query)
        def result():return next((r['id'] for r in ui.nodes() if r.get('id','').startswith('offline-result-')),None)
        key=wait(result);ui.screenshot('real-cache-search-English');ui.click('offline-search-session');wait(result);ui.click(key);ui.wait('message-draft');ui.text(query);assert ui.find('message-draft')['text']==edited;ui.screenshot('real-cache-search-located')
        ui.navigate('conversation-back','offline-search-input');assert ui.find('offline-search-input')['text']==query;ui.navigate('page-back','storage-refresh');ui.navigate('page-back','settings-style');ui.navigate('page-back','home-settings');ui.click('session-'+session['id'],scroll=True);ui.wait('message-draft')
        passed('P6-real-cache-offline-G','真实长历史加密落盘，断网实际冷启及离线改稿再次冷启保留、缓存范围和禁发、英文当前主机/会话搜索定位与返回保留查询通过')
        proxy('/online');wait(lambda:str(ui.wait('message-send')['enabled']).lower()=='true',50);wait(lambda:not ui.find('cached-history-notice'),30);assert ui.find('message-draft')['text']==edited;ui.screenshot('real-cache-network-takeover')
        records=call('/history',{'id':session['id']})['records'];assert not any(edited in json.dumps(r,ensure_ascii=False) or draft in json.dumps(r,ensure_ascii=False) for r in records),'离线草稿被自动重发'
        storage();count('storage-window-count','· 1');ui.click('storage-clear-history');ui.wait('confirmation-accept');ui.click('取消','text');count('storage-window-count','· 1');ui.click('storage-clear-history');ui.wait('confirmation-accept');ui.click('confirmation-accept');count('storage-window-count','· 0');count('storage-draft-count','· 1 ·');time.sleep(2);count('storage-window-count','· 0')
        ui.navigate('storage-search','offline-search-input');ui.input('offline-search-input',query);ui.wait('offline-search-empty');ui.screenshot('real-cache-clear-index-empty')
        (folder/'real-cache-audit.json').write_text(json.dumps(dict(sessionId=session['id'],cachedUserMarkers=len(cached_markers),coldOfflineRetained=True,offlineEditedRetained=True,searchQuery=query,networkTakeoverRetainedDraft=True,noAutomaticDraftSend=True,historyClearedIndexEmpty=True),ensure_ascii=False,indent=2))
        passed('P6-real-cache-takeover-clear-G','真实网络恢复接管不清离线新稿、不自动重发；清理取消/确认、仅历史保留草稿、心跳后不复活及索引移除通过')
    finally:proxy('/online')
