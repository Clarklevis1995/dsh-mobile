# coding: utf-8
"""沿用既有冻结门禁，测量千条历史下两轮长代码/表格流式、编辑和前后台。"""
import json,time,re,os
from long_stream_fixture import content,digest

def run(ui,m,folder,passed):
    gates=json.loads((m.ROOT/'harmony/testing/baseline/p7-performance-gates.json').read_text());samples=[];anchors=[];expected=digest(content());diagnostic=any(os.environ.get(key)=='1' for key in ['HARMONY_PERF_MEMORY_DETAIL','HARMONY_PERF_NATIVE_MEMORY'])
    def sample(label):
        assert not ui.find('内容解析失败','text'), '生产Markdown解析失败'
        value=json.loads(ui.wait('test-p7-stats')['text']);value['label']=label;value['sampledAtMonotonicSeconds']=time.monotonic();samples.append(value);(folder/'long-stream-performance.json').write_text(json.dumps(dict(gates=gates,expectedProjection=expected,samples=samples,acceptanceEligible=not diagnostic,scope='实际SDK PSS与JS桥/镜像/编辑/后台解析回调，非GPU帧时间；F夹具千行代码+160行表格，未闭合语法逐段到达'),ensure_ascii=False,indent=2))
        if os.environ.get('HARMONY_PERF_MEMORY_DETAIL')=='1' and label in ['1005-initial','after-background-1','final-idle-3s','final-idle-30s']:
            from memory_detail_probe import record
            record(m,folder,label)
        return value
    def wait(fn,timeout=100):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            value=fn()
            if value:return value
            time.sleep(.6)
        raise AssertionError('长流式回归未按时完成')
    m.connect(ui);ui.text('一千条历史加载完成',timeout=30);time.sleep(2);first=sample('1005-initial');assert first['messages']>=1000
    ids=[]
    for round_index in range(2):
        ui.message('perf-stream');ui.wait('message-stop');time.sleep(2);sample('stream-'+str(round_index)+'-active')
        for n in range(2):ui.input('message-draft','长回复期间保留草稿 '+str(round_index)+'-'+str(n));sample('stream-edit-'+str(round_index)+'-'+str(n))
        draft=ui.find('message-draft')['text'];anchor=None
        for _ in range(9):
            ui.scroll(False)
            anchor=next((r for r in ui.nodes() if re.fullmatch(r'message-body-harmony-smoke-(?:[1-9]\d{0,2}|100[0-5])',r.get('id','')) and r.get('text') and r.get('bounds')==r.get('origBounds')),None)
            if anchor:break
        assert anchor, '未真正滚动到较早的稳定消息'
        # UITest快速滑动后仍有惯性；先确认同一消息连续三帧静止，再记录阅读位置。
        previous_y=None;stable=0;settle_end=time.monotonic()+5
        while time.monotonic()<settle_end:
            current=ui.find(anchor['id']);current_y=int(re.findall(r'-?\d+',current['bounds'])[1]) if current else None
            stable=stable+1 if current_y is not None and current_y==previous_y else 0;previous_y=current_y
            if stable>=2:break
            time.sleep(.2)
        assert stable>=2, '手势惯性结束前无法固定阅读位置'
        anchor_y=previous_y;sample('stream-reading-older-'+str(round_index));assert ui.find('message-draft')['text']==draft
        wait(lambda:len(json.loads(ui.wait('test-p7-stats')['text']).get('completedLong',[]))>=round_index+1)
        def restored_position():
            row=ui.find(anchor['id'])
            if not row:return None
            y=int(re.findall(r'-?\d+',row['bounds'])[1]);return y if abs(y-anchor_y)<=5 else None
        restored_y=wait(restored_position,4);anchors.append(dict(round=round_index,id=anchor['id'],beforeY=anchor_y,afterY=restored_y));(folder/'long-stream-reading-anchors.json').write_text(json.dumps(anchors,ensure_ascii=False,indent=2))
        ui.click('conversation-latest');ui.text('P7_LONG_STREAM_FINAL',timeout=25);assert ui.find('message-draft')['text']==draft;ui.screenshot('long-stream-final-'+str(round_index));value=sample('stream-'+str(round_index)+'-complete');rows=value['completedLong'];assert len({r['id'] for r in rows})==round_index+1
        for row in rows:assert row['length']==expected['length'] and row['hash']==expected['hash'], 'Native终帧正文与源样本不一致'
        ids=[r['id'] for r in rows]
        m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(3);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('message-draft');assert ui.find('message-draft')['text']==draft;time.sleep(2);sample('after-background-'+str(round_index))
    idle_start=time.monotonic()
    for elapsed in [3,10,20,30]:
        time.sleep(max(0,elapsed-(time.monotonic()-idle_start)));sample('final-idle-'+str(elapsed)+'s')
    final=samples[-1];checks=[
        ('首屏',first['firstTailMs']>=0 and first['firstTailMs']<=gates['firstTailReadyMillisecondsMaximum']),
        ('挂载数量',max(x['peakCards'] for x in samples)<=gates['liveMessageCardsMaximum']),
        ('桥接P95',final['bridgeP95Ms']<=gates['bridgeP95MillisecondsMaximum']),
        ('镜像P95',final['updateP95Ms']<=gates['viewUpdateP95MillisecondsMaximum']),
        ('编辑P95',0<=final['draftP95Ms']<=gates['draftCallbackP95MillisecondsMaximum']),
        ('1秒取样PSS峰值',max(int(x.get('sampledPeakPssKiB',x['pssKiB'])) for x in samples)/1024<=gates['processPssMiBMaximum']),
        ('短时3秒空闲PSS增量',(int(next(x for x in samples if x['label']=='final-idle-3s')['pssKiB'])-int(first['pssKiB']))/1024<=gates['scrollIdlePssGrowthMiBMaximum']),
        ('空闲PSS增量',(int(final['pssKiB'])-int(first['pssKiB']))/1024<=gates['scrollIdlePssGrowthMiBMaximum'])]
    (folder/'long-stream-gates.json').write_text(json.dumps(dict(checks=[dict(label=k,passed=v) for k,v in checks],completedMessageIds=ids,expectedProjection=expected,acceptanceEligible=not diagnostic),ensure_ascii=False,indent=2))
    if diagnostic:
        print('DIAGNOSTIC_ONLY: functional assertions completed; memory instrumentation timings are not acceptance evidence',flush=True);return
    assert all(v for _,v in checks),checks
    passed('P7-long-code-table-stream-F','1005历史下两轮千行代码/160行表格持续流，Native正文UTF16长度/FNV与源样本一致、终标记可见、期间编辑和上滑及前后台草稿保留；既有冻结门禁通过')
