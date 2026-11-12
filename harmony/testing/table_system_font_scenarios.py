# coding: utf-8
"""从真实系统设置热更新字号；保留同进程/同预览，再与新进入的布局比较。"""
import json
import re
import time


def run(ui, m, folder, passed):
    def rect(row):
        return list(map(int, re.findall(r'-?\d+', row.get('origBounds', row['bounds']))))

    def wait_system(label, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            found = next((r for r in ui.nodes(system=True) if r.get('text') == label), None)
            if found:
                return found
            time.sleep(.3)
        raise AssertionError('系统设置未找到：' + label)

    def tap_system(label):
        l, t, r, b = rect(wait_system(label))
        m.hdc('shell', 'uitest', 'uiInput', 'click', str((l+r)//2), str((t+b)//2))

    def settings():
        m.hdc('shell', 'aa', 'start', '-a', 'com.huawei.hmos.settings.MainAbility', '-b', 'com.huawei.hmos.settings')
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            rows = ui.nodes(system=True)
            if any(r.get('text') == '字体大小和界面缩放' and r.get('type') == 'Slider' for r in rows):
                return
            if any(r.get('type') == 'Slider' for r in rows) and any(r.get('text') == '字体粗细' for r in rows):
                return
            if any(r.get('text') == '显示和亮度' for r in rows):
                tap_system('显示和亮度')
                break
            time.sleep(.3)
        tap_system('字体大小和界面缩放')
        wait_system('字体粗细')

    stages = []

    def sliders():
        return [r for r in ui.nodes(system=True) if r.get('type') == 'Slider']

    def save_sliders(stage):
        rows = sliders()
        assert len(rows) == 3
        values = [r['text'] for r in rows]
        stages.append(dict(stage=stage, sliders=[dict(text=r['text'], bounds=r['bounds']) for r in rows]))
        (folder/'system-font-sliders.json').write_text(json.dumps(stages, ensure_ascii=False, indent=2))
        return values

    def set_font(value, stage):
        rows = sliders()
        assert len(rows) == 3
        l, t, r, b = rect(rows[0])
        # Slider的无障碍外框包含边缘留白；按同一行实际刻度标签的中心点选。
        tick = next(r for r in ui.nodes(system=True) if r.get('type') == 'Text'
                    and r.get('text') == str(value) and t <= (rect(r)[1]+rect(r)[3])/2 <= b)
        tick_box=rect(tick)
        x=(tick_box[0]+tick_box[2])//2
        m.hdc('shell', 'uitest', 'uiInput', 'click', str(x), str((t+b)//2))
        deadline = time.monotonic()+15
        while time.monotonic()<deadline:
            rows = sliders()
            if len(rows) == 3 and float(rows[0]['text']) == value:
                values = save_sliders(stage)
                assert values[1:] == original[1:], '字体粗细或显示缩放被改变'
                return
            time.sleep(.3)
        raise AssertionError('系统字号未到目标档位')

    def resume():
        m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE)
        ui.wait('table-reader-copy')
        time.sleep(1)

    def reader():
        key='message-body-harmony-rich-3-table-preview-0:table'
        for _ in range(15):
            if ui.find(key):
                break
            ui.scroll(False)
        ui.navigate(key, 'table-reader-copy')
        ui.wait('2 行', key='description')

    def capture(name):
        rows=ui.nodes(include_offscreen=True)
        (folder/(name+'-layout.json')).write_text(json.dumps(rows,ensure_ascii=False,indent=2))
        ui.screenshot(name)
        cells={}
        for text in ['名称', '数量', '鲸鱼', '2', '海豚', '3', '中文与 long_table_cell_that_wraps']:
            row=next(r for r in rows if r.get('text')==text)
            box=rect(row)
            cells[text]={'width':box[2]-box[0], 'height':box[3]-box[1]}
        return cells

    ui.navigate('conversation-back','home-settings')
    ui.navigate('home-settings','settings-font')
    ui.navigate('settings-font','font-follow-system')
    if str(ui.find('font-follow-system').get('checked')).lower()!='true':
        ui.click('font-follow-system')
    ui.navigate('page-back','settings-style')
    ui.navigate('page-back','home-settings')
    ui.click('session-harmony-rich',scroll=True)
    ui.wait('message-draft')
    draft='系统字号热更新表格草稿 中文 English 😀'
    ui.input('message-draft',draft)
    reader()
    baseline=capture('table-font-standard-before')
    process_before=m.hdc('shell','pidof',m.BUNDLE).strip()
    assert process_before
    original=None
    try:
        settings()
        original=save_sliders('original')
        assert float(original[0])==2, '先核对测试环境原字号；不可覆盖未知原值'
        set_font(5,'maximum')
        resume()
        assert m.hdc('shell','pidof',m.BUNDLE).strip()==process_before, '热更新验收期间进程重启'
        hot=capture('table-font-maximum-hot')
        ui.navigate('page-back','message-draft')
        assert ui.find('message-draft')['text']==draft
        reader()
        fresh=capture('table-font-maximum-fresh')
        result=dict(standard=baseline, hot=hot, fresh=fresh, sameProcess=True)
        (folder/'table-system-font-geometry.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
        assert hot==fresh, '字号热更新与新进入预览的测量不一致'
        assert fresh['名称']['height']>baseline['名称']['height'], '最大字号表头高度没有放大'
        settings()
        set_font(2,'standard-restored')
        resume()
        restored=capture('table-font-standard-restored')
        assert restored==baseline, '恢复标准字号后表格测量未恢复'
        ui.navigate('page-back','message-draft')
        assert ui.find('message-draft')['text']==draft
        passed('P7-table-system-font-hot-F','真实系统标准→最大→标准，同进程已有表格与新进入测量相等，行高实际放大/恢复，草稿保持，其他系统滑块不变')
    finally:
        if original:
            settings()
            set_font(int(float(original[0])),'finally-restored')
            assert save_sliders('verified-final')==original
            m.hdc('shell','aa','start','-a','EntryAbility','-b',m.BUNDLE)
