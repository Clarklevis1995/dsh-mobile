# coding: utf-8
"""保留Android原始表格矢量的分组旋转与透明度，生成可复现的ArkUI单色SVG。"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = '{http://schemas.android.com/apk/res/android}'
SVG = '{http://www.w3.org/2000/svg}'


def convert(source, target):
    for node in source:
        if node.tag == 'group':
            rotation = node.get(ANDROID + 'rotation', '0')
            group = ET.SubElement(target, SVG + 'g', {'transform': 'rotate(' + rotation + ' ' +
                node.get(ANDROID + 'pivotX', '0') + ' ' + node.get(ANDROID + 'pivotY', '0') + ')'})
            convert(node, group)
        elif node.tag == 'path':
            attrs = {'d': node.get(ANDROID + 'pathData'), 'fill': 'none', 'stroke': '#FFFFFF',
                     'stroke-width': node.get(ANDROID + 'strokeWidth', '1'),
                     'stroke-linecap': node.get(ANDROID + 'strokeLineCap', 'round'),
                     'stroke-linejoin': node.get(ANDROID + 'strokeLineJoin', 'round'),
                     'stroke-opacity': node.get(ANDROID + 'strokeAlpha', '1')}
            ET.SubElement(target, SVG + 'path', attrs)


if __name__ == '__main__':
    ET.register_namespace('', 'http://www.w3.org/2000/svg')
    for name in ['table_copy', 'table_download', 'table_expand', 'table_rotate']:
        source = ET.parse(ROOT / 'androidApp/src/main/res/drawable' / ('ic_' + name + '.xml')).getroot()
        target = ET.Element(SVG + 'svg', {'viewBox': '0 0 24 24'})
        convert(source, target)
        (ROOT / 'harmonyApp/entry/src/main/resources/rawfile/icons' / (name + '.svg')).write_text(ET.tostring(target, encoding='unicode'))
    print('已生成4个原始表格图标（含分组旋转与透明度）')
