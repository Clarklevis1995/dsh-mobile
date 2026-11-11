#!/usr/bin/env python3
"""将原始品牌 SVG 轮廓按冻结基准72/100网格与EVEN_ODD抗锯齿采样为Canvas粒子，不依赖平台路径命中 API。"""
from pathlib import Path
import json
from bisect import bisect_right
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

def particles(size, polygons):
    # 每个子像素扫描线一次求交点；4×4覆盖率近似原生Canvas边缘抗锯齿。
    coverage = [[0 for _ in range(size)] for _ in range(size)]
    for sy in range(size * 4):
        y = (sy + .5) / (size * 4) * 24 - 3
        crossings = []
        for polygon in polygons:
            previous = polygon[-1]
            for current in polygon:
                if (current[1] > y) != (previous[1] > y):
                    crossings.append((previous[0]-current[0])*(y-current[1])/(previous[1]-current[1])+current[0])
                previous = current
        crossings.sort()
        for sx in range(size * 4):
            x = (sx + .5) / (size * 4) * 24
            if bisect_right(crossings, x) % 2:
                coverage[sy // 4][sx // 4] += 1
    def contains(x, y):
        return 0 <= x < size and 0 <= y < size and coverage[y][x] / 16 > .2
    result = []
    for y in range(size):
        for x in range(size):
            if not contains(x,y): continue
            if not any(contains(x+dx,y+dy) for dy in range(-2,3) for dx in range(-2,3) if dx or dy): continue
            missing = sum(not contains(x+dx,y+dy) for dy in range(-1,2) for dx in range(-1,2) if dx or dy)
            result.append([round((x+.5)/size,6),round((y+.5)/size,6),coverage[y][x]/16,missing/8])
    return result

if __name__ == '__main__':
    polygons = [polygon for element in ET.parse(SOURCE).getroot() for polygon in contours(element.get('d', ''))]
    phone, wide = particles(72,polygons), particles(100,polygons)
    OUTPUT.write_text('// 来自原始hero-whale.svg，按冻结Android的72/100网格生成；字段x/y/亮度/边缘比例。\n'
                      + 'export const BRAND_PIXELS: number[][] = ' + json.dumps(phone,separators=(',', ':')) + ';\n'
                      + 'export const BRAND_WIDE_PIXELS: number[][] = ' + json.dumps(wide,separators=(',', ':')) + ';\n')
    print('品牌轮廓采样完成：',len(phone),'手机粒子，',len(wide),'宽屏粒子')
