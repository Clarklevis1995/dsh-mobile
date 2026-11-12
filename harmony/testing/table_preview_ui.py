# coding: utf-8
"""操作已安装的独立smoke，核对表格旋转/系统返回/草稿并采集无探针V原图。"""
import json
import os
from pathlib import Path
import re
import tempfile
import time
import uuid
import smoke_ui as m


def main():
    m.require_device()
    assert m.BUNDLE == 'com.clarklevis.whaleness.harmony.smoke'
    folder = Path(tempfile.mkdtemp(prefix='harmony-table-preview-ui-'))
    print('REPORT_DIR', folder, flush=True)
    ui = m.UI(folder)
    checks = []

    def wait(predicate, timeout=25):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = predicate()
            if result: return result
            time.sleep(.3)
        raise AssertionError('表格旋转/恢复未按时完成')

    def bounds(row): return list(map(int, re.findall(r'-?\d+', row['bounds'])))
    def landscape(identifier):
        row = ui.find(identifier)
        if not row: return None
        l, t, r, b = bounds(row)
        return r-l > b-t
    def reader():
        for _ in range(15):
            if ui.find('message-body-harmony-rich-3-table-preview-0:table'): break
            ui.scroll(False)
        ui.navigate('message-body-harmony-rich-3-table-preview-0:table', 'table-reader-copy')
        row = ui.wait('2 行', key='description'); assert row['id'] == 'table-reader-list'
        ui.text('名称'); ui.text('数量')
    def capture(name):
        time.sleep(.8)
        rows = ui.nodes(include_offscreen=True)
        (folder/(name+'-layout.json')).write_text(json.dumps(rows, ensure_ascii=False, indent=2))
        ui.screenshot(name)
    def record(case, **details):
        checks.append(dict(case=case, **details))
        (folder/'rotation-results.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2))
        print('PASS', case, flush=True)

    ui.wait('message-draft')
    original = ui.find('message-draft')['text']
    draft = '表格旋转与返回草稿 中文😀 ' + uuid.uuid4().hex[:8]
    ui.input('message-draft', draft)
    reader()
    if os.environ.get('HARMONY_TABLE_OPERATIONS') == '1':
        ui.click('table-reader-copy'); ui.navigate('page-back', 'message-draft')
        ui.input('message-draft', ''); ui.click('message-draft')
        m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', '2072', '2038')
        time.sleep(.5); m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
        assert ui.find('message-draft')['text'] == '名称\t数量\t备注\n鲸鱼\t2\t中文与 long_table_cell_that_wraps\n海豚\t3\t粗体'
        ui.input('message-draft', draft); reader()
        from table_export_scenarios import run as operations
        operations(ui, m, folder, lambda task, detail: record(task, detail=detail))
        assert ui.find('message-draft')['text'] == draft
        reader()
    capture('harmony-table-preview-light')
    ui.click('table-reader-rotate')
    wait(lambda: landscape('table-reader') is True)
    ui.text('中文与 long_table_cell_that_wraps')
    capture('harmony-table-preview-landscape-light')
    # 使用物理边缘返回；普通应用swipe不能替代鸿蒙系统返回手势。
    from PIL import Image
    width, height = Image.open(folder/'harmony-table-preview-landscape-light.png').size
    assert width > height
    m.hdc('shell', 'uinput', '-T', '-m', '2', str(height//2), '920', str(height//2), '160')
    ui.wait('message-draft', timeout=30)
    wait(lambda: landscape('conversation-list') is False)
    assert ui.find('message-draft')['text'] == draft
    capture('harmony-table-edge-back-restored')
    record('landscape-edge-back', portraitRestored=True, draftRetained=True)

    reader(); ui.click('table-reader-rotate'); wait(lambda: landscape('table-reader') is True)
    ui.click('table-reader-rotate'); wait(lambda: landscape('table-reader') is False)
    ui.navigate('page-back', 'message-draft')
    wait(lambda: landscape('conversation-list') is False)
    assert ui.find('message-draft')['text'] == draft
    record('rotate-twice-close', portraitRestored=True, draftRetained=True)

    reader()
    _, x, y = ui.control('table-reader-rotate')
    started = time.monotonic()
    m.hdc('shell', 'uitest', 'uiInput', 'click', str(x), str(y))
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
    ui.wait('message-draft', timeout=30)
    wait(lambda: landscape('conversation-list') is False)
    assert ui.find('message-draft')['text'] == draft
    record('rotate-immediate-back', portraitRestored=True, draftRetained=True, elapsedSeconds=time.monotonic()-started)

    ui.navigate('conversation-back', 'home-settings')
    ui.navigate('home-settings', 'settings-style')
    ui.navigate('settings-style', 'appearance-system'); ui.choose('appearance-dark')
    ui.navigate('page-back', 'home-settings')
    ui.click('session-harmony-rich', scroll=True); ui.wait('message-draft')
    assert ui.find('message-draft')['text'] == draft
    reader(); capture('harmony-table-preview-dark')
    ui.click('table-reader-rotate'); wait(lambda: landscape('table-reader') is True)
    capture('harmony-table-preview-landscape-dark')
    ui.navigate('page-back', 'message-draft')
    wait(lambda: landscape('conversation-list') is False)
    assert ui.find('message-draft')['text'] == draft
    ui.input('message-draft', original)
    record('dark-rotate-close', portraitRestored=True, draftRetained=True, originalDraftRestored=True)
    print('DONE', folder, flush=True)


if __name__ == '__main__': main()
