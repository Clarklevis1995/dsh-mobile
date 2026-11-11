import { worker, MessageEvents } from '@kit.ArkTS';
import { MarkdownParseRequest, MarkdownParseResponse } from './MarkdownParserTypes';

const MAX_PENDING_PARSES = 64;
const PARSER_IDLE_MILLISECONDS = 2000;
const PARSE_TIMEOUT_MILLISECONDS = 30000;

interface PendingParse { resolve: (value: string) => void; reject: (error: Error) => void; timer: number; }

/** 可见消息共享单一解析线程；空闲后销毁其堆，不把流式前缀留在通用任务池。 */
export class MarkdownParser {
  private port?: worker.ThreadWorker;
  private sequence: number = 0;
  private pending: Map<number, PendingParse> = new Map();
  private idleTimer: number = -1;
  async parse(source: string, plain: boolean = false, codeLanguage?: string): Promise<string> {
    if (this.pending.size >= MAX_PENDING_PARSES) { throw new Error('markdown-parser-busy'); }
    clearTimeout(this.idleTimer); this.idleTimer = -1;
    const port = this.ensurePort(); const id = ++this.sequence;
    return new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => this.fail(port), PARSE_TIMEOUT_MILLISECONDS);
      this.pending.set(id, { resolve: resolve, reject: reject, timer: timer });
      const request: MarkdownParseRequest = { id: id, source: source, plain: plain, code: codeLanguage !== undefined, language: codeLanguage ?? '' };
      try { port.postMessage(request); } catch (_) { this.fail(port); }
    });
  }
  private ensurePort(): worker.ThreadWorker {
    if (this.port) { return this.port; }
    const port = new worker.ThreadWorker('entry/ets/workers/MarkdownParserWorker.ets', { name: 'markdown-parser' }); this.port = port;
    port.onmessage = (event: MessageEvents): void => {
      if (this.port !== port) { return; }
      const response = event.data as MarkdownParseResponse; const pending = this.pending.get(response.id);
      if (!pending) { return; }
      this.pending.delete(response.id); clearTimeout(pending.timer);
      if (response.failed || typeof response.result !== 'string') { pending.reject(new Error('markdown-parser-failed')); }
      else { pending.resolve(response.result); }
      if (!this.pending.size) {
        clearTimeout(this.idleTimer);
        this.idleTimer = setTimeout(() => {
          if (this.port !== port || this.pending.size) { return; }
          this.port = undefined; this.idleTimer = -1; this.disposePort(port);
        }, PARSER_IDLE_MILLISECONDS);
      }
    };
    port.onerror = (): void => this.fail(port);
    port.onmessageerror = (): void => this.fail(port);
    port.onexit = (): void => this.fail(port);
    return port;
  }
  private disposePort(port: worker.ThreadWorker): void {
    port.onmessage = undefined; port.onmessageerror = undefined; port.onerror = undefined; port.onexit = undefined;
    try { port.terminate(); } catch (_) {}
  }
  private fail(port: worker.ThreadWorker): void {
    if (this.port !== port) { return; }
    this.port = undefined; clearTimeout(this.idleTimer); this.idleTimer = -1;
    const pending = Array.from(this.pending.values()); this.pending.clear();
    this.disposePort(port);
    pending.forEach(value => { clearTimeout(value.timer); value.reject(new Error('markdown-parser-unavailable')); });
  }
}

const parser = new MarkdownParser();
export function parseMarkdownAsync(source: string): Promise<string> { return parser.parse(source); }
export function markdownTextAsync(source: string): Promise<string> { return parser.parse(source, true); }

export function highlightMarkdownCodeAsync(source: string, language: string): Promise<string> { return parser.parse(source, false, language); }
