#!/usr/bin/env python3
# coding: utf-8
"""默认模型页共有标签限定V；原图不移动，纵向按返回按钮归一系统安全区。"""
import argparse
import json
from pathlib import Path
import re
from compare_home_geometry import attributes

LABELS=['默认模型','共享模型','读取模型','当前使用','推理模型','思考等级','低','高','不可用模型']


def main():
    p=argparse.ArgumentParser();p.add_argument('--android-layout',required=True,type=Path);p.add_argument('--harmony-layout',required=True,type=Path);p.add_argument('--output',required=True,type=Path);args=p.parse_args()
    a=[r for w in json.loads(args.android_layout.read_text()) for r in w.get('content',[])];h=list(attributes(json.loads(args.harmony_layout.read_text())))
    def bounds(r):return list(map(int,re.findall(r'-?\d+',r['bounds'])))
    def center(r):b=bounds(r);return (b[1]+b[3])/2
    aa=center(next(r for r in a if r.get('content-desc')=='返回'));ha=center(next(r for r in h if r.get('id')=='page-back'))
    checks=[]
    for text in LABELS:
        left=sorted([r for r in a if r.get('text')==text],key=center);right=sorted([r for r in h if r.get('text')==text],key=center)
        assert len(left)==len(right) and left,(text,len(left),len(right))
        for index,(ar,hr) in enumerate(zip(left,right)):
            difference=(center(hr)-ha-center(ar)+aa)/3.5
            checks.append(dict(text=text,occurrence=index,differenceVp=round(difference,4),passed=abs(difference)<=2))
    result=dict(scope='同源静态夹具相同模型/默认值、376逻辑宽/100%字号；只比较10个共有标签纵向中心，非整个页面或原生触摸框V，不移动截图',checks=checks)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2));assert all(r['passed'] for r in checks),result;print('PASS',len(checks),'default model labels <=2vp')


if __name__=='__main__':main()
