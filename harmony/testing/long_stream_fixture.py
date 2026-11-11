# coding: utf-8
"""固定长流样本：千行代码、160行表格与未闭合语法的逐段到达。"""
def content():
    code='\n'.join('val sampleLine%04d = "鲸鱼中文与 English source %04d"' % (n,n) for n in range(1000))
    table='\n'.join('| 第%03d行 | long_table_value_%03d | %d |' % (n,n,n) for n in range(160))
    return '# 持续流式长内容验收\n\n**加粗中文**与 `inline_code`，以及[链接](https://example.com)。\n\n```kotlin\n'+code+'\n```\n\n| 名称 | 说明 | 数量 |\n| :--- | :---: | ---: |\n'+table+'\n\nP7_LONG_STREAM_FINAL'

def digest(text):
    raw=text.encode('utf-16-le');value=2166136261
    for n in range(0,len(raw),2):value=((value^(raw[n]+256*raw[n+1]))*16777619)&0xffffffff
    return dict(length=len(raw)//2,hash=str(value))
