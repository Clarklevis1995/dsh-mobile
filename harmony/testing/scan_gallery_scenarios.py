"""系统扫码从图库读取本轮有效二维码并使用生产配对链路；仅清理本轮媒体URI。"""
import json,re,time

def run(ui,m,folder,passed):
    def click(value):
        end=time.monotonic()+18
        while time.monotonic()<end:
            r=next((r for r in reversed(ui.nodes(system=True)) if value in [r.get('id'),r.get('text'),r.get('description')]),None)
            if r:
                b=list(map(int,re.findall(r'-?\d+',r['bounds'])));m.hdc('shell','uitest','uiInput','click',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.8);return
            time.sleep(.3)
        raise AssertionError('系统节点不可见 '+value)
    ui.wait('test-seed-photos');ui.click('test-seed-photos');click('advanced_dialog_button_1');end=time.monotonic()+20
    while time.monotonic()<end:
        status=ui.find('test-seed-photos').get('text','')
        if status.startswith(('seeded-','failed-')):break
        time.sleep(.3)
    assert status=='seeded-1',status
    uris=json.loads(ui.find('test-photo-uris')['text']);assert len(uris)==1 and uris[0].startswith('file://media/Photo/')
    (folder/'owned-qr-uri.json').write_text(json.dumps(uris))
    try:
        ui.navigate('home-auth','host-add');ui.navigate('host-add','gateway-scan');ui.click('gateway-scan');time.sleep(3)
        rows=ui.nodes(system=True);(folder/'scanner-layout.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
        print('SCAN_GALLERY_NODES',[(r.get('id'),r.get('text'),r.get('description'),r.get('type')) for r in rows if r.get('text') or r.get('description')],flush=True)
        click('scan_page_openPicker');time.sleep(1)
        rows=ui.nodes(system=True);(folder/'scanner-gallery-layout.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2));print('SCAN_ALBUM_NODES',[(r.get('id'),r.get('text'),r.get('description'),r.get('type')) for r in rows if r.get('id') or r.get('text')],flush=True)
        selectors=[r for r in rows if r.get('type')=='CachedImage' and uris[0] in r.get('id','')]
        assert len([r for r in rows if r.get('type')=='CachedImage'])==1, '图库出现其他媒体，停止选择'
        assert len(selectors)==1,'图库必须只有本轮唯一二维码，避免选择其他媒体'
        click(selectors[0]['id']);time.sleep(.5)
        rows=ui.nodes(system=True);(folder/'scanner-selected-layout.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
        print('SCAN_SELECTED_NODES',[(r.get('id'),r.get('text'),r.get('type')) for r in rows if r.get('text')],flush=True)
        click('完成');ui.wait('home-settings',timeout=30);ui.text('已连接',timeout=30)
        assert 'fixture-pair-accepted control' in (folder/'gateway-18783.log').read_text(),'未真正进入认证'
        ui.screenshot('scan-gallery-paired')
        m.hdc('shell','aa','force-stop',m.BUNDLE);m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE);ui.wait('home-settings');ui.text('已连接',timeout=25);ui.click('鸿蒙原生会话验证','text');ui.wait('message-draft');ui.message('echo');ui.text('流式回复验证完成',timeout=25);ui.screenshot('scan-gallery-cold-chat')
        passed('P5-system-scan-gallery-F','系统默认扫码图库识别本轮QR，Native校验后生产认证成功，凭据保存，删除仅本轮媒体')
    finally:
        m.hdc('shell','uitest','uiInput','keyEvent','Back')
        for uri in uris:m.hdc('shell','mediatool','delete',uri)
