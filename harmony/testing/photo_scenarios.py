"""系统相册专项；只选择本轮生成的三张纯色 EXIF 测试图。"""
import time,json,re

def run(ui,m,folder,passed):
    def system_click(value):
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            rows=ui.nodes(system=True);row=next((r for r in reversed(rows) if value in [r.get('id'),r.get('text'),r.get('accessibilityText')]),None)
            if row:
                l,t,r,b=map(int,re.findall(r'-?\d+',row['bounds']));m.hdc('shell','uitest','uiInput','click',str((l+r)//2),str((t+b)//2));time.sleep(.8);return
            time.sleep(.3)
        raise AssertionError('system control '+value)
    ui.wait('home-settings');ui.click('test-seed-photos');system_click('advanced_dialog_button_1')
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        status=ui.find('test-seed-photos').get('text','')
        if status.startswith(('seeded-','failed-')):break
        time.sleep(.4)
    assert status=='seeded-3',status
    uris=json.loads(ui.find('test-photo-uris')['text']);(folder/'owned-photo-uris.json').write_text(json.dumps(uris))
    try:
        m.connect(ui);ui.input('message-draft','相册正文不能丢失')
        ui.click('message-attach');ui.click('attach-images');time.sleep(2)
        ui.nodes(system=True);ui.screenshot('system-photo-picker')
        (folder/'picker-layout.json').write_text(json.dumps(ui.nodes(system=True),ensure_ascii=False,indent=2))
        selectors=[r for r in ui.nodes(system=True) if r.get('id','').startswith('Selector_')]
        assert len(selectors)==3, '必须只有本轮生成的三张图片，避免选择其他媒体'
        m.hdc('shell','uitest','uiInput','keyEvent','Back');ui.wait('message-draft')
        assert ui.find('message-draft').get('text')=='相册正文不能丢失'
        assert not any(r.get('id','').startswith('photo-draft-') for r in ui.nodes())
        passed('P5-photo-picker-cancel-F','实际系统相册取消后无附件，保留文字草稿')
        ui.click('message-attach');ui.click('attach-images');time.sleep(1)
        for n in range(3):system_click('Selector_'+str(n))
        system_click('完成')
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            photos=[r for r in ui.nodes() if r.get('id','').startswith('photo-draft-')]
            if len(photos)==3:break
            time.sleep(.4)
        assert len(photos)==3, '未准备三张实际相册图片'
        assert ui.find('message-draft').get('text')=='相册正文不能丢失'
        ui.screenshot('photo-drafts-three')
        ui.click(photos[0]['id']);ui.wait('photo-preview');ui.text('图片 1 / 3');ui.screenshot('photo-draft-preview')
        row,x,y=ui.control('photo-preview');l,t,r,b=map(int,re.findall(r'-?\d+',row['bounds']))
        m.hdc('shell','uitest','uiInput','swipe',str(r-70),str((t+b)//2),str(l+70),str((t+b)//2),'1200');ui.text('图片 2 / 3')
        ui.screenshot('photo-draft-preview-second');ui.navigate('page-back','message-draft')
        ui.click(photos[-1]['id'].replace('photo-draft-','photo-remove-'))
        assert len([r for r in ui.nodes() if r.get('id','').startswith('photo-draft-')])==2
        ui.screenshot('photo-drafts-after-remove');ui.click('message-send');ui.text('流式回复验证完成',timeout=25)
        assert not any(r.get('id','').startswith('photo-draft-') for r in ui.nodes())
        assert ui.find('message-draft').get('text','')==''
        deadline=time.monotonic()+20
        while time.monotonic()<deadline:
            images=[r for r in ui.nodes() if r.get('id','').startswith('image-attachment-')]
            if images and str(images[0].get('enabled')).lower()!='false':break
            ui.scroll(False)
        assert images,'发送后的消息未显示图片卡片'
        ui.screenshot('photo-message-thumbnails');ui.click(images[0]['id']);ui.wait('photo-preview');ui.text('图片 1 / 2')
        ui.screenshot('photo-received-preview');ui.navigate('page-back','message-draft')
        log=(folder/'gateway-18783.log').read_text();assert 'photo-payload-validated 2' in log,log[-2000:]
        passed('P5-photo-send-preview-F','真实系统相册三选二、TaskPool规范化JPEG、移除、草稿预览及切图、正文与图片发送、远端按字节解码核对、消息下载缩略图和收到后预览通过')
    finally:
        m.hdc('shell','uitest','uiInput','keyEvent','Back')
        for uri in uris:m.hdc('shell','mediatool','delete',uri)
