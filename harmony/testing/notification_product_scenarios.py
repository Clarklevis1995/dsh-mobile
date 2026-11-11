"""生产通知从共享业务事件到系统点击定位；双主机使用相同session ID。"""
import json,re,time

def run(ui,m,folder,passed,connected=False):
    def system_click(row):
        b=list(map(int,re.findall(r'-?\d+',row['bounds'])));m.hdc('shell','uitest','uiInput','click',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.6)
    def home():
        if ui.find('conversation-back'):ui.navigate('conversation-back','home-settings')
        ui.wait('home-settings')
    def inspect(name):
        ui.click('test-notification-inspect');time.sleep(.5);rows=json.loads(ui.find('test-notification-inspect')['text']);(folder/(name+'.json')).write_text(json.dumps(rows,ensure_ascii=False,indent=2))
        for r in rows:assert r['title'] in ['有事项需要处理','任务已完成','任务已结束'] and r['text']=='点击进入对应主机和会话查看', '系统通知泄露业务正文'
        return rows
    def switch(host):
        ui.navigate('home-auth','host-add');ui.click('host-select-'+host);ui.wait('home-settings')
    def background_notice(mode):
        ui.message(mode);m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(14)
    def reopen():
        m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('conversation-back');home()
    def tap_notice(title):
        m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(.5);m.hdc('shell','uitest','uiInput','swipe','650','40','650','1600','1000');time.sleep(.8)
        rows=ui.nodes(system=True);own=[r for r in rows if r.get('text')==title];assert len(own)==1,'通知面板应只有本轮唯一目标';ui.screenshot('notification-system-'+title);system_click(own[0]);ui.wait('message-draft',timeout=25)
    if not connected:m.connect(ui)
    home();ui.navigate('home-settings','settings-privacy');ui.click('settings-privacy');ui.click('notifications-enabled')
    end=time.monotonic()+25
    while time.monotonic()<end:
        toggle=ui.find('notifications-enabled')
        if toggle and str(toggle.get('checked')).lower()=='true':break
        row=next((r for r in reversed(ui.nodes(system=True)) if r.get('text') in ['允许','允许通知','Allow']),None)
        if row:system_click(row)
        time.sleep(.3)
    else:raise AssertionError('系统通知未获授权')
    ui.navigate('page-back','settings-style');ui.navigate('page-back','home-settings');ui.navigate('home-auth','host-add')
    a=next(r['id'].removeprefix('host-select-') for r in ui.nodes() if r.get('id','').startswith('host-select-'))
    ui.click('host-add');ui.input('gateway-endpoint','ws://127.0.0.1:18784/ws/mobile');ui.input('gateway-pairing-code','harmony-smoke');ui.click('gateway-connect');ui.click('B 鸿蒙原生会话验证','text');ui.wait('message-draft');ui.input('message-draft','notification-host-B-draft');home();ui.navigate('home-auth','host-add')
    b=next(r['id'].removeprefix('host-select-') for r in ui.nodes() if r.get('id','').startswith('host-select-') and r['id']!='host-select-'+a);ui.click('host-select-'+a);ui.click('鸿蒙原生会话验证','text');ui.wait('message-draft')
    background_notice('notice-approval');reopen();before=inspect('approval-private-notices');assert len(before)==1 and before[0]['title']=='有事项需要处理'
    switch(b);ui.click('B 鸿蒙原生会话验证','text');assert ui.wait('message-draft')['text']=='notification-host-B-draft';tap_notice('有事项需要处理')
    ui.text('确认本机测试命令');assert ui.find('message-draft')['text']!='notification-host-B-draft';assert not any(r.get('text','').startswith('B 历史记录') for r in ui.nodes());ui.screenshot('notification-selected-host-A')
    home();after=inspect('after-click-notices');m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('home-settings');time.sleep(2);cold=inspect('after-cold-replay-notices');assert {r['id'] for r in cold}<={r['id'] for r in after},'冷重放产生重复通知'
    ui.click('鸿蒙原生会话验证','text');ui.wait('message-draft');ui.text('确认本机测试命令');reject=next(r['id'] for r in ui.nodes() if r.get('id','').startswith('approval-reject-'));ui.click(reject);ui.text('已拒绝工具调用')
    passed('P5-notification-routing-F','实际系统审批提醒隐私内容、A/B同sessionID点击回A正确会话、B草稿隔离、冷启重放无重复通知通过')
    background_notice('notice-ended');reopen();rows=inspect('ended-private-notices');assert any(r['title']=='任务已完成' for r in rows);tap_notice('任务已完成');ui.text('鸿蒙原生会话验证');ui.screenshot('notification-ended-session')
    home();ui.navigate('home-settings','settings-privacy');ui.click('settings-privacy');ui.click('notifications-enabled');assert str(ui.find('notifications-enabled')['checked']).lower()=='false';ui.navigate('page-back','settings-style');ui.navigate('page-back','home-settings');assert inspect('disabled-cleared-notices')==[]
    ui.click('鸿蒙原生会话验证','text');ui.wait('message-draft');background_notice('notice-approval');reopen();assert inspect('disabled-after-event-notices')==[]
    passed('P5-notification-ended-disabled-F','实际任务结束通知点击定位，关闭通知撤销本应用提醒，后续后台审批不再发布通过')
