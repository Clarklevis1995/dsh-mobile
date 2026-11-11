#!/usr/bin/env python3
"""从仓库原始品牌和 Android 单色路径生成 ArkUI SVG；保留 MIT 与字体许可。"""
from pathlib import Path
import copy
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / 'harmonyApp/entry/src/main/resources/rawfile'
ASSETS = ROOT / 'DeepSeekHarnessMobile/Resources/Assets.xcassets'
SVG = '{http://www.w3.org/2000/svg}'
ANDROID = '{http://schemas.android.com/apk/res/android}'
ICONS = ['back_chevron', 'chevron_right', 'settings', 'gateway_auth', 'menu_circle', 'folder_outline',
         'chevrons_up_down', 'add', 'search', 'session_rename', 'session_archive', 'host_selected', 'menu_check',
         'gateway_pc', 'gateway_server', 'question_chevron_down', 'voice_waveform', 'composer_keyboard', 'arrow_up', 'copy_message', 'more_horizontal', 'conversation_sidebar', 'pause_circle', 'play_circle', 'pencil_line', 'trash']

if __name__ == '__main__':
    ET.register_namespace('', 'http://www.w3.org/2000/svg')
    (RAW / 'icons').mkdir(parents=True, exist_ok=True); (RAW / 'branding').mkdir(exist_ok=True)
    for name in ICONS:
        source = ET.parse(ROOT / 'androidApp/src/main/res/drawable' / ('ic_' + name + '.xml')).getroot()
        target = ET.Element(SVG + 'svg', {'viewBox': '0 0 ' + source.get(ANDROID + 'viewportWidth', '24') + ' ' + source.get(ANDROID + 'viewportHeight', '24')})
        for node in source.iter('path'):
            def monochrome(value):
                return 'none' if value in ['none', None, '@android:color/transparent', '#00000000'] else '#FFFFFF'
            attributes = {'d': node.get(ANDROID + 'pathData', ''), 'fill': monochrome(node.get(ANDROID + 'fillColor'))}
            if node.get(ANDROID + 'strokeColor'):
                attributes.update({'stroke': monochrome(node.get(ANDROID + 'strokeColor')), 'stroke-width': node.get(ANDROID + 'strokeWidth', '1'),
                                   'stroke-linecap': node.get(ANDROID + 'strokeLineCap', 'round'), 'stroke-linejoin': node.get(ANDROID + 'strokeLineJoin', 'round')})
            attributes['fill-rule'] = 'evenodd' if node.get(ANDROID + 'fillType') == 'evenOdd' else 'nonzero'
            ET.SubElement(target, SVG + 'path', attributes)
        (RAW / 'icons' / (name + '.svg')).write_text(ET.tostring(target, encoding='unicode'))
    wordmark = ET.Element(SVG + 'svg', {'width': '182', 'height': '24', 'viewBox': '0 0 182 24'})
    orca = ET.parse(ASSETS / 'DshOrcaMark.imageset/dsh-orca-mark.svg').getroot()
    group = ET.SubElement(wordmark, SVG + 'g', {'transform': 'translate(0 0.68) scale(0.027210884)'})
    for node in orca:
        item = copy.deepcopy(node); item.set('fill', '#FFFFFF'); group.append(item)
    source = ET.parse(ASSETS / 'WhalenessHarnessWordmark.imageset/whaleness-harness.svg').getroot()
    for node in source:
        item = copy.deepcopy(node)
        if item.get('fill') == '#000000': item.set('fill', '#FFFFFF')
        elif item.get('fill') == '#FFFFFF': item.set('fill', '#06172B')
        wordmark.append(item)
    (RAW / 'branding/wordmark.svg').write_text(ET.tostring(wordmark, encoding='unicode'))
    shutil.copyfile(ASSETS / 'HeroWhaleMask.imageset/hero-whale.svg', RAW / 'branding/whale.svg')
    shutil.copyfile(ROOT / 'LICENSE', RAW / 'branding/LICENSE.txt')
    shutil.copyfile(ROOT / 'Design/whaleness-wordmark/Montserrat-OFL.txt', RAW / 'branding/Montserrat-OFL.txt')
    print('品牌资源已生成：', len(ICONS), '个图标及原始品牌路径')
