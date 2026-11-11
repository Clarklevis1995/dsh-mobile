"""实际 TaskPool 写盘、原生 Markdown／图片／PDF 阅读的夹具 F。"""
import time,os,json


def run(ui, m, folder, passed):
    m.connect(ui); ui.wait('message-draft'); ui.navigate('conversation-files', 'files-refresh')
    ui.navigate('workspace-file-资料', 'workspace-file-资料/报告 空格.md')
    ui.navigate('workspace-file-资料/报告 空格.md', 'file-preview-save'); ui.text('原生文件预览', timeout=25); ui.text('worker native syntax', timeout=25); ui.text('完整校验', timeout=25)
    assert not ui.find('file-preview-error'); ui.screenshot('native-Markdown-code-table-file')
    if os.environ.get('HARMONY_FILES_SAVE') == '1': save_round_trip(ui,m,folder,passed)
    if os.environ.get('HARMONY_FILES_SYSTEM') == '1': system_actions(ui,m,folder,passed)
    ui.click('file-preview-mode'); ui.wait('file-preview-text'); assert '```kotlin' in ui.find('file-preview-text').get('text', '')
    ui.navigate('page-back', 'files-refresh'); ui.navigate('workspace-file-资料/示例.pdf', 'native-pdf-view'); ui.wait('pdf-pages', timeout=25)
    assert ui.find('pdf-pages').get('text') == '1 页'; assert not ui.find('pdf-error'); ui.screenshot('native-PDF-file')
    ui.navigate('page-back', 'files-refresh'); ui.navigate('files-parent', 'workspace-file-图片.png'); ui.navigate('workspace-file-图片.png', 'file-preview-save'); time.sleep(1); ui.screenshot('native-image-file')
    ui.navigate('page-back', 'files-refresh'); ui.navigate('workspace-file-分块验收.txt', 'file-preview-text'); ui.text('后台逐块写入与完整校验', timeout=30)
    assert not ui.find('file-preview-error'); ui.screenshot('native-multichunk-text-file')
    ui.navigate('page-back', 'files-refresh'); ui.click('workspace-file-损坏下载.txt'); ui.wait('files-error', timeout=20)
    assert not ui.find('file-preview-save'), '摘要错误文件仍进入预览'; ui.screenshot('bad-download-digest-rejected')
    passed('P5-workspace-files-F', '实际目录／中文空格路径，下载背压与落盘、Markdown／代码／表格、单页原生 PDF、图片和多块文本阅读；错误摘要拒绝预览')


def system_actions(ui,m,folder,passed):
    before=[r.get('text') for r in ui.nodes() if r.get('text')]
    ui.click('file-preview-save');time.sleep(2)
    rows=ui.nodes(system=True);(folder/'save-system-layout.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
    texts=[r.get('text','') for r in rows if r.get('text')];print('SAVE_SYSTEM',texts,flush=True)
    assert any(any(t in text for t in ['新建文件夹','我的手机','文件管理','保存至','保存到']) for text in texts),'未打开系统保存选择器'
    ui.screenshot('system-document-save-picker')
    m.hdc('shell','uitest','uiInput','keyEvent','Back');ui.wait('file-preview-save')
    assert not ui.find('file-preview-error');ui.text('原生文件预览');ui.text('完整校验')
    ui.click('file-preview-share');time.sleep(2)
    rows=ui.nodes(system=True);(folder/'share-system-layout.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
    texts=[r.get('text','') for r in rows if r.get('text')];print('SHARE_SYSTEM',texts,flush=True)
    assert sum('分享' in text for text in texts)>=2,'未打开系统分享面板'
    ui.screenshot('system-file-share-panel')
    m.hdc('shell','uitest','uiInput','keyEvent','Back');ui.wait('file-preview-save');ui.text('原生文件预览');assert not ui.find('file-preview-error')
    passed('P5-file-save-share-cancel-F','真实DocumentViewPicker保存与ShareKit分享面板打开/系统返回，原生预览继续可读且无错误；不发送给任何外部对象')


def save_round_trip(ui,m,folder,passed):
    import re,uuid,hashlib
    from download_fixture import FILES
    name='harmony-export-'+uuid.uuid4().hex+'.md'
    def system_find(predicate):return next((r for r in reversed(ui.nodes(system=True)) if predicate(r)),None)
    def click(row):
        l,t,r,b=map(int,re.findall(r'-?\d+',row['bounds']));m.hdc('shell','uitest','uiInput','click',str((l+r)//2),str((t+b)//2));time.sleep(.6)
    ui.click('file-preview-save');time.sleep(1.5)
    row=system_find(lambda r:r.get('type')=='TextInput' and r.get('text')=='报告 空格.md');assert row;click(row)
    row=system_find(lambda r:r.get('type')=='TextInput' and str(r.get('focused')).lower()=='true');assert row
    m.hdc('shell','uitest','uiInput','keyEvent','2072','2017');m.hdc('shell','uitest','uiInput','keyEvent','2055');m.hdc('shell','uitest','uiInput','text',name)
    assert system_find(lambda r:r.get('type')=='TextInput' and r.get('text')==name),'系统保存文件名未逐字确认'
    m.hdc('shell','uitest','uiInput','keyEvent','Back')
    row=system_find(lambda r:r.get('text')=='Download');assert row;click(row);time.sleep(1)
    rows=ui.nodes(system=True);(folder/'save-download-layout.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
    print('SAVE_DOWNLOAD',[(r.get('id'),r.get('text'),r.get('type')) for r in rows if r.get('text')],flush=True)
    row=next((r for r in reversed(rows) if r.get('id')=='dialog_confirm' and str(r.get('enabled')).lower()!='false'),None);assert row;click(row)
    deadline=time.monotonic()+25
    while time.monotonic()<deadline:
        row=ui.find('test-export-receipt');value=row.get('text','') if row else ''
        if value:break
        time.sleep(.4)
    receipt=json.loads(value);expected=FILES['资料/报告 空格.md'][1]
    assert receipt['text'].encode()==expected;assert receipt['uri'].startswith('file://docs/') and name in receipt['uri']
    ui.screenshot('system-save-read-back-complete')
    (folder/'system-save-result.json').write_text(json.dumps(dict(name=name,bytes=len(expected),sha256=hashlib.sha256(expected).hexdigest(),readBackEquals=True,method='real DocumentViewPicker.save -> actual TaskPool export -> reopen granted destination URI -> byte equality; cleanup only owned filename'),ensure_ascii=False,indent=2))
    path='/storage/media/100/local/files/Docs/Download/'+name
    assert m.hdc('shell','cat',path).encode()==expected
    m.hdc('shell','rm',path)
    assert 'No such file' in m.hdc('shell','ls',path)
    receiptPath=folder/'system-save-result.json';value=json.loads(receiptPath.read_text());value.update(shellReadBackEquals=True,cleanup='removed only owned UUID file after byte validation');receiptPath.write_text(json.dumps(value,ensure_ascii=False,indent=2))
    passed('P5-file-save-roundtrip-F','系统选择Download与本轮唯一文件名，实际TaskPool保存并重开目标URI，173字节逐字一致；仅删除本轮导出文件')
