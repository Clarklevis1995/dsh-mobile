#!/usr/bin/env python3
# coding: utf-8
"""冻结Agent页共有标签/卡片几何限定V，不移动或裁剪原图。"""
import argparse
import json
from pathlib import Path
import re
from compare_home_geometry import attributes


def main():
    p=argparse.ArgumentParser();p.add_argument('--android-layout',type=Path,required=True);p.add_argument('--harmony-layout',type=Path,required=True);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    a=[r for w in json.loads(args.android_layout.read_text()) for r in w.get('content',[])];h=list(attributes(json.loads(args.harmony_layout.read_text())))
    def box(r):return list(map(int,re.findall(r'-?\d+',r['bounds'])))
    def cy(r):b=box(r);return (b[1]+b[3])/2
    aa=cy(next(r for r in a if r.get('content-desc')=='返回'));ha=cy(next(r for r in h if r.get('id')=='page-back'))
    checks=[]
    def check(label,difference):checks.append(dict(metric=label,differenceVp=round(difference/3.5,4),passed=abs(difference/3.5)<=2))
    labels=['Agent 预设','预设决定 Agent 使用的工具、提示词与能力。选择后只对新建会话生效。','构建 Agent','build','当前使用','由 DeepSeek Harness 提供的 Agent 预设。','阅读 Agent','read']
    for text in labels:
        left=sorted([r for r in a if r.get('text')==text],key=cy);right=sorted([r for r in h if r.get('text')==text],key=cy);assert len(left)==len(right) and left,(text,len(left),len(right))
        for i,(ar,hr) in enumerate(zip(left,right)):
            check(text+str(i)+'.centerY',cy(hr)-ha-cy(ar)+aa)
            if text not in ['Agent 预设','build','read','当前使用']:check(text+str(i)+'.left',box(hr)[0]-box(ar)[0])
    cards=sorted([r for r in a if 'CLICKABLE' in r.get('interactions',[]) and box(r)[2]-box(r)[0]>1000],key=cy)
    assert len(cards)==2
    for preset,ar in zip(['build','read'],cards):
        hr=next(r for r in h if r.get('id')=='agent-preset-card-'+preset);ab=box(ar);hb=box(hr)
        for i,name in enumerate(['left','top','right','bottom']):check(preset+'.card.'+name,hb[i]-ab[i]-(ha-aa if i in [1,3] else 0))
    result=dict(scope='376逻辑宽/标准字号/相同静态Agent目录和默认值；按返回按钮归一系统安全区，只比较标签与卡片矩形，字形/系统触摸框/完整异常V另验',checks=checks)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2));assert all(r['passed'] for r in checks),result;print('PASS',len(checks),'Agent metrics <=2vp')


if __name__=='__main__':main()
