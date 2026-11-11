# coding: utf-8
"""同数据设置页浅深采集与连接操作；只用于隔离协议夹具。"""
import json
import re
import shutil
import time
from collections import Counter
from PIL import Image


def run(ui, m, folder, passed, fixture_log=None):
    def top():
        ui.navigate('page-back', 'home-settings')
        ui.navigate('home-settings', 'settings-agent-presets')
        ui.text('0.2.2-fixture')
    def capture(name):
        rows = ui.nodes()
        shutil.copyfile(folder / 'layout.json', folder / (name + '-layout.json'))
        ui.screenshot(name)
        return rows
    if ui.find('home-settings'): ui.navigate('home-settings', 'settings-agent-presets')
    ui.text('构建 Agent'); ui.text('读取模型'); ui.text('只读')
    if fixture_log:
        before = fixture_log.read_text().count('manual-ping-response')
        ui.click('settings-ping')
        deadline = time.monotonic() + 8
        while fixture_log.read_text().count('manual-ping-response') <= before:
            assert time.monotonic() < deadline, '生产Ping入口未收到协议响应'
            time.sleep(.2)
    ui.click('settings-connection'); ui.text('已断开')
    rows = capture('settings-disconnected')
    for key in ['settings-ping', 'settings-agent-presets', 'settings-defaults', 'settings-default-permission']:
        assert str(next(r for r in rows if r.get('id') == key).get('enabled')).lower() == 'false', key
    dot = next(r for r in rows if r.get('id') == 'settings-status-dot')
    crop = Image.open(folder / 'settings-disconnected.png').convert('RGB').crop(tuple(map(int,re.findall(r'-?\d+',dot['bounds']))))
    assert Counter(crop.getdata())[(46,184,92)] == 0, '断开仍显示已连接绿点'
    ui.click('settings-connection'); ui.text('已连接'); ui.text('断开连接')
    assert str(ui.wait('settings-ping').get('enabled')).lower() == 'true'
    ui.navigate('settings-manage-hosts', 'host-add'); ui.navigate('page-back', 'settings-agent-presets')
    for theme in ['light', 'dark']:
        ui.navigate('settings-style', 'appearance-system'); ui.choose('appearance-' + theme)
        top(); rows = capture('settings-connected-' + theme + '-final')
        image = Image.open(folder / ('settings-connected-' + theme + '-final.png')).convert('RGB')
        expected = (241,241,246) if theme == 'light' else (13,17,24)
        assert image.getpixel((20,50)) == expected, (theme,'page background')
        divider = next(r for r in rows if r.get('type') == 'Divider')
        db = list(map(int,re.findall(r'-?\d+',divider['bounds'])))
        surface = (255,255,255) if theme == 'light' else (17,23,32)
        assert image.getpixel((db[0]-15,(db[1]+db[3])//2)) == surface, (theme,'divider margin must retain card surface')
        dot = next(r for r in rows if r.get('id') == 'settings-status-dot')
        crop = image.crop(tuple(map(int,re.findall(r'-?\d+',dot['bounds']))))
        assert Counter(crop.getdata())[(46,184,92)] > 300, (theme,'connected status dot')
    ui.navigate('settings-style', 'appearance-system'); ui.choose('appearance-light'); top()
    (folder / 'settings-root-results.json').write_text(json.dumps(dict(ping=bool(fixture_log),disconnect=True,reconnect=True,disabledActions=True,hostNavigation=True,themes=['light','dark']),indent=2))
    passed('P7-settings-connected-root-F', '生产设置页浅深/状态点/真实偏好切换；Ping夹具响应、主动断开禁用远程操作、重连恢复与主机管理返回通过；G和完整V另验')
