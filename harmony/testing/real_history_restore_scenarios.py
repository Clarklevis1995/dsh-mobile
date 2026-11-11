"""真实长历史阅读时后台收到另一端消息，前台重同步保留原位置并可跳到新增内容。"""
import json,os,re,time,uuid
from pathlib import Path


def run(ui,m,folder,passed,call,session):
    seed=json.loads(Path(os.environ['HARMONY_HISTORY_SEED_JSON']).read_text());prefix='HARMONY_HISTORY_'+seed['run']+'_'
    def wait(fn,timeout=40):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=fn()
            if value:return value
            time.sleep(.3)
        raise AssertionError('真实阅读恢复未按时完成')
    def probe():return json.loads(ui.wait('test-history-projection').get('text') or '{}')
    def top():
        for _ in range(65):
            if ui.find('history-older'):return
            ui.scroll(False)
        raise AssertionError('未到达历史入口')
    wait(lambda:len(probe().get('markers',[]))>0);top();before=len(probe()['markers']);ui.click('history-older');wait(lambda:len(probe()['markers'])>before and not probe()['loading']);top()
    rows=[r for r in ui.nodes() if r.get('id','').startswith('message-body-') and str(r.get('text','')).startswith(prefix) and r['bounds']==r.get('origBounds',r['bounds'])];assert rows;anchor=rows[0];anchor_id=anchor['id'];y=int(re.findall(r'-?\d+',anchor['bounds'])[1]);ui.screenshot('real-reading-before-background-append')
    m.hdc('shell','uitest','uiInput','keyEvent','Home');marker='HARMONY_REAL_HISTORY_ADDED_'+uuid.uuid4().hex[:8].upper();call('/message',{'id':session['id'],'text':marker})
    def received():
        records=call('/history',{'id':session['id']})['records']
        return any((r.get('event') or {}).get('type')=='assistant/message' and marker in json.dumps(r,ensure_ascii=False) for r in records)
    wait(received);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('message-draft',timeout=30);wait(lambda:not probe().get('loading'))
    (folder/'history-foreground-projection.json').write_text(json.dumps(probe(),ensure_ascii=False,indent=2))
    try:row=ui.wait(anchor_id,timeout=35)
    except AssertionError:
        ui.screenshot('real-foreground-reading-anchor-red');raise
    restored_y=int(re.findall(r'-?\d+',row['bounds'])[1]);assert abs(y-restored_y)<=5,(y,restored_y)
    ui.screenshot('real-foreground-reading-anchor-preserved');ui.click('conversation-latest');ui.text(marker,timeout=25);ui.screenshot('real-foreground-new-message-at-tail')
    (folder/'real-history-restore.json').write_text(json.dumps(dict(sessionId=session['id'],anchor=anchor_id,beforeY=y,afterY=restored_y,externalMarker=marker,latestVisible=True,source='real owned Host /message while native Home background; real resume snapshot and shared history pagination'),ensure_ascii=False,indent=2))
    passed('P1-real-history-foreground-append-G','长历史阅读时Home后台另一端追加，前台同步保留原消息位置≤5px，主动回到底部后新增消息真实可见')
