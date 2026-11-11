"""只验证本应用授权／对象创建／格式，不启动麦克风采集。"""
import re
import time


def run(ui,m,folder):
    ui.wait('probe-audio-start',timeout=25);ui.click('probe-audio-start');until=time.monotonic()+90
    while time.monotonic()<until:
        rows=ui.nodes();lines=[row.get('text','') for row in rows]
        if any(line.startswith('FAIL') for line in lines):raise AssertionError(lines)
        if 'DONE AUDIO probe' in lines:ui.screenshot('audio-probe');return [line for line in lines if line.startswith(('PASS','DONE'))]
        system=ui.nodes(system=True)
        completed=[row.get('text','') for row in system]
        if 'DONE AUDIO probe' in completed: ui.screenshot('audio-probe'); return [line for line in completed if line.startswith(('PASS','DONE'))]
        allow=next((row for row in reversed(system) if row.get('text') in ['允许','仅使用期间允许','使用时允许','While using the app','Allow']),None)
        if allow:
            a,b,c,d=map(int,re.findall(r'-?\d+',allow['bounds']));m.hdc('shell','uitest','uiInput','click',str((a+c)//2),str((b+d)//2))
        time.sleep(.4)
    raise AssertionError('实际录音对象与格式验证未完成')
