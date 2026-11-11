# coding: utf-8
"""无连接首页采集：生产组件、真实偏好和冷启静态帧，不固定系统设备适配器。"""
import shutil
import time


def run(ui, m, folder, passed):
    def capture(name):
        ui.nodes(); shutil.copy2(folder / 'layout.json', folder / (name + '-layout.json'))
        ui.screenshot(name)
    ui.wait('home-settings'); time.sleep(1.5)
    assert ui.wait('session-refresh')['text'] == '未连接'
    capture('harmony-home-empty')
    ui.navigate('home-settings', 'settings-motion')
    ui.click('settings-motion', scroll=True)
    ui.navigate('page-back', 'home-settings')
    m.hdc('shell', 'aa', 'force-stop', m.BUNDLE)
    m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
    ui.wait('home-settings'); time.sleep(2)
    capture('harmony-home-static')
    passed('P2-empty-home-capture-F', '生产未配对首页各入口与未连接状态可见；真实减少动态偏好冷启静态帧采集完成，V独立计算')
