import { UIContext, window } from '@kit.ArkUI';
import { Appearance } from '../core/Appearance';
import { MarkdownCell, MarkdownRow, MarkdownData } from '../core/MarkdownModel';
import { parseMarkdownAsync } from '../core/MarkdownParser';
import { contentAction, copyContent } from '../core/ContentActions';
import { MarkdownText } from './MarkdownText';
import { DocumentHeader, DocumentIcon, DocumentPalette } from './DocumentHeader';

class TableRows implements IDataSource {
  private rows: MarkdownRow[] = [];
  private listeners: DataChangeListener[] = [];
  totalCount(): number { return this.rows.length; }
  getData(index: number): MarkdownRow { return this.rows[index]; }
  registerDataChangeListener(listener: DataChangeListener): void { this.listeners.push(listener); }
  unregisterDataChangeListener(listener: DataChangeListener): void { const index = this.listeners.indexOf(listener); if (index >= 0) { this.listeners.splice(index, 1); } }
  reset(rows: MarkdownRow[]): void { this.rows = rows; this.listeners.forEach(listener => listener.onDataReloaded()); }
}

/** 与冻结基准相同的96～260vp自然列宽，窄表按比例铺满实际视口。 */
export function tableWidths(a: Appearance, ui: UIContext, header: MarkdownCell[], rows: MarkdownRow[]): number[] {
  const widths = header.map(() => 96);
  const measure = ui.getMeasureUtils();
  [header].concat(rows.map(row => row.cells)).forEach(cells => cells.forEach((cell: MarkdownCell, index: number) => {
    let longest = 0;
    cell.text.split('\n').forEach(line => longest = Math.max(longest, ui.px2vp(measure.measureText({ textContent: line, fontSize: a.font(17) }))));
    widths[index] = Math.max(widths[index] ?? 96, Math.min(260, longest + 24));
  }));
  return widths;
}

export function fitTableWidths(natural: number[], available: number): number[] {
  const total = natural.reduce((sum: number, width: number) => sum + width, 0);
  return total > 0 && available > total ? natural.map(width => width * available / total) : natural;
}

function rowHeight(a: Appearance, ui: UIContext, cells: MarkdownCell[], widths: number[], header: boolean): number {
  let height = 0;
  cells.forEach((cell, index) => {
    const size = ui.getMeasureUtils().measureTextSize({ textContent: cell.text || ' ', fontSize: a.font(17),
      // MeasureOptions数值行高与Text的fp行框不同；显式fp以跟随真实系统字号。
      fontWeight: header ? FontWeight.Bold : FontWeight.Normal, lineHeight: a.data.fontFollowsSystem ? '24fp' : a.font(24),
      constraintWidth: Math.max(1, (widths[index] ?? 96) - 24).toString() + 'vp' });
    height = Math.max(height, ui.px2vp(Number(size.height)));
  });
  return height + 24;
}

function hasCjkGlyphs(text: string): boolean {
  return /[\u2E80-\u9FFF\uAC00-\uD7AF\uF900-\uFAFF\uFF00-\uFFEF\u{20000}-\u{2FA1F}]/u.test(text);
}

@Component
export struct TableCells {
  @ObjectLink @Watch('updateHeight') a: Appearance;
  @Prop @Watch('updateHeight') values: MarkdownCell[] = [];
  @Prop @Watch('updateHeight') widths: number[] = [];
  @Prop @Watch('updateHeight') header: boolean = false;
  @Prop alternate: boolean = false;
  @State private measuredHeight: number = 48;
  onLink: (value: string) => void = () => {};
  aboutToAppear(): void { this.updateHeight(); }
  private updateHeight(): void {
    const height = rowHeight(this.a, this.getUIContext(), this.values, this.widths, this.header);
    if (Math.abs(height - this.measuredHeight) > .01) { this.measuredHeight = height; }
  }
  build() {
    Row() {
      ForEach(this.values, (cell: MarkdownCell, index: number) => {
        // 冻结基准的CJK回退字框为24fp；纯西文采用自然字框，避免数字基线下移。
        MarkdownText({ a: this.a, runs: cell.runs, fillWidth: true, fontPoints: 17, linePoints: 24, naturalLines: !hasCjkGlyphs(cell.text),
          foreground: new DocumentPalette(this.a.dark).foreground, bold: this.header, cellAlign: cell.align, onLink: this.onLink })
          .padding(12).width(this.widths[index] ?? 96).height(this.measuredHeight).align(Alignment.TopStart)
          .border({ width: 0.5, color: new DocumentPalette(this.a.dark).border })
      })
    }.height(this.measuredHeight).alignItems(VerticalAlign.Top)
      .backgroundColor(this.header ? new DocumentPalette(this.a.dark).header : this.alternate ? new DocumentPalette(this.a.dark).alternate : new DocumentPalette(this.a.dark).background)
  }
}

/** 横滚容器与惰性行共享列宽；只测量短表总高度，长表保持原生List虚拟化。 */
@Component
export struct TableReader {
  @ObjectLink @Watch('measure') a: Appearance;
  @Prop @Watch('load') source: string = '';
  @State private header: MarkdownCell[] = [];
  @State private naturalWidths: number[] = [];
  @State private availableWidth: number = 0;
  @State private availableHeight: number = 0;
  @State private count: number = 0;
  @State private loading: boolean = true;
  @State private rotating: boolean = false;
  @State private error: string = '';
  private rows: MarkdownRow[] = [];
  private data: TableRows = new TableRows();
  private alive: boolean = false;
  private revision: number = 0;
  private hostWindow: window.Window | undefined;
  private originalOrientation: window.Orientation | undefined;
  private orientationWork: Promise<void> = Promise.resolve();
  onBack: () => void = () => {};
  onLink: (value: string) => void = () => {};
  onExport: (csv: string) => void = () => {};
  aboutToAppear(): void { this.alive = true; this.load(); }
  aboutToDisappear(): void {
    this.alive = false; this.revision++;
    // 先等待本页在途旋转，再恢复进入前方向，避免晚完成的旋转污染聊天页。
    this.orientationWork = this.orientationWork.then(async () => {
      if (this.hostWindow && this.originalOrientation !== undefined) { await this.hostWindow.setPreferredOrientation(this.originalOrientation); }
    }).catch(() => {});
  }
  private toggleOrientation(): void {
    if (this.rotating || !this.alive) { return; }
    this.rotating = true;
    this.orientationWork = this.orientationWork.then(async () => {
      const context = this.getUIContext().getHostContext();
      if (!context || !this.alive) { return; }
      const host = this.hostWindow ?? await window.getLastWindow(context);
      if (!this.alive) { return; }
      if (this.originalOrientation === undefined) { this.originalOrientation = host.getPreferredOrientation(); this.hostWindow = host; }
      const bounds = host.getWindowProperties().windowRect;
      await host.setPreferredOrientation(bounds.width > bounds.height ? window.Orientation.PORTRAIT : window.Orientation.LANDSCAPE);
    }).catch(() => { if (this.alive) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('无法旋转屏幕') }); } })
      .finally(() => { if (this.alive) { this.rotating = false; } });
  }
  private async load(): Promise<void> {
    const ticket = ++this.revision; this.count = 0; this.loading = true; this.error = '';
    try {
      const blocks = JSON.parse(await parseMarkdownAsync(this.source) as string) as MarkdownData[];
      if (!this.alive || ticket !== this.revision) { return; }
      const table = blocks.find(block => block.kind === 'table'); if (!table) { throw new Error('没有可预览的表格'); }
      this.header = table.header; this.rows = table.rows; this.naturalWidths = tableWidths(this.a, this.getUIContext(), table.header, table.rows);
      this.data.reset(table.rows); this.count = table.rows.length;
    } catch (_) { if (this.alive && ticket === this.revision) { this.error = this.a.t('表格解析失败，可返回复制原文'); } }
    finally { if (this.alive && ticket === this.revision) { this.loading = false; } }
  }
  private measure(): void {
    if (this.alive) { this.naturalWidths = tableWidths(this.a, this.getUIContext(), this.header, this.rows); }
  }
  private widths(): number[] { return fitTableWidths(this.naturalWidths, this.availableWidth); }
  private gridHeight(): number {
    if (this.rows.length > 12 || this.availableHeight <= 0) { return Math.max(1, this.availableHeight); }
    const widths = this.widths(); const ui = this.getUIContext();
    const height = rowHeight(this.a, ui, this.header, widths, true) + this.rows.reduce((sum: number, row: MarkdownRow) => sum + rowHeight(this.a, ui, row.cells, widths, false), 0);
    return Math.min(height, this.availableHeight);
  }
  private exportValue(csv: boolean): string {
    const value = contentAction('render-table', '', '', [this.header].concat(this.rows.map(row => row.cells)).map(row => row.map(cell => cell.text)));
    return csv ? value.csv : value.plainText;
  }
  private async copy(): Promise<void> {
    try { await copyContent(this.exportValue(false)); this.getUIContext().getPromptAction().showToast({ message: this.a.t('已复制') }); }
    catch (_) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('复制失败') }); }
  }
  @Builder private actions() {
    Row() {
      DocumentIcon({ a: this.a, icon: 'table_rotate', label: this.a.t('旋转屏幕'), controlId: 'table-reader-rotate', available: !this.rotating, onPress: () => this.toggleOrientation() })
      DocumentIcon({ a: this.a, icon: 'table_copy', label: this.a.t('复制表格'), controlId: 'table-reader-copy', available: !this.loading && !this.error, onPress: () => this.copy() })
      DocumentIcon({ a: this.a, icon: 'table_download', label: this.a.t('下载 CSV'), controlId: 'table-reader-export', available: !this.loading && !this.error, onPress: () => this.onExport(this.exportValue(true)) })
    }
  }
  build() {
    Column() {
      DocumentHeader({ a: this.a, title: this.a.t('表格'), onBack: this.onBack }) { this.actions() }
      if (this.loading) { LoadingProgress().width(30).height(30).color(this.a.accent) }
      if (this.error) { Text(this.error).fontSize(this.a.font(14)).fontColor(this.a.muted).padding(20) }
      Stack({ alignContent: Alignment.TopStart }) {
        Scroll() {
          Column() {
            TableCells({ a: this.a, values: this.header, widths: this.widths(), header: true, onLink: this.onLink })
            List() {
              LazyForEach(this.data, (row: MarkdownRow, index: number) => {
                ListItem() { TableCells({ a: this.a, values: row.cells, widths: this.widths(), alternate: index % 2 === 1, onLink: this.onLink }) }
              }, (_row: MarkdownRow, index: number) => index.toString())
            }.id('table-reader-list').accessibilityDescription(this.count + ' ' + this.a.t('行')).width('100%').cachedCount(2).layoutWeight(1)
          }.height(this.gridHeight()).width(this.widths().reduce((sum: number, width: number) => sum + width, 0))
        }.id('table-reader-horizontal').scrollable(ScrollDirection.Horizontal).align(Alignment.TopStart).scrollBar(BarState.Auto)
          .width('100%').height(this.gridHeight()).borderRadius(12).clip(true).border({ width: 0.5, color: new DocumentPalette(this.a.dark).border })
          .onAreaChange((_old: Area, area: Area) => { const width = Number(area.width); if (Math.abs(width - this.availableWidth) > .1) { this.availableWidth = width; } })
      }.width('100%').layoutWeight(1).padding({ left: 20, right: 20, top: 12, bottom: 20 })
        .onAreaChange((_old: Area, area: Area) => { this.availableHeight = Math.max(1, Number(area.height) - 32); })
    }.id('table-reader').width('100%').height('100%').backgroundColor(new DocumentPalette(this.a.dark).background)
  }
}
