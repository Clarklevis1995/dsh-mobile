import { TableCells, tableWidths } from './TableReader';
import { Appearance } from '../core/Appearance';
import { MarkdownCell, MarkdownRow } from '../core/MarkdownModel';
import { MarkdownBlock } from '../core/MarkdownBlock';
import { contentAction, copyContent } from '../core/ContentActions';
import { MarkdownText } from './MarkdownText';

@Component
export struct MarkdownTable {
  @ObjectLink a: Appearance;
  @ObjectLink @Watch('measure') block: MarkdownBlock;
  @Prop prefix: string = '';
  @Prop preview: boolean = false;
  onLink: (url: string) => void = () => {};
  onPreview: (source: string) => void = () => {};
  onExport: (csv: string) => void = () => {};
  @State private widths: number[] = [];
  aboutToAppear(): void { this.measure(); }
  private measure(): void { this.widths = tableWidths(this.block.header, this.block.rows); }
  private matrix(): string[][] { return [this.block.header].concat(this.block.rows.map(row => row.cells)).map(row => row.map(cell => cell.text)); }
  private async copy(): Promise<void> {
    try { await copyContent(contentAction('render-table', '', '', this.matrix()).plainText); this.getUIContext().getPromptAction().showToast({ message: this.a.t('已复制') }); }
    catch (_) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('复制失败') }); }
  }
  build() {
    Column({ space: 4 }) {
      Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.End }) {
        Button(this.a.t('复制表格')).id(this.prefix + '-table-copy-' + this.block.id).fontSize(this.a.font(12)).height(44)
          .fontColor(this.a.accent).backgroundColor(Color.Transparent).onClick(() => this.copy())
        if (!this.preview) {
          Button(this.a.t('预览表格')).id(this.prefix + '-table-preview-' + this.block.id).fontSize(this.a.font(12)).height(44)
            .fontColor(this.a.accent).backgroundColor(Color.Transparent).onClick(() => this.onPreview(this.block.raw))
        }
        Button('CSV').id(this.prefix + '-table-export-' + this.block.id).fontSize(this.a.font(12)).height(44)
          .fontColor(this.a.accent).backgroundColor(Color.Transparent).onClick(() => this.onExport(contentAction('render-table', '', '', this.matrix()).csv))
      }.width('100%')
      Scroll() {
        Column() {
          TableCells({ a: this.a, values: this.block.header, widths: this.widths, header: true, onLink: this.onLink })
          ForEach(this.block.rows.slice(0, 12), (row: MarkdownRow, index: number) => { TableCells({ a: this.a, values: row.cells, widths: this.widths, alternate: index % 2 === 1, onLink: this.onLink }) })
        }.alignItems(HorizontalAlign.Start)
      }.scrollable(ScrollDirection.Horizontal).align(Alignment.TopStart).scrollBar(BarState.Auto).width('100%').borderRadius(12).border({ width: 1, color: this.a.line })
      if (this.block.rows.length > 12) { Button(this.a.t('查看完整表格') + ' · ' + this.block.rows.length + ' ' + this.a.t('行')).id(this.prefix + '-table-all-' + this.block.id).height(44).fontSize(this.a.font(13)).fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(() => this.onPreview(this.block.raw)) }
    }.width('100%')
  }
}
