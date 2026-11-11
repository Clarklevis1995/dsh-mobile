"""只在独立测试副本添加实际 Window 旋转入口，不改生产导航／数据。"""
import os

def instrument(app):
    path = app / 'entry/src/main/ets/pages/Index.ets'
    source = path.read_text()
    state = '  @State private startupPhase:'
    root = "    Column() {\n      if (this.errorText)"
    assert state in source and root in source
    source = source.replace(state, "  @State private testLandscape: boolean = false;\n" + state, 1)
    source = source.replace(root, "    Column() {\n      Text(this.testLandscape ? 'landscape' : 'portrait').id('test-orientation-toggle').fontSize(8).height(16).width('100%').textAlign(TextAlign.Start).onClick(() => { this.testLandscape = !this.testLandscape; this.hostWindow?.setPreferredOrientation(this.testLandscape ? window.Orientation.LANDSCAPE : window.Orientation.PORTRAIT).catch(() => {}); })\n      if (this.errorText)", 1)
    if os.environ.get('HARMONY_ADAPT_WIDE') == '1':
        source = source.replace(state, "  @State private testViewport: string = '';\n" + state, 1)
        source = source.replace('this.viewportHeight = Number(area.height); this.resize();', "this.viewportHeight = Number(area.height); this.testViewport = this.viewportWidth.toString() + 'x' + this.viewportHeight.toString(); this.resize();", 1)
        ability = app / 'entry/src/main/ets/entryability/EntryAbility.ets'
        text = ability.read_text().replace("    stage.loadContent('pages/Index');", "    stage.setCustomDensity(2);\n    stage.loadContent('pages/Index');", 1)
        ability.write_text(text)
        source = source.replace("      if (this.errorText)", "      Text((this.wide ? 'wide' : 'compact') + ':' + this.testViewport).id('test-wide-state').fontSize(1).height(1)\n      if (this.errorText)", 1)
    path.write_text(source)
