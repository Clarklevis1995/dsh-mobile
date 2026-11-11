#!/usr/bin/env python3
"""将原始品牌 SVG 轮廓按 60×60 EVEN_ODD 采样为 Canvas 粒子，不依赖平台路径命中 API。"""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'DeepSeekHarnessMobile/Resources/Assets.xcassets/HeroWhaleMask.imageset/hero-whale.svg'
OUTPUT = ROOT / 'harmonyApp/entry/src/main/ets/core/BrandPixels.ets'

def contours(path):
    tokens = re.findall(r'[MCVZ]|[-+]?(?:\d*\.\d+|\d+)(?:[eE][-+]?\d+)?', path)
    result, polygon, point, at, command = [], [], (0, 0), 0, ''
    while at < len(tokens):
        if tokens[at] in ['M', 'C', 'V', 'Z']:
            command = tokens[at]; at += 1
        if command == 'Z':
            result.append(polygon); polygon = []; command = ''; continue
        if command == 'M':
            point = (float(tokens[at]), float(tokens[at + 1])); at += 2; polygon.append(point)
        elif command == 'V':
            point = (point[0], float(tokens[at])); at += 1; polygon.append(point)
        elif command == 'C':
            values = [float(value) for value in tokens[at:at + 6]]; at += 6
            start = point; end = (values[4], values[5])
            for index in range(1, 33):
                t = index / 32; u = 1 - t
                polygon.append((u**3 * start[0] + 3*u*u*t*values[0] + 3*u*t*t*values[2] + t**3*end[0],
                                u**3 * start[1] + 3*u*u*t*values[1] + 3*u*t*t*values[3] + t**3*end[1]))
            point = end
        else:
            raise ValueError('不支持的 SVG 路径命令')
    if polygon: result.append(polygon)
    return result

def inside(x, y, polygons):
    hit = False
    for polygon in polygons:
        previous = polygon[-1]
        for current in polygon:
            if (current[1] > y) != (previous[1] > y) and x < (previous[0] - current[0]) * (y - current[1]) / (previous[1] - current[1]) + current[0]:
                hit = not hit
            previous = current
    return hit

if __name__ == '__main__':
    polygons = [polygon for element in ET.parse(SOURCE).getroot() for polygon in contours(element.get('d', ''))]
    pixels = [[round((x + .5) / 60, 6), round((y + .5) / 60, 6)] for y in range(60) for x in range(60)
              if inside((x + .5) / 60 * 24, (y + .5) / 60 * 24 - 3, polygons)]
    OUTPUT.write_text('// 来自原始 hero-whale.svg；由 harmony/testing/generate_brand_pixels.py 生成。\n'
                      + 'export const BRAND_PIXELS: number[][] = ' + json.dumps(pixels, separators=(',', ':')) + ';\n')
    print('品牌轮廓采样完成：', len(pixels), '个粒子')
