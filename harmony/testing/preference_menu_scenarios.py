# coding: utf-8
"""生产偏好菜单：同帧布局、浅深实际截图及原生选择状态，不替换系统菜单。"""
import json
import re
import shutil
import time


def run(ui, m, folder, passed):
    def capture(name, prefix, selected):
        ui.wait(prefix + '-system')
        time.sleep(.8)
        rows = ui.nodes()
        shutil.copyfile(folder / 'layout.json', folder / (name + '-layout.json'))
        menu = next(row for row in reversed(rows) if row.get('type') == 'Menu')
        box = list(map(int, re.findall(r'-?\d+', menu['bounds'])))
        items = [row for row in rows if row.get('id', '').startswith(prefix + '-')]
        assert len(items) == 3
        bounds = [list(map(int, re.findall(r'-?\d+', item['bounds']))) for item in items]
        width = (box[2] - box[0]) / 3.5
        assert abs(width - 208) <= 2, (name, width)
        spacing = [(bounds[i + 1][1] - bounds[i][1]) / 3.5 for i in range(2)]
        assert all(abs(value - 48) <= 2 for value in spacing), spacing
        current = next(row for row in items if row['id'] == prefix + '-' + selected)
        assert str(current.get('selected')).lower() == 'true', current
        ui.screenshot(name)
        measurements.append(dict(name=name, widthVp=width, rowSpacingVp=spacing,
                                 selected=selected, bounds=box, source='生产ArkUI Menu'))

    measurements = []
    ui.navigate('home-settings', 'settings-style')
    for theme in ['light', 'dark']:
        ui.navigate('settings-style', 'appearance-system')
        ui.choose('appearance-' + theme)
        ui.navigate('settings-style', 'appearance-system')
        capture('harmony-appearance-' + theme, 'appearance', theme)
        m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
        ui.navigate('settings-language', 'language-system')
        capture('harmony-language-' + theme, 'language', 'system')
        m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
    ui.navigate('settings-language', 'language-system'); ui.choose('language-en')
    ui.text('Appearance'); ui.text('Agent presets')
    ui.navigate('settings-language', 'language-system')
    capture('harmony-language-selected-en', 'language', 'en')
    ui.choose('language-system')
    ui.navigate('settings-style', 'appearance-system'); ui.choose('appearance-light')
    (folder / 'preference-menu-measurements.json').write_text(json.dumps(measurements, ensure_ascii=False, indent=2))
    passed('P2-preference-menu-F', '原生外观/语言浅深菜单208vp、48vp行距和真实选中状态；英语切换后返回系统语言及浅色')
