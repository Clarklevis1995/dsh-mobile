# coding: utf-8
"""生产表格原生横滚及真实系统CSV保存；只清理本轮UUID文件。"""
import hashlib
import json
import re
import time
import uuid


def run(ui, m, folder, passed):
    # 调用点已在生产TableReader中，不替换选择器或文件写入实现。
    expected = ('\ufeff名称,数量,备注\r\n鲸鱼,2,中文与 long_table_cell_that_wraps\r\n'
                '海豚,3,粗体\r\n').encode('utf-8')

    def bounds(row):
        return list(map(int, re.findall(r'-?\d+', row.get('origBounds', row['bounds']))))

    def cells(required):
        rows = ui.nodes(include_offscreen=True)
        return {text: next(r for r in rows if r.get('type') == 'Text' and r.get('text') == text)
                for text in required}

    before = cells(['名称', '数量', '备注', '鲸鱼', '2', '中文与 long_table_cell_that_wraps'])
    for header, value in [('名称', '鲸鱼'), ('数量', '2'), ('备注', '中文与 long_table_cell_that_wraps')]:
        a, b = bounds(before[header]), bounds(before[value])
        assert (a[0], a[2]) == (b[0], b[2]), (header, a, b)
    ui.screenshot('table-columns-before-horizontal')
    viewport = ui.wait('table-reader-horizontal')
    left, top, right, bottom = map(int, re.findall(r'-?\d+', viewport['bounds']))
    # 从内容区起滑，避开系统右缘智慧多窗栏；完全移出视口的首列可能不在无障碍树中。
    m.hdc('shell', 'uitest', 'uiInput', 'swipe', str(right-200), str(top+200), str(left+200), str(top+200), '1200')
    time.sleep(.5)
    after = cells(['数量', '2', '备注', '中文与 long_table_cell_that_wraps'])
    delta = bounds(after['备注'])[0] - bounds(before['备注'])[0]
    assert delta < -30, ('表格未实际横滚', delta)
    for name in ['数量', '2', '备注', '中文与 long_table_cell_that_wraps']:
        assert abs(bounds(after[name])[0] - bounds(before[name])[0] - delta) <= 2
    ui.screenshot('table-columns-after-horizontal')
    geometry = dict(before={key: bounds(value) for key, value in before.items()},
                    after={key: bounds(value) for key, value in after.items()}, horizontalDeltaPx=delta)
    (folder/'table-column-geometry.json').write_text(json.dumps(geometry, ensure_ascii=False, indent=2))

    def system_find(predicate):
        return next((r for r in reversed(ui.nodes(system=True)) if predicate(r)), None)

    def click(row):
        assert row
        l, t, r, b = map(int, re.findall(r'-?\d+', row['bounds']))
        m.hdc('shell', 'uitest', 'uiInput', 'click', str((l+r)//2), str((t+b)//2))
        time.sleep(.6)

    ui.click('table-reader-export')
    time.sleep(1.5)
    assert system_find(lambda r: r.get('type') == 'TextInput' and r.get('text') == 'table.csv')
    ui.screenshot('table-csv-save-cancel')
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
    ui.wait('table-reader-export')
    ui.text('2 行')

    name = 'harmony-table-' + uuid.uuid4().hex + '.csv'
    ui.click('table-reader-export')
    time.sleep(1.5)
    click(system_find(lambda r: r.get('type') == 'TextInput' and r.get('text') == 'table.csv'))
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', '2072', '2017')
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', '2055')
    m.hdc('shell', 'uitest', 'uiInput', 'text', name)
    assert system_find(lambda r: r.get('type') == 'TextInput' and r.get('text') == name)
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
    click(system_find(lambda r: r.get('text') == 'Download'))
    time.sleep(.8)
    click(system_find(lambda r: r.get('id') == 'dialog_confirm' and str(r.get('enabled')).lower() != 'false'))
    ui.wait('table-reader-export')
    time.sleep(1)
    path = '/storage/media/100/local/files/Docs/Download/' + name
    local = folder / name
    m.hdc('file', 'recv', path, str(local))
    actual = local.read_bytes()
    try:
        assert actual == expected, (actual, expected)
        ui.screenshot('table-csv-saved')
    finally:
        # 文件名由本轮生成并在系统保存框逐字核对，不操作其他CSV。
        m.hdc('shell', 'rm', path)
    assert 'No such file' in m.hdc('shell', 'ls', path)
    ui.navigate('page-back', 'message-draft')
    # 导出前后的聊天草稿由调用者设置；不得通过发送消息验证以免产生额外副作用。
    (folder/'table-csv-save.json').write_text(json.dumps(dict(name=name, bytes=len(actual),
        sha256=hashlib.sha256(actual).hexdigest(), byteExact=True, utf8Bom=True, crlf=True,
        cancelledWithoutLeavingReader=True, cleanup='only owned UUID CSV removed'), ensure_ascii=False, indent=2))
    passed('P3-table-horizontal-csv-F', '原生表头/数据列坐标一致，横滚同步；真实系统CSV取消和保存，UTF-8 BOM/CRLF/中文逐字一致，仅清理本轮导出文件')
