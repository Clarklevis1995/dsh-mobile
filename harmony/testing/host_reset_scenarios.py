# coding: utf-8
"""隔离应用移除最后主机后：首页、设置及同Host重新配对不保留旧资料。"""
import time


def run(ui, m, folder, passed):
    def no_host_settings(name):
        ui.text('尚未配置主机')
        assert not any(r.get('text') == 'ws://127.0.0.1:18783/ws/mobile' for r in ui.nodes())
        assert str(ui.wait('settings-defaults')['enabled']).lower() == 'false'
        ui.text('尚未连接'); ui.screenshot(name)
    def idle_home(name):
        assert ui.wait('session-refresh')['text'] == '未连接'
        assert not any(r.get('id', '').startswith('session-harmony-') for r in ui.nodes())
        ui.screenshot(name)
    m.connect(ui); ui.input('message-draft', '删除主机时应清理的旧草稿'); time.sleep(1.5)
    ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-settings', 'settings-hosts')
    ui.navigate('settings-hosts', 'host-add')
    profiles = [r['id'].removeprefix('host-select-') for r in ui.nodes() if r.get('id', '').startswith('host-select-')]
    assert len(profiles) == 1
    ui.click('host-remove-' + profiles[0]); ui.wait('confirmation-accept'); ui.click('confirmation-accept')
    assert not any(r.get('id', '').startswith('host-select-') for r in ui.nodes())
    ui.navigate('page-back', 'settings-style'); no_host_settings('last-host-removed-settings')
    ui.navigate('page-back', 'home-settings'); idle_home('last-host-removed-home')
    time.sleep(2); idle_home('last-host-removed-late-callback')
    m.hdc('shell', 'aa', 'force-stop', m.BUNDLE); m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
    ui.wait('home-settings'); idle_home('last-host-removed-cold')
    m.connect(ui); ui.wait('message-draft')
    assert ui.find('message-draft').get('text', '') == '', '同Host重新配对复活已清理草稿'
    ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-settings', 'settings-privacy'); ui.navigate('settings-privacy', 'forget-all-hosts')
    ui.click('forget-all-hosts'); ui.wait('confirmation-accept'); ui.click('confirmation-accept')
    ui.wait('home-settings'); idle_home('forget-all-home')
    ui.navigate('home-settings', 'settings-style'); no_host_settings('forget-all-settings')
    ui.navigate('page-back', 'home-settings')
    passed('P2-last-host-reset-F', '移除最后Host/全部删除后首页恢复未连接、设置清旧地址并禁用默认值；迟到回调和冷启不复活，同Host重新配对无旧草稿')
