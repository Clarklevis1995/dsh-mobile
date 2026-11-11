#!/usr/bin/env python3
# coding: utf-8
"""同数据设置根页顶部：以返回按钮归一系统安全区，保留未经移动的原图。"""
import argparse
import json
from pathlib import Path
import re
from compare_home_geometry import attributes

LABELS = ['新会话默认配置', 'Agent 预设', '构建 Agent', '默认模型', '读取模型', '权限', '只读', 'Mobile Gateway', 'ws://127.0.0.1:18786/ws/mobile', '已连接', '断开连接', 'Ping 网关', 'DSH Host', '版本', '0.2.2-fixture']
VALUES = ['构建 Agent', '读取模型', '只读', '0.2.2-fixture']


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--android-layout', required=True, type=Path)
    p.add_argument('--harmony-layout', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    args = p.parse_args()
    a = [r for w in json.loads(args.android_layout.read_text()) for r in w.get('content', [])]
    h = list(attributes(json.loads(args.harmony_layout.read_text())))
    def bounds(row): return list(map(int, re.findall(r'-?\d+', row['bounds'])))
    def cy(row): b = bounds(row); return (b[1] + b[3]) / 2
    aa = cy(next(r for r in a if r.get('content-desc') == '返回'))
    ha = cy(next(r for r in h if r.get('id') == 'page-back'))
    checks = []
    for label in LABELS:
        ar = next(r for r in a if r.get('text') == label)
        hr = next(r for r in h if r.get('text') == label)
        difference = ((cy(hr)-ha)-(cy(ar)-aa))/3.5
        checks.append(dict(label=label, metric='centerYFromBack', differenceVp=round(difference,4), passed=abs(difference)<=2))
        if label in VALUES:
            difference = (bounds(hr)[2]-bounds(ar)[2])/3.5
            checks.append(dict(label=label, metric='valueRightEdge', differenceVp=round(difference,4), passed=abs(difference)<=2))
    result = dict(scope='同一18786静态夹具与1316x2832/560dpi/376逻辑宽；只扣除返回按钮中心对应的系统安全区差，不缩放或移动原图；顶部15标签纵向与4值右边缘，不替代全页V', checks=checks)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    assert all(r['passed'] for r in checks), result
    print('PASS',len(checks),'settings shared-label metrics <=2vp')


if __name__ == '__main__': main()
