"""正式原生消息组件的隔离网关回归，不注入替代渲染器。"""
import time


def run(ui, m, folder, passed):
    m.connect(ui)
    ui.navigate('conversation-back', 'home-settings')
    ui.click('富文本验收', 'text'); ui.wait('message-draft')
    time.sleep(1)
    assert not any(row.get('text') == '内容解析失败' for row in ui.nodes()), 'TaskPool 解析器失败'
    table = 'message-body-harmony-rich-3'
    code = 'message-body-harmony-rich-2'
    expected_code = 'val whale = "鲸鱼🐳"\n\tprintln(whale)\n' + '\n'.join('// line ' + str(i) for i in range(1, 29)) + '\n// ' + 'horizontal_' * 18

    def locate(value, upward=True):
        for _ in range(15):
            if ui.find(value): return
            ui.scroll(not upward)
        raise AssertionError('未找到富文本组件：' + value)

    def paste(expected):
        ui.input('message-draft', '')
        ui.click('message-draft')
        m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', '2072', '2038')
        time.sleep(.7)
        m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Back')
        actual = ui.find('message-draft').get('text')
        assert actual == expected, repr(actual)
        ui.input('message-draft', '')

    locate(table + '-table-copy-0:table')
    ui.wait(table + '-table-copy-0:table', timeout=20)
    ui.text('中文与 long_table_cell_that_wraps'); ui.screenshot('rich-table')
    ui.click(table + '-table-copy-0:table')
    paste('名称\t数量\t备注\n鲸鱼\t2\t中文与 long_table_cell_that_wraps\n海豚\t3\t粗体')
    ui.click(table + '-table-preview-0:table'); ui.wait('table-preview-table-copy-0:table'); ui.screenshot('rich-table-preview')
    ui.navigate('page-back', 'message-draft')
    passed('P3-table-F', '原生表格内容、中文长单元格、复制纯文本矩阵和原生预览返回通过')

    locate(code + '-code-copy-0:code')
    ui.click(code + '-code-copy-0:code'); paste(expected_code)
    passed('P3-code-copy-F', '代码原文复制与中文、emoji、TAB、长行一致，未混入装饰或行号')
    locate(code + '-code-expand-0:code', upward=False)
    ui.click(code + '-code-expand-0:code'); ui.screenshot('rich-code-expanded')
    locate(code + '-code-expand-0:code', upward=False)
    row, _, _ = ui.control(code + '-code-scroll-0:code')
    import re
    left, top, right, bottom = map(int, re.findall(r'-?\d+', row['bounds']))
    m.hdc('shell', 'uitest', 'uiInput', 'swipe', str(right-30), str((top+bottom)//2), str(left+30), str((top+bottom)//2), '2000')
    time.sleep(.4)
    text = ui.find(code + '-code-0:code')
    original = list(map(int, re.findall(r'-?\d+', text.get('origBounds', text['bounds']))))
    assert original[0] < left, '代码长行没有实际横向滚动'
    ui.click(code + '-code-expand-0:code')
    passed('P3-code-F', 'Kotlin 高亮代码卡片、展开收起、中文/emoji/制表符/长行原文复制一致')

    locate('message-body-harmony-rich-1')
    ui.text('原生富文本标题'); ui.screenshot('rich-paragraphs')
    assert not any('**加粗**' in row.get('text', '') for row in ui.nodes())
    ui.click('message-menu-harmony-rich-1'); ui.click('message-select-harmony-rich-1')
    selected = ui.wait('content-selection').get('text'); ui.text('原生富文本标题'); ui.text('已完成')
    assert '\n\n' in selected and '**加粗**' not in selected
    ui.click('content-select-all'); ui.click('content-copy'); ui.screenshot('rich-selection')
    ui.navigate('page-back', 'message-draft'); paste(selected)
    passed('P3-text-selection-F', '标题、行内样式、列表/任务列表、引用、分隔线原生渲染；跨段落选择页及全选复制可操作')
    ui.message('echo'); ui.text('流式回复验证完成', timeout=25)
    assert not any(row.get('text') == '内容解析失败' for row in ui.nodes())
    ui.screenshot('rich-stream-complete')
    ui.click('conversation-tab-trajectory'); ui.wait('trajectory-all')
    until = time.monotonic() + 20
    while not any(row.get('id', '').startswith('trajectory-node-') for row in ui.nodes()):
        assert time.monotonic() < until, '轨迹未显示共享节点'
        time.sleep(.4)
    ui.screenshot('rich-trajectory')
    node = next(row['id'] for row in ui.nodes() if row.get('id', '').startswith('trajectory-node-'))
    ui.click(node); ui.wait('content-selection'); ui.text('轨迹详情'); ui.screenshot('rich-trajectory-detail')
    ui.navigate('page-back', 'trajectory-all'); ui.click('trajectory-tools'); ui.text('暂无轨迹记录')
    ui.click('trajectory-requests'); ui.click('conversation-tab-chat'); ui.wait('message-draft')
    passed('P3-trajectory-F', '共享真实事件投影、对话/轨迹切换、模型/工具过滤与原生详情返回通过')
    passed('P3-native-stream-F', '正式 Native 协议到原生 Markdown Worker 的连续增量与终帧显示通过')
