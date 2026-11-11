"""产品缓存 F：实际 ArkData、系统杀进程与无网；不代表真实 Host G。"""
import json
import os
import subprocess
import sys
import time


def run(ui, m, folder, passed, servers):
    def stop(): m.hdc('shell', 'aa', 'force-stop', m.BUNDLE); time.sleep(.7)
    def start(): m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE); ui.wait('home-settings', timeout=40)
    def storage():
        ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-settings', 'settings-style'); ui.click('settings-storage', scroll=True); ui.wait('storage-refresh')
    def count(value, expected):
        until = time.monotonic() + 20
        while time.monotonic() < until:
            ui.click('storage-refresh'); row = ui.wait(value)
            if expected in row.get('text',''): return
            time.sleep(.5)
        raise AssertionError('真实存储统计未达到 ' + expected)
    m.connect(ui); ui.wait('message-draft'); ui.text('历史记录 24', timeout=25)
    ui.input('message-draft', '持久草稿 A English'); storage(); count('storage-window-count', '· 1'); count('storage-draft-count', '· 1 ·'); ui.screenshot('cache-durable-counts')
    stop(); servers[0].terminate(); servers[0].wait(timeout=6); start()
    ui.click('鸿蒙原生会话验证', 'text'); ui.wait('cached-history-notice', timeout=25); ui.text('历史记录 24', timeout=25)
    assert ui.find('message-draft').get('text') == '持久草稿 A English'
    assert str(ui.find('message-send').get('enabled')).lower() == 'false'
    ui.screenshot('offline-cold-history-and-draft')
    ui.input('message-draft', '离线修改 B English'); time.sleep(1.2); stop(); start()
    ui.click('鸿蒙原生会话验证', 'text'); ui.wait('message-draft'); assert ui.find('message-draft').get('text') == '离线修改 B English'
    ui.text('历史记录 24', timeout=25); passed('P6-offline-cold-F', '实际进程终止后，无网冷启动恢复稳定历史和文字草稿；离线修改再次杀进程保留，发送禁用')
    storage(); ui.navigate('storage-search', 'offline-search-input'); ui.input('offline-search-input', '历史记录 24')
    ui.wait('offline-result-harmony-smoke-24'); ui.screenshot('offline-Chinese-search')
    ui.click('offline-search-session'); ui.wait('offline-result-harmony-smoke-24'); ui.click('offline-result-harmony-smoke-24'); ui.wait('message-body-harmony-smoke-24')
    assert ui.find('message-draft').get('text') == '离线修改 B English'
    ui.screenshot('offline-search-located-message')
    m.hdc('shell','uinput','-T','-m','2','1800','920','1800','160'); time.sleep(.7); ui.wait('offline-search-input')
    assert ui.find('offline-search-input').get('text') == '历史记录 24'
    ui.wait('offline-result-harmony-smoke-24'); ui.click('offline-result-harmony-smoke-24'); ui.wait('message-body-harmony-smoke-24')
    passed('P6-offline-search-F', '真实索引中文查询、主机／当前会话范围及消息定位，定位不覆盖草稿')
    log = (folder / 'gateway-18783-restarted.log').open('w')
    process = subprocess.Popen([sys.executable, str(m.ROOT / 'harmony/testing/gateway_fixture.py')], env=dict(os.environ, HARMONY_FIXTURE_PORT='18783', HARMONY_FIXTURE_ID='a56a1098-8519-43a1-9dce-fb99863bf5bb'), stdout=log, stderr=log); servers.append(process)
    try:
        until = time.monotonic() + 45
        while time.monotonic() < until and str(ui.wait('message-send').get('enabled')).lower() != 'true': time.sleep(.5)
        assert str(ui.wait('message-send').get('enabled')).lower() == 'true', '恢复连接后仍无法发送'
        ui.text('历史记录 24', timeout=25)
        until = time.monotonic() + 15
        while time.monotonic() < until and ui.find('cached-history-notice'): time.sleep(.5)
        assert not ui.find('cached-history-notice'), '网络接管后缓存标签未移除'
        assert ui.find('message-draft').get('text') == '离线修改 B English'
        ui.screenshot('network-takeover-preserves-draft'); passed('P6-network-takeover-F', '同一受控网关恢复后自动建立新快照并移除缓存标签，离线文字不被覆盖')
        ui.navigate('conversation-back', 'offline-search-input'); ui.navigate('page-back', 'storage-refresh')
        count('storage-window-count', '· 1'); count('storage-draft-count', '· 1 ·')
        ui.click('storage-clear-history'); ui.wait('confirmation-accept'); ui.click('取消','text'); count('storage-window-count','· 1')
        ui.click('storage-clear-history'); ui.wait('confirmation-accept'); ui.click('confirmation-accept'); count('storage-window-count','· 0'); count('storage-draft-count','· 1 ·')
        ui.click('storage-clear-drafts'); ui.wait('confirmation-accept'); ui.click('取消','text'); count('storage-draft-count','· 1 ·')
        ui.click('storage-clear-drafts'); ui.wait('confirmation-accept'); ui.click('confirmation-accept'); count('storage-window-count','· 0'); count('storage-draft-count','· 0 ·'); ui.screenshot('storage-clear-confirmed-counts')
        ui.navigate('storage-search','offline-search-input'); ui.input('offline-search-input','历史记录 24'); ui.wait('offline-search-empty'); ui.screenshot('storage-clear-search-index-empty')
        passed('P6-storage-clear-F','实际存储统计，清理取消、仅历史保留草稿、历史加草稿确认后归零，搜索索引同步移除；仅独立测试包当前主机，不替代临时资源租约全矩阵')
    finally:
        process.terminate(); process.wait(timeout=6); log.close()
