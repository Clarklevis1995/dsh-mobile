"""真实 NotificationKit 授权、发布、隐私内容与 WantAgent 点击；只操作独立 smoke 包。"""
import re
import time


def run(ui, m, folder):
    ui.wait('probe-notification-enable',timeout=25); ui.click('probe-notification-enable')
    until=time.monotonic()+25
    while time.monotonic()<until:
        if ui.find('notification-enabled','text'): break
        rows=ui.nodes(system=True); allow=next((row for row in reversed(rows) if row.get('text') in ['允许','允许通知','Allow']),None)
        if allow:
            a,b,c,d=map(int,re.findall(r'-?\d+',allow['bounds']));m.hdc('shell','uitest','uiInput','click',str((a+c)//2),str((b+d)//2))
        time.sleep(.4)
    else: raise AssertionError('实际系统通知未获得授权')
    ui.click('probe-notification-publish'); time.sleep(1); ui.click('probe-notification-refresh'); ui.text('PASS actual SDK notification privacy'); ui.screenshot('notification-sdk-confirmed')
    m.hdc('shell','uitest','uiInput','keyEvent','Home');time.sleep(.8)
    m.hdc('shell','uitest','uiInput','swipe','650','40','650','1600','1000');time.sleep(.8)
    rows=ui.nodes(system=True); own=next((row for row in reversed(rows) if row.get('text')=='任务已完成'),None)
    assert own, '系统通知面板未显示本次自测提醒'
    a,b,c,d=map(int,re.findall(r'-?\d+',own['bounds']));m.hdc('shell','uitest','uiInput','click',str((a+c)//2),str((b+d)//2))
    ui.text('PASS actual WantAgent click target',timeout=25);ui.screenshot('notification-actual-click-target')
    return ['PASS actual SDK notification privacy','PASS actual WantAgent click target','DONE NOTIFICATION probe']
