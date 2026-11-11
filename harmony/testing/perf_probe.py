"""只在临时产品副本注入可审计的时延／挂载／PSS 仪表。"""
from pathlib import Path
import shutil


def instrument(app, root):
    base = app / 'entry/src/main/ets'; shutil.copyfile(root / 'harmony/testing/compatibility/PerfStats.ets', base / 'core/PerfStats.ets')
    def patch(name, source, target):
        path = base / name; value = path.read_text(); assert source in value, (name, source); path.write_text(value.replace(source, target, 1))
    for name, prefix in [('core/GatewayClient.ets', './'), ('core/HostRuntime.ets', './'), ('components/MessageCard.ets', '../core/'), ('components/MarkdownBody.ets','../core/'), ('pages/ConversationPage.ets', '../core/'), ('pages/Index.ets','../core/')]:
        path = base/name; path.write_text("import { PerfStats } from '" + prefix + "PerfStats';\n" + path.read_text())
    patch('core/GatewayClient.ets', '    const result = JSON.parse(this.engine.dispatch(JSON.stringify(command)))', "    if (command.op === 'select' && command.sessionId === 'harmony-smoke') { PerfStats.started = Date.now(); }\n    const perfStart = Date.now();\n    const result = JSON.parse(this.engine.dispatch(JSON.stringify(command)))")
    patch('core/GatewayClient.ets', '    if (result.state)', "    PerfStats.add(PerfStats.bridges, Date.now() - perfStart); const perfLabel = command.op + ':' + command.channel; PerfStats.operations.set(perfLabel, Math.max(PerfStats.operations.get(perfLabel) ?? 0, Date.now() - perfStart));\n    if (result.state)")
    patch('core/HostRuntime.ets', '    const changed = this.state.selectedSessionId', '    const perfStart = Date.now();\n    const changed = this.state.selectedSessionId')
    patch('core/HostRuntime.ets', '    return changed;', '    PerfStats.add(PerfStats.updates, Date.now() - perfStart);\n    return changed;')
    patch('components/MessageCard.ets', 'aboutToAppear(): void {', 'aboutToAppear(): void { PerfStats.appeared();')
    patch('components/MessageCard.ets', 'aboutToDisappear(): void {', 'aboutToDisappear(): void { PerfStats.disappeared();')
    patch('components/MarkdownBody.ets', '    const ticket = this.epoch;', '    const perfStart = Date.now();\n    const ticket = this.epoch;')
    patch('components/MarkdownBody.ets', 'finally { this.busy = false;', 'finally { PerfStats.add(PerfStats.parses, Date.now()-perfStart); this.busy = false;')
    patch('pages/ConversationPage.ets', '  private rendered(scope: string, id: string): void {', '  private rendered(scope: string, id: string): void { PerfStats.rendered(id);')
    patch('pages/ConversationPage.ets', 'if (event.type === TouchType.Down) {', 'if (event.type === TouchType.Down) { PerfStats.scrolling = true; PerfStats.lastFrame = 0;')
    patch('pages/ConversationPage.ets', '.onScrollStop(() => {', '.onScrollStop(() => { PerfStats.scrolling = false;')
    patch('pages/Index.ets', '  @State private startupPhase:', "  @State private perfText: string = '';\n  private perfTimer: number = -1;\n  private perfDraw: () => void = () => PerfStats.draw();\n  @State private startupPhase:")
    patch('pages/Index.ets', '    this.active = true;', "    this.active = true; this.getUIContext().getUIObserver().on('willDraw', this.perfDraw); this.perfTimer = setInterval(() => { this.perfText = PerfStats.report(this.messages.length); }, 1000);")
    patch('pages/Index.ets', '    this.active = false;', "    clearInterval(this.perfTimer); this.getUIContext().getUIObserver().off('willDraw', this.perfDraw); this.active = false;")
    patch('pages/Index.ets', "    Column() {\n      if (this.errorText)", "    Column() {\n      Text(this.perfText).id('test-p7-stats').fontSize(1).height(1)\n      if (this.errorText)")
    patch('pages/Index.ets', '  private editedDraft(value: string): void {', '  private editedDraft(value: string): void { const perfStart = Date.now();')
    patch('pages/Index.ets', '    }, 80);', '    }, 80); PerfStats.add(PerfStats.drafts, Date.now()-perfStart);')

    patch('core/GatewayClient.ets', '  get connected(): boolean', "  get historyBytesForProbe(): number { return this.state.historyMemoryBytes ?? 0; }\n  get historySessionsForProbe(): number { return this.state.retainedHistorySessions ?? 0; }\n  get connected(): boolean")
    patch('pages/Index.ets', "  @State private perfText:", "  @State private memoryText: string = '';\n  @State private perfText:")
    patch('pages/Index.ets', 'this.perfText = PerfStats.report(this.messages.length);', "this.perfText = PerfStats.report(this.messages.length); this.memoryText = Array.from(this.runtimes.values()).map(runtime => runtime.id + ':' + runtime.client.historyBytesForProbe.toString() + ':' + runtime.client.historySessionsForProbe.toString() + ':' + runtime.messages.length.toString()).join('|');")
    patch('pages/Index.ets', "Text(this.perfText).id('test-p7-stats').fontSize(1).height(1)", "Text(this.perfText).id('test-p7-stats').fontSize(1).height(1)\n      Text(this.memoryText).id('test-memory-hosts').fontSize(1).height(1)")
