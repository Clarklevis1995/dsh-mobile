import { MarkdownData, MarkdownRun, MarkdownCell, MarkdownRow } from './MarkdownModel';

@Observed
export class MarkdownBlock implements MarkdownData {
  id: string = ''; kind: string = ''; raw: string = ''; text: string = ''; runs: MarkdownRun[] = [];
  depth: number = 0; quote: boolean = false; prefix: string = ''; language: string = '';
  header: MarkdownCell[] = []; rows: MarkdownRow[] = [];
  constructor(value: MarkdownData) { this.update(value); }
  update(value: MarkdownData): void {
    this.id = value.id;
    if (this.raw === value.raw && this.kind === value.kind && this.prefix === value.prefix && this.quote === value.quote && JSON.stringify(this.runs) === JSON.stringify(value.runs) && JSON.stringify(this.header) === JSON.stringify(value.header) && JSON.stringify(this.rows) === JSON.stringify(value.rows)) { return; }
    this.kind = value.kind; this.raw = value.raw; this.text = value.text; this.runs = value.runs;
    this.depth = value.depth; this.quote = value.quote; this.prefix = value.prefix;
    this.language = value.language; this.header = value.header; this.rows = value.rows;
  }
}
