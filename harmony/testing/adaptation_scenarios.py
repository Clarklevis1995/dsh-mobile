"""实际 Window 横竖切换、应用字号和深色专项；不冒充平板／系统读屏 D。"""
import re
import time
import struct
import json


def run(ui, m, folder, passed):
    def screen(name):
        ui.screenshot(name)
        data = (folder / (name + '.png')).read_bytes()
        assert data[:8] == b'\x89PNG\r\n\x1a\n'
        return struct.unpack('>II', data[16:24])
    def draft():
        assert ui.wait('message-draft').get('text') == '横竖切换保留 Chinese English 🐳'
    m.connect(ui); ui.wait('message-draft'); ui.input('message-draft', '横竖切换保留 Chinese English 🐳')
    portrait = screen('native-portrait-before'); ui.click('test-orientation-toggle'); time.sleep(1.5)
    landscape = screen('native-landscape-retained-draft'); assert landscape[0] > portrait[0] * 1.5 and landscape[1] < portrait[1], (portrait, landscape); draft()
    ui.click('test-orientation-toggle'); time.sleep(1); restored = screen('native-portrait-restored'); assert restored == portrait; draft()
    (folder / 'orientation.json').write_text(json.dumps({'portrait': portrait, 'landscape': landscape, 'restored': restored, 'source':'actual PNG header; UITest dump clips to stale portrait width after rotation'}, indent=2))
    ui.navigate('conversation-back', 'home-settings'); ui.navigate('home-settings', 'settings-style'); ui.navigate('settings-font', 'font-follow-system')
    if str(ui.find('font-follow-system').get('checked')).lower() == 'true': ui.click('font-follow-system')
    row, x, y = ui.control('font-scale'); bounds = list(map(int, re.findall(r'-?\d+', row['bounds'])))
    m.hdc('shell', 'uitest', 'uiInput', 'click', str(bounds[2] - 7), str(y)); ui.text('150%'); ui.navigate('page-back', 'settings-style')
    ui.navigate('settings-style', 'appearance-system'); ui.choose('appearance-dark'); ui.navigate('page-back', 'home-settings')
    ui.click('鸿蒙原生会话验证', 'text'); draft(); ui.wait('conversation-overflow'); ui.screenshot('native-dark-font-150-chat')
    ui.click('conversation-overflow'); ui.wait('conversation-controls'); ui.screenshot('native-dark-font-150-menu')
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back'); draft()
    passed('P7-rotation-font-F', '实际 Window 横竖切换前后保留聊天草稿，原生深色与 150% 应用字号下输入／更多菜单可用；手机横屏不等于平板或全页 V／读屏 D')
