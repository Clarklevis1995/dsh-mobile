import { hidebug } from '@kit.PerformanceAnalysisKit';

interface PerfSnapshot {
  operations: string[]; messages: number; liveCards: number; peakCards: number; firstTailMs: number;
  bridgeP95Ms: number; bridgeMaxMs: number; updateP95Ms: number; draftP95Ms: number; parseP95Ms: number;
  drawCallbackIntervalP95Ms: number; drawCallbackSamples: number; pssKiB: string; rssKiB: string;
}
export class PerfStats {
  static cards: number = 0;
  static peakCards: number = 0;
  static started: number = 0;
  static tailMs: number = -1;
  static bridges: number[] = [];
  static operations: Map<string, number> = new Map();
  static updates: number[] = [];
  static drafts: number[] = [];
  static parses: number[] = [];
  static frames: number[] = [];
  static scrolling: boolean = false;
  static lastFrame: number = 0;
  static add(values: number[], value: number): void { values.push(value); if (values.length > 4096) { values.shift(); } }
  static p95(values: number[]): number { if (!values.length) { return -1; } const sorted = values.slice().sort((a: number,b: number) => a-b); return sorted[Math.min(sorted.length-1, Math.floor(sorted.length*.95))]; }
  static appeared(): void { PerfStats.cards++; PerfStats.peakCards = Math.max(PerfStats.peakCards, PerfStats.cards); }
  static disappeared(): void { PerfStats.cards = Math.max(0, PerfStats.cards-1); }
  static rendered(id: string): void { if (id === 'harmony-smoke-1005' && PerfStats.started && PerfStats.tailMs < 0) { PerfStats.tailMs = Date.now()-PerfStats.started; } }
  static draw(): void { const now = Date.now(); if (PerfStats.scrolling && PerfStats.lastFrame) { PerfStats.add(PerfStats.frames,now-PerfStats.lastFrame); } PerfStats.lastFrame = PerfStats.scrolling ? now : 0; }
  static report(messages: number): string {
    const memory = hidebug.getAppNativeMemInfo();
    const operations: string[] = []; PerfStats.operations.forEach((elapsed: number, name: string) => operations.push(name + ':' + elapsed));
    const snapshot: PerfSnapshot = { operations: operations, messages: messages, liveCards: PerfStats.cards, peakCards: PerfStats.peakCards, firstTailMs: PerfStats.tailMs,
      bridgeP95Ms: PerfStats.p95(PerfStats.bridges), bridgeMaxMs: PerfStats.bridges.length ? Math.max(...PerfStats.bridges) : -1,
      updateP95Ms: PerfStats.p95(PerfStats.updates), draftP95Ms: PerfStats.p95(PerfStats.drafts), parseP95Ms: PerfStats.p95(PerfStats.parses),
      drawCallbackIntervalP95Ms: PerfStats.p95(PerfStats.frames), drawCallbackSamples: PerfStats.frames.length,
      pssKiB: memory.pss.toString(), rssKiB: memory.rss.toString() };
    return JSON.stringify(snapshot);
  }
}
