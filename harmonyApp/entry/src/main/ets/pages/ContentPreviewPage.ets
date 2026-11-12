import { TableReader } from '../components/TableReader';
import { DocumentPalette } from '../components/DocumentHeader';
import { Appearance } from '../core/Appearance';
import { MarkdownBody } from '../components/MarkdownBody';
import { PageHeader } from '../components/DesignSystem';
import { copyContent } from '../core/ContentActions';

@Component
export struct ContentPreviewPage {
  @ObjectLink a: Appearance;
  @Prop content: string = '';
  @Prop table: boolean = false;
  @Prop heading: string = '';
  onBack: () => void = () => {};
  onLink: (url: string) => void = () => {};
  onExport: (csv: string) => void = () => {};
  @State private selectionStart: number = -1;
  @State private selectionEnd: number = -1;
  private selectedFrom: number = 0;
  private selectedTo: number = 0;
  private async copy(): Promise<void> {
    const selected = this.selectedTo > this.selectedFrom ? this.content.substring(this.selectedFrom, this.selectedTo) : this.content;
    try { await copyContent(selected); this.getUIContext().getPromptAction().showToast({ message: this.a.t('已复制') }); }
    catch (_) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('复制失败') }); }
  }
  build() {
    Column() {
      if (!this.table) { PageHeader({ a: this.a, title: this.a.t(this.heading || '选择文字'), onBack: this.onBack }) }
      if (!this.table) {
        Row({ space: 12 }) {
          Button(this.a.t('全选')).id('content-select-all').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface)
            .onClick(() => { this.selectionStart = 0; this.selectionEnd = this.content.length; })
          Button(this.a.t('复制')).id('content-copy').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(() => this.copy())
        }.padding({ left: 16, right: 16, bottom: 8 }).width('100%').justifyContent(FlexAlign.End)
      }
      if (this.table) { TableReader({ a: this.a, source: this.content, onBack: this.onBack, onLink: this.onLink, onExport: this.onExport }).layoutWeight(1) }
      else {
      Scroll() {
          Text(this.content).id('content-selection').fontSize(this.a.font(16)).lineHeight(this.a.font(25)).fontColor(this.a.fg)
            .copyOption(CopyOptions.LocalDevice).textSelectable(TextSelectableMode.SELECTABLE_FOCUSABLE)
            .selection(this.selectionStart, this.selectionEnd).onTextSelectionChange((from: number, to: number) => { this.selectedFrom = from; this.selectedTo = to; })
            .width('100%').padding(16)
      }.layoutWeight(1).width('100%').align(Alignment.Top)
      }
    }.width('100%').height('100%').backgroundColor(this.table ? new DocumentPalette(this.a.dark).background : this.a.bg)
  }
}
