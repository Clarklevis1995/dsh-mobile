# coding: utf-8
"""首页紧凑行的实际点击/草稿返回与长按取消；不修改目录业务结果。"""
import json
import re
import shutil
import uuid


def run(ui, m, folder, passed):
    ui.wait('home-settings')
    rows = ui.nodes()
    titles = [row for row in rows if row.get('id', '').startswith('session-title-')]
    assert len(titles) >= 2
    for row in titles:
        suffix = row['id'].removeprefix('session-title-')
        assert any(item.get('id') == 'session-meta-' + suffix and item.get('text') for item in rows)
        assert any(item.get('id') == 'session-age-' + suffix and item.get('text') for item in rows)
    shutil.copy2(folder / 'layout.json', folder / 'home-rows-final-layout.json')
    ui.screenshot('home-rows-final')
    ui.click('session-harmony-smoke', scroll=True); ui.wait('message-draft')
    previous = ui.find('message-draft').get('text', '')
    draft = previous + ' 首页紧凑行回归 ' + uuid.uuid4().hex[:6]
    ui.input('message-draft', draft); ui.navigate('conversation-back', 'home-settings')
    def long_press():
        row, x, y = ui.control('session-harmony-smoke', scroll=True)
        m.hdc('shell', 'uitest', 'uiInput', 'longClick', str(x), str(y))
        ui.wait('重命名', 'text')
    original = ui.wait('session-title-harmony-smoke')['text']
    long_press(); ui.click('重命名', 'text'); ui.wait('session-rename-input')
    ui.input('session-rename-input', '不保存的行标题'); ui.click('取消', 'text')
    assert ui.wait('session-title-harmony-smoke')['text'] == original
    long_press(); ui.click('归档', 'text'); ui.wait('confirmation-accept'); ui.click('取消', 'text')
    ui.wait('session-harmony-smoke'); ui.click('session-harmony-smoke', scroll=True); ui.wait('message-draft')
    assert ui.find('message-draft')['text'] == draft
    ui.input('message-draft', previous); ui.navigate('conversation-back', 'home-settings')
    (folder / 'home-row-identities.json').write_text(json.dumps([row['id'] for row in titles], ensure_ascii=False, indent=2))
    passed('P2-home-compact-rows-F', '生产首页显示目录/相对时间，紧凑行点击进入与返回保留草稿；长按重命名取消与归档取消保留会话，未确认不修改目录')
