#!/usr/bin/env python3
# coding: utf-8
"""同376逻辑宽的未配对首页限定V；比较共有标签纵向中心，不移动原图。"""
import argparse
import json
from pathlib import Path
import re

LABELS = ['探索未至之境', 'Deepseek Harness 预览版', '未分组', '0 个未归属会话', '新建会话', '最近会话', '未连接', '搜索会话内容', '暂无已知会话。连接服务后创建第一个任务。']


def attributes(value):
    if isinstance(value, dict):
        if 'attributes' in value: yield value['attributes']
        for key, child in value.items():
            if key != 'attributes': yield from attributes(child)
    elif isinstance(value, list):
        for child in value: yield from attributes(child)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--android-layout', type=Path, required=True)
    parser.add_argument('--harmony-layout', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    android = [row for window in json.loads(args.android_layout.read_text()) for row in window.get('content', [])]
    harmony = list(attributes(json.loads(args.harmony_layout.read_text())))
    checks = []
    for label in LABELS:
        a = next(row for row in android if row.get('text') == label)
        h = next(row for row in harmony if row.get('text') == label or label == '搜索会话内容' and row.get('id') == 'session-search')
        ab = list(map(int, re.findall(r'-?\d+', a['bounds'])))
        hb = list(map(int, re.findall(r'-?\d+', h['bounds'])))
        difference = ((hb[1]+hb[3])-(ab[1]+ab[3])) / 7
        checks.append(dict(label=label, androidCenterPx=(ab[1]+ab[3])/2, harmonyCenterPx=(hb[1]+hb[3])/2, differenceVp=round(difference, 4), passed=abs(difference) <= 2))
    value = dict(scope='仅共有标签实际屏幕纵向中心；相同1316x2832/3.5 density/376逻辑宽，100%字号，均未配对，不调整原图', checks=checks)
    args.output.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    assert all(row['passed'] for row in checks), checks
    print('PASS', len(checks), 'shared home labels <=2vp')


if __name__ == '__main__': main()
