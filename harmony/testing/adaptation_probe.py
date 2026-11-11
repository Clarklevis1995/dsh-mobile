"""只在独立测试副本添加实际 Window 旋转入口，不改生产导航／数据。"""
def instrument(app):
    path = app / 'entry/src/main/ets/pages/Index.ets'
    source = path.read_text()
    state = '  @State private startupPhase:'
    root = "    Column() {\n      if (this.errorText)"
    assert state in source and root in source
    source = source.replace(state, "  @State private testLandscape: boolean = false;\n" + state, 1)
    source = source.replace(root, "    Column() {\n      Text(this.testLandscape ? 'landscape' : 'portrait').id('test-orientation-toggle').fontSize(8).height(16).width('100%').textAlign(TextAlign.Start).onClick(() => { this.testLandscape = !this.testLandscape; this.hostWindow?.setPreferredOrientation(this.testLandscape ? window.Orientation.LANDSCAPE : window.Orientation.PORTRAIT).catch(() => {}); })\n      if (this.errorText)", 1)
    path.write_text(source)
