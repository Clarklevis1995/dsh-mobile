import { taskpool } from '@kit.ArkTS';
import { Appearance } from '../core/Appearance';
import { MarkdownCell, MarkdownRow, MarkdownData } from '../core/MarkdownModel';
import { parseMarkdownInWorker } from '../core/MarkdownWorker';
import { contentAction, copyContent } from '../core/ContentActions';
import { MarkdownText } from './MarkdownText';

class TableRows implements IDataSource {
  private rows: MarkdownRow[] = [];
  private listeners: DataChangeListener[] = [];
  totalCount(): number { return this.rows.length; }
  getData(index: number): MarkdownRow { return this.rows[index]; }
  registerDataChangeListener(listener: DataChangeListener): void { this.listeners.push(listener); }
  unregisterDataChangeListener(listener: DataChangeListener): void { const index = this.listeners.indexOf(listener); if (index >= 0) { this.listeners.splice(index, 1); } }
  reset(rows: MarkdownRow[]): void { this.rows = rows; this.listeners.forEach(listener => listener.onDataReloaded()); }
}

export function tableWidths(header: MarkdownCell[], rows: MarkdownRow[]): number[] {
  const widths = header.map(() => 104);
  [header].concat(rows.map(row => row.cells)).forEach(cells => cells.forEach((cell: MarkdownCell, index: number) => {
    let longest = 0; cell.text.split('\n').forEach(line => longest = Math.max(longest, line.length));
    widths[index] = Math.max(widths[index] ?? 104, Math.min(260, longest * 8 + 24));
  }));
  return widths;
}

@Component
export struct TableCells {
  @ObjectLink a: Appearance;
  @Prop values: MarkdownCell[] = [];
  @Prop widths: number[] = [];
  @Prop header: boolean = false;
  @Prop alternate: boolean = false;
  onLink: (value: string) => void = () => {};
  build() {
    Row() {
      ForEach(this.values, (cell: MarkdownCell, index: number) => {
        MarkdownText({ a: this.a, runs: cell.runs, fillWidth: true, fontPoints: 14, bold: this.header, cellAlign: cell.align, onLink: this.onLink })
          .padding(12).width((this.widths[index] ?? 104) * (this.a.data.fontFollowsSystem ? 1 : this.a.data.fontScale)).align(Alignment.TopStart).border({ width: 0.5, color: this.a.line })
      })
    }.height('auto').alignItems(VerticalAlign.Top).backgroundColor(this.header ? (this.a.dark ? '#252527' : '#EFEFF0') : this.alternate ? (this.a.dark ? '#111113' : '#F8F8FA') : this.a.surface)
  }
}

/** 完整表格采用横向滚动 + 原生惰性行，避免长表格一次性挂载全部单元格。 */
@Component
export struct TableReader {
  @ObjectLink a: Appearance;
  @Prop @Watch('load') source: string = '';
  @State private header: MarkdownCell[] = [];
  @State private widths: number[] = [];
  @State private count: number = 0;
  @State private loading: boolean = true;
  @State private error: string = '';
  private rows: MarkdownRow[] = [];
  private data: TableRows = new TableRows();
  private alive: boolean = false;
  private revision: number = 0;
  onLink: (value: string) => void = () => {};
  onExport: (csv: string) => void = () => {};
  aboutToAppear(): void { this.alive = true; this.load(); }
  aboutToDisappear(): void { this.alive = false; this.revision++; }
  private async load(): Promise<void> {
    const ticket = ++this.revision; this.count = 0; this.loading = true; this.error = '';
    try {
      const blocks = JSON.parse(await taskpool.execute(new taskpool.Task(parseMarkdownInWorker, this.source)) as string) as MarkdownData[];
      if (!this.alive || ticket !== this.revision) { return; }
      const table = blocks.find(block => block.kind === 'table'); if (!table) { throw new Error('没有可预览的表格'); }
      this.header = table.header; this.rows = table.rows; this.widths = tableWidths(table.header, table.rows); this.data.reset(table.rows); this.count = table.rows.length;
    } catch (_) { if (this.alive && ticket === this.revision) { this.error = this.a.t('表格解析失败，可返回复制原文'); } }
    finally { if (this.alive && ticket === this.revision) { this.loading = false; } }
  }
  private exportValue(csv: boolean): string { return csv ? contentAction('render-table', '', '', [this.header].concat(this.rows.map(row => row.cells)).map(row => row.map(cell => cell.text))).csv : contentAction('render-table', '', '', [this.header].concat(this.rows.map(row => row.cells)).map(row => row.map(cell => cell.text))).plainText; }
  private async copy(): Promise<void> { try { await copyContent(this.exportValue(false)); this.getUIContext().getPromptAction().showToast({ message: this.a.t('已复制') }); } catch (_) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('复制失败') }); } }
  build() {
    Column({ space: 8 }) {
      Row({ space: 12 }) {
        Text(this.count + ' ' + this.a.t('行')).id('table-reader-count').fontSize(this.a.font(13)).fontColor(this.a.muted).layoutWeight(1)
        Button(this.a.t('复制表格')).id('table-reader-copy').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).enabled(!this.loading && !this.error).onClick(() => this.copy())
        Button('CSV').id('table-reader-export').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).enabled(!this.loading && !this.error).onClick(() => this.onExport(this.exportValue(true)))
      }.padding({ left: 16, right: 16 }).width('100%')
      if (this.loading) { LoadingProgress().width(30).height(30).color(this.a.accent) }
      if (this.error) { Text(this.error).fontSize(this.a.font(14)).fontColor(this.a.muted).padding(20) }
      Scroll() {
        Column() {
          TableCells({ a: this.a, values: this.header, widths: this.widths, header: true, onLink: this.onLink })
          List() { LazyForEach(this.data, (row: MarkdownRow, index: number) => { ListItem() { TableCells({ a: this.a, values: row.cells, widths: this.widths, alternate: index % 2 === 1, onLink: this.onLink }) } }, (_row: MarkdownRow, index: number) => index.toString()) }
            .id('table-reader-list').width('100%').cachedCount(2).layoutWeight(1)
        }.height('100%').width(this.widths.reduce((total: number, value: number) => total + value, 0) * (this.a.data.fontFollowsSystem ? 1 : this.a.data.fontScale))
      }.id('table-reader-horizontal').scrollable(ScrollDirection.Horizontal).align(Alignment.TopStart).scrollBar(BarState.Auto).width('100%').layoutWeight(1)
    }.height('100%').width('100%')
  }
}
