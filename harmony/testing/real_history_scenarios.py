"""真实1033条事件历史分页、前插锚点和切换期间的迟到页面。"""
import json,os,re,time,uuid,urllib.request
from pathlib import Path


def run(ui,m,folder,passed,call,session):
    seed=json.loads(Path(os.environ['HARMONY_HISTORY_SEED_JSON']).read_text());assert seed['complete'] and seed['sessionId']==session['id'] and seed['eventCount']>=1000
    expected=seed['markers'];prefix='HARMONY_HISTORY_'+seed['run']+'_';checks=[]
    def wait(fn,timeout=35):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=fn()
            if value:return value
            time.sleep(.3)
        raise AssertionError('真实历史投影/定位未按时确认')
    def probe():
        row=ui.wait('test-history-projection');return json.loads(row.get('text') or '{}')
    def proxy(route,data=None):
        req=urllib.request.Request('http://127.0.0.1:18908'+route,None if data is None else json.dumps(data).encode(),{'Content-Type':'application/json'});return json.load(urllib.request.urlopen(req,timeout=10))
    def top():
        for _ in range(70):
            if ui.find('history-older'):return
            ui.scroll(False)
        raise AssertionError('无法滚到真实分页入口')
    def current_markers():return [s for s in probe().get('markers',[]) if s.startswith(prefix)]
    def record(name,detail,**data):
        checks.append(dict(case=name,**data));(folder/'real-history-results.json').write_text(json.dumps(dict(sourceEvents=seed['eventCount'],sourceSession=session['id'],checks=checks),ensure_ascii=False,indent=2));passed('P1-real-history-'+name+'-G',detail)
    wait(lambda:probe().get('sessionId')==session['id'] and len(current_markers())>0)
    first=probe();assert first['more'] and 0<len(current_markers())<len(expected)
    top();rows=[r for r in ui.nodes() if r.get('id','').startswith('message-body-') and str(r.get('text','')).startswith(prefix)]
    anchor=next(r for r in rows if r['bounds']==r.get('origBounds',r['bounds']));anchor_id=anchor['id'];before_y=int(re.findall(r'-?\d+',anchor['bounds'])[1]);before=len(current_markers())
    ui.click('history-older');wait(lambda:len(current_markers())>before and not probe()['loading']);after=ui.wait(anchor_id);after_y=int(re.findall(r'-?\d+',after['bounds'])[1]);assert abs(before_y-after_y)<=5,(before_y,after_y)
    ui.screenshot('real-history-prepend-kept-anchor');record('prepend','真实服务器分页前插新增标记，旧可见消息位置偏差不超过5px',before=before,after=len(current_markers()),anchor=anchor_id,beforeY=before_y,afterY=after_y)
    other=call('/create',{'label':'P1-history-B-'+uuid.uuid4().hex[:6]});call('/subscribe',{'id':other['id']});other_marker='HARMONY_REAL_HISTORY_OTHER_'+uuid.uuid4().hex[:8].upper();call('/message',{'id':other['id'],'text':other_marker});call('/subscribe',{'id':session['id']})
    top()
    anchors=[r for r in ui.nodes() if r.get('id','').startswith('message-body-') and str(r.get('text','')).startswith(prefix) and r['bounds']==r.get('origBounds',r['bounds'])];assert anchors
    switch_anchor=anchors[0];switch_anchor_id=switch_anchor['id'];switch_y=int(re.findall(r'-?\d+',switch_anchor['bounds'])[1])
    proxy('/hold',{'kinds':['history'],'sessionId':session['id']});ui.click('history-older');wait(lambda:proxy('/status')['held']>0)
    ui.navigate('conversation-sidebar','sidebar-search');ui.click('session-'+other['id']);ui.wait('message-draft');wait(lambda:probe().get('sessionId')==other['id']);ui.input('message-draft','历史切换 B 草稿')
    proxy('/release',{});time.sleep(1.2);assert probe()['sessionId']==other['id'] and not current_markers();ui.text(other_marker,timeout=25);ui.screenshot('real-delayed-A-page-rejected-in-B')
    record('late-page-isolation','A真实历史页延迟，切B后释放迟到页面不出现A标记，B草稿和原生内容正确',currentSession=other['id'],foreignMarkers=0)
    ui.navigate('conversation-sidebar','sidebar-search');ui.click('session-'+session['id']);ui.wait('message-draft');wait(lambda:probe().get('sessionId')==session['id'] and not probe()['loading'])
    # 返回曾阅读的A时应恢复该条真实消息，不能悄悄回到只有最新尾页。
    (folder/'history-return-before-restore.json').write_text(json.dumps(probe(),ensure_ascii=False,indent=2))
    try: restored=ui.wait(switch_anchor_id,timeout=30)
    except AssertionError:
        ui.screenshot('real-history-switch-anchor-red');raise
    restored_y=int(re.findall(r'-?\d+',restored['bounds'])[1]);assert abs(restored_y-switch_y)<=5,(switch_y,restored_y)
    record('switch-anchor','A→B→A保留旧历史阅读锚点',beforeY=switch_y,afterY=restored_y)
    for page in range(30):
        state=probe()
        if not state['more']:break
        top();before=len(current_markers());ui.click('history-older');wait(lambda:not probe()['loading'] and (len(current_markers())>before or not probe()['more']))
    state=probe();actual=current_markers();assert not state['more'];assert actual==expected,(len(actual),len(expected));assert len(state['ids'])==len(set(state['ids']))
    sequences={seq for page in state['pages'] if page['sessionId']==session['id'] for seq in page['sequences']};assert set(seed['projectedSequences']).issubset(sequences)
    (folder/'history-page-boundaries.json').write_text(json.dumps(dict(sourceSequences=seed['eventSequences'],receivedPages=state['pages'],projectedMarkers=actual,projectedIds=state['ids']),ensure_ascii=False,indent=2))
    ui.screenshot('real-all-128-turns-paged');record('complete','1033条真实源历史的用户/助手消息经快照/分页完整收到，128条用户标记顺序和投影ID唯一且不漏',projectedUsers=len(actual),sourceEvents=seed['eventCount'])
