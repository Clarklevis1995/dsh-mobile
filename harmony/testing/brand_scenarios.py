# coding: utf-8
"""生产Canvas组件仅加计数；不替换绘制函数或伪造GPU指标。"""
from pathlib import Path
import json
import shutil
import time


def instrument(app, root):
    base = app / 'entry/src/main/ets'
    shutil.copy2(root / 'harmony/testing/compatibility/BrandProbe.ets', base / 'pages/BrandProbe.ets')
    (base / 'core/BrandProbeStats.ets').write_text('''export class BrandProbeStats {
  static draws: number = 0; static totalMs: number = 0; static maxMs: number = 0;
  static record(ms: number): void { BrandProbeStats.draws++; BrandProbeStats.totalMs+=ms; BrandProbeStats.maxMs=Math.max(BrandProbeStats.maxMs,ms); }
}
''')
    path = base / 'components/BrandBackground.ets'; source = path.read_text()
    source = "import { BrandProbeStats } from '../core/BrandProbeStats';\n" + source
    source = source.replace('  private draw(): void {', '  private draw(): void { const probeStart = Date.now();', 1)
    marker = "ctx.fillStyle=veil; ctx.fillRect(0,0,w,h);"
    assert marker in source
    source = source.replace(marker, marker + ' BrandProbeStats.record(Date.now()-probeStart);', 1)
    path.write_text(source)
    path = base / 'entryability/EntryAbility.ets'; source = path.read_text(); assert "stage.loadContent('pages/Index')" in source
    path.write_text(source.replace("stage.loadContent('pages/Index')", "stage.loadContent('pages/BrandProbe')"))
    path = app / 'entry/src/main/resources/base/profile/main_pages.json'; value = json.loads(path.read_text()); value['src'].append('pages/BrandProbe'); path.write_text(json.dumps(value))


def run(ui, m, folder, passed):
    def capture():
        ui.click('probe-capture'); return json.loads(ui.wait('probe-stats')['text'])
    ui.wait('probe-active'); time.sleep(1.5)
    first = capture(); time.sleep(3); active = capture()
    rate = (active['draws']-first['draws']) / ((active['time']-first['time'])/1000)
    assert 3 <= rate <= 15, rate
    ui.click('probe-active'); time.sleep(1.5); idle = capture(); time.sleep(3); idle_end = capture()
    assert idle_end['draws'] == idle['draws'], (idle, idle_end)
    ui.click('probe-active'); ui.click('probe-motion'); time.sleep(1.5); reduced = capture(); time.sleep(3); reduced_end = capture()
    assert reduced_end['draws'] == reduced['draws'], (reduced, reduced_end)
    ui.click('probe-motion')
    m.hdc('shell', 'uitest', 'uiInput', 'keyEvent', 'Home'); time.sleep(3)
    m.hdc('shell', 'aa', 'start', '-a', 'EntryAbility', '-b', m.BUNDLE); ui.wait('probe-capture')
    resumed = capture(); assert 0 <= resumed['backgroundEnd']-resumed['backgroundStart'] <= 1, resumed
    assert resumed['totalMs']/resumed['draws'] < 16, resumed
    value = dict(activeDrawCallsPerSecond=rate, inactiveDrawDelta=idle_end['draws']-idle['draws'], reduceMotionDrawDelta=reduced_end['draws']-reduced['draws'], backgroundDrawDelta=resumed['backgroundEnd']-resumed['backgroundStart'], averageJsSubmitMs=resumed['totalMs']/resumed['draws'], maximumJsSubmitMs=resumed['maxMs'], draws=resumed['draws'], scope='实际API21生产Canvas组件，仅临时计数；JS提交耗时和频率，不是GPU帧时/真机功耗')
    (folder / 'brand-dynamics.json').write_text(json.dumps(value, ensure_ascii=False, indent=2))
    ui.screenshot('brand-dynamics')
    passed('P2-brand-dynamics-F', '修正后的生产Canvas活动限频、停用和减少动态零持续绘制、前后台最多一个收尾提交与实际JS平均提交成本通过')
