# coding: utf-8
"""只读系统PSS/映射分类；不触发GC，不改变设备内存或性能门禁。"""
import json
import re
import time


def record(m,folder,label):
    assert m.BUNDLE=='com.clarklevis.whaleness.harmony.smoke','内存诊断仅限本轮独立测试包'
    assert re.fullmatch(r'[a-zA-Z0-9_-]+',label)
    pid=m.hdc('shell','pidof',m.BUNDLE).strip()
    assert re.fullmatch(r'\d+',pid),'必须唯一定位测试包进程'
    command=m.hdc('shell','cat','/proc/'+pid+'/cmdline')
    assert command.split('\x00')[0]==m.BUNDLE,'进程归属变化，停止采样'
    target=folder/'memory-detail';target.mkdir(exist_ok=True)
    started=time.monotonic()
    commands=[('categories',['hidumper','--mem',pid]),('smaps',['hidumper','--mem-smaps',pid]),('threads',['ps','-T','-p',pid])]
    results={}
    for name,args in commands:
        output=m.hdc('shell',*args);(target/(label+'-'+name+'.txt')).write_text(output)
        results[name]=dict(bytes=len(output.encode()),file=label+'-'+name+'.txt')
    (target/(label+'-scope.json')).write_text(json.dumps(dict(pid=int(pid),bundle=m.BUNDLE,elapsedSeconds=round(time.monotonic()-started,3),commands=results,method='只读hidumper内存分类/smaps与线程表；无heap dump/GC/pressure；诊断采样有额外耗时，不能作优化收益结论'),ensure_ascii=False,indent=2))
