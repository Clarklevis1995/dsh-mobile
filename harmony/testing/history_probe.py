"""只在测试副本记录真实历史页序号和实际Native投影标记，不注入业务历史。"""
def instrument(app):
    p=app/'entry/src/main/ets/core/GatewayClient.ets';s=p.read_text();marker='export class GatewayClient {';assert marker in s
    s=s.replace(marker,'''interface OwnedHistoryRecord { seq: number; }
interface OwnedHistoryFrame { kind: string; sessionId?: string; events?: OwnedHistoryRecord[]; requestId?: string; }
interface OwnedHistoryPage { sessionId: string; sequences: number[]; requestId: string; }
export class GatewayClient {
  private ownedHistoryPages: OwnedHistoryPage[] = [];
  get historyProjectionForProbe(): string {
    const rows = this.messages.filter(row => row.kind === 'USER' && row.text.startsWith('HARMONY_HISTORY_'));
    return JSON.stringify({ sessionId: this.state.selectedSessionId, connected: this.state.connected, more: this.state.hasMore, loading: this.state.loadingHistory,
      markers: rows.map(row => row.text), ids: rows.map(row => row.id), messages: this.messages.length, pages: this.ownedHistoryPages });
  }
''',1)
    marker='    const result = JSON.parse(this.engine.dispatch(JSON.stringify(command)))';assert marker in s
    s=s.replace(marker,'''    if (command.op === 'frame') {
      try {
        const frame = JSON.parse(command.frame) as OwnedHistoryFrame;
        if (['history','session-snapshot'].includes(frame.kind)) {
          this.ownedHistoryPages = this.ownedHistoryPages.concat([{ sessionId: frame.sessionId ?? '', sequences: (frame.events ?? []).map(row => row.seq), requestId: frame.requestId ?? '' }]).slice(-50);
        }
      } catch (_) {}
    }
'''+marker,1);p.write_text(s)
    p=app/'entry/src/main/ets/pages/Index.ets';s=p.read_text()
    s=s.replace('  @State private startupPhase:',"  @State private ownedHistoryText: string = '';\n  private ownedHistoryTimer: number = -1;\n  @State private startupPhase:",1)
    s=s.replace('    this.active = true;',"    this.active = true; this.ownedHistoryTimer = setInterval(() => { this.ownedHistoryText = this.runtime?.client.historyProjectionForProbe ?? '{}'; }, 600);",1)
    s=s.replace('    this.active = false;', '    clearInterval(this.ownedHistoryTimer); this.active = false;',1)
    marker="    Column() {\n      if (this.errorText)";assert marker in s
    s=s.replace(marker,"    Column() {\n      Text(this.ownedHistoryText).id('test-history-projection').fontSize(1).height(1)\n      if (this.errorText)",1);p.write_text(s)
