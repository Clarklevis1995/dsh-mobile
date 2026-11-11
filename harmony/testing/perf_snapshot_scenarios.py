# coding: utf-8
"""不改变1005条数据，仅截图/前后台的内存对照，用于区分窗口缓存与消息解析。"""
import json,time

def run(ui,m,folder,passed):
    samples=[]
    def sample(label):
        row=json.loads(ui.wait('test-p7-stats')['text']);row['label']=label;samples.append(row);(folder/'snapshot-control-memory.json').write_text(json.dumps(dict(scope='无发送/无历史追加，同一1005数据，截图和Home/前台两次；只观测，不作为性能通过结论',samples=samples),ensure_ascii=False,indent=2));return row
    m.connect(ui);ui.text('一千条历史加载完成');time.sleep(3);sample('unchanged-history-baseline')
    for n in range(2):
        ui.screenshot('unchanged-history-snapshot-'+str(n));m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(3);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('message-draft');time.sleep(2);sample('after-snapshot-background-'+str(n))
    start=time.monotonic()
    for elapsed in [3,10,20,30]:
        time.sleep(max(0,elapsed-(time.monotonic()-start)));sample('control-idle-'+str(elapsed)+'s')
    print('DONE SNAPSHOT CONTROL observational only',flush=True)
