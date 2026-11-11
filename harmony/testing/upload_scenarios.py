"""系统选择器须验证系统界面节点，不能把仍停留在会话页算作打开成功。"""
import time
import re
import os
import uuid
import shlex
import hashlib


def run(ui, m, folder, passed):
    def sys_click(text):
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            rows = ui.nodes(system=True); row = next((row for row in reversed(rows) if row.get('text') == text), None)
            if row:
                left, top, right, bottom = map(int, re.findall(r'-?\d+', row['bounds']))
                m.hdc('shell', 'uitest', 'uiInput', 'click', str((left+right)//2), str((top+bottom)//2)); time.sleep(.7); return
            time.sleep(.4)
        raise AssertionError('未找到系统控件：' + text)
    m.connect(ui); ui.wait('message-attach'); ui.click('message-attach'); ui.wait('attach-files'); time.sleep(.5); ui.click('attach-files')
    deadline = time.monotonic() + 30
    labels = {'最近', '浏览', '取消', '文件', '我的手机', '内部存储', '设备', '位置'}
    found = set(); retried = False
    while time.monotonic() < deadline:
        nodes = ui.nodes(system=True)
        found = {row.get('text', '') for row in nodes if row.get('text', '') in labels}
        if len(found) >= 2: break
        if not retried and ui.find('attach-files'):
            retried = True; time.sleep(.5); ui.click('attach-files')
        time.sleep(.5)
    assert len(found) >= 2, '未确认系统选择器界面，仅发现标准标题：' + repr(sorted(found))
    if os.environ.get('HARMONY_UPLOAD_SELECT') == '1':
        name = 'harmony-文件验收 ' + uuid.uuid4().hex[:8] + '.txt'
        original = ('鸿蒙分块上传正文\t' + 'x' * 200 + '\n').encode() * 2500
        source = folder / 'upload-source.txt'; source.write_bytes(original)
        remote = '/storage/media/100/local/files/Docs/Download/' + name
        m.hdc('file', 'send', str(source), remote)
        try:
            sys_click('浏览'); sys_click('我的手机')
            ui.nodes(system=True); ui.screenshot('system-phone-folders')
            rows = ui.nodes(system=True); download = next((row.get('text') for row in rows if row.get('text') in ['Download', '下载']), None)
            assert download, '我的手机尚未找到下载目录'
            sys_click(download); sys_click(name)
            # 单选选择器可在选中文件后直接返回，多选形态才显示完成。
            if not ui.find('message-draft'): sys_click('完成')
            ui.wait('message-draft')
            if os.environ.get('HARMONY_UPLOAD_FAILURES') == '1':
                ui.text('受控上传失败，请重试',timeout=30)
                retry=next(row['id'] for row in ui.nodes() if row.get('id','').startswith('upload-retry-'))
                ui.screenshot('file-upload-failed');ui.click(retry)
            ui.text('已就绪', timeout=30); ui.text(name); ui.screenshot('file-upload-ready')
            ui.click('message-send'); ui.text('流式回复验证完成', timeout=25)
            assert not any(row.get('id', '').startswith('upload-status-') for row in ui.nodes())
            deadline=time.monotonic()+15
            while time.monotonic()<deadline:
                cards=[row for row in ui.nodes() if row.get('id','').startswith('message-file-fixture-file-fixture-upload-')]
                if cards:break
                time.sleep(.4)
            assert cards,'未看到当前上传凭证对应的文件卡片'
            ui.text(name);ui.screenshot('file-upload-sent-card')
            passed('P5-file-upload-F', '实际系统选择中文空格文件名，后台私有副本/摘要、多块偏移确认、附件单独发送及确认后移除通过；原文 SHA-256=' + hashlib.sha256(original).hexdigest())
            if os.environ.get('HARMONY_UPLOAD_FAILURES') == '1':
                assert 'upload-failed-once' in (folder/'gateway-18783.log').read_text()
                passed('P5-file-upload-retry-F','实际系统文件上传遭服务端拒绝后显示原生错误，用户重试从新上传开始，最终摘要及发送确认通过')
                second='harmony-cancel-'+uuid.uuid4().hex[:8]+'.txt';second_remote='/storage/media/100/local/files/Docs/Download/'+second
                m.hdc('file','send',str(source),second_remote)
                try:
                    ui.input('message-draft','取消上传保留文字')
                    ui.click('message-attach');ui.click('attach-files');time.sleep(1)
                    # 文件选择器可能记住上次 Download，按实际节点判定，避免无条件重复导航。
                    if not any(row.get('text')==second for row in ui.nodes(system=True)):
                        sys_click('浏览');sys_click('我的手机');sys_click(download)
                    sys_click(second)
                    if not ui.find('message-draft'):sys_click('完成')
                    deadline=time.monotonic()+20
                    while time.monotonic()<deadline:
                        removes=[row['id'] for row in ui.nodes() if row.get('id','').startswith('upload-remove-')]
                        if removes:break
                        time.sleep(.3)
                    assert removes
                    ui.click(removes[0]);ui.screenshot('file-upload-canceled')
                    deadline=time.monotonic()+25
                    while 'upload-cancel-confirmed '+second not in (folder/'gateway-18783.log').read_text():
                        assert time.monotonic()<deadline,'服务端未确认取消'
                        time.sleep(.4)
                    assert not any(row.get('id','').startswith('upload-status-') for row in ui.nodes())
                    assert ui.wait('message-draft').get('text')=='取消上传保留文字'
                    passed('P5-file-upload-cancel-late-F','上传期间移除附件向服务端取消，迟到分块确认不重建草稿附件，文字输入保持')
                finally:m.hdc('shell','rm','-f',shlex.quote(second_remote))

        finally: m.hdc('shell', 'rm', '-f', shlex.quote(remote))
        return
    if os.environ.get('HARMONY_UPLOAD_BROWSE') == '1':
        rows = ui.nodes(system=True); row = next(row for row in rows if row.get('text') == '浏览')
        left, top, right, bottom = map(int, re.findall(r'-?\d+', row['bounds']))
        m.hdc('shell', 'uitest', 'uiInput', 'click', str((left+right)//2), str((top+bottom)//2))
        time.sleep(1); ui.nodes(system=True); ui.screenshot('system-file-browser')
    ui.screenshot('system-file-picker-confirmed')
    passed('P5-file-picker-open-F', '正式原生输入器打开系统文件选择器，已验证系统界面标题节点；尚不包含选择、上传和发送')
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back'); ui.wait('message-draft')
    assert ui.find('message-draft').get('text', '') == ''
    assert not any(row.get('id', '').startswith('upload-status-') for row in ui.nodes())
    passed('P5-file-picker-cancel-F', '取消系统选择器返回原会话，未创建附件或修改文字草稿')
