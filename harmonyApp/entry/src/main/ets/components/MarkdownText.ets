import { Appearance } from '../core/Appearance';
import { MarkdownRun } from '../core/MarkdownModel';

@Component
export struct MarkdownText {
  @ObjectLink a: Appearance;
  @Prop runs: MarkdownRun[] = [];
  @Prop fontPoints: number = 16;
  @Prop bold: boolean = false;
  @Prop mono: boolean = false;
  @Prop fillWidth: boolean = false;
  @Prop cellAlign: string = 'left';
  @Prop controlId: string = '';
  @Prop maxLines: number = 100000;
  onLink: (url: string) => void = () => {};
  build() {
    Text() {
      ForEach(this.runs, (run: MarkdownRun) => {
        Span(run.text).fontWeight(run.bold || this.bold ? FontWeight.Bold : FontWeight.Normal)
          .fontStyle(run.italic ? FontStyle.Italic : FontStyle.Normal)
          .decoration({ type: run.strike ? TextDecorationType.LineThrough : run.href ? TextDecorationType.Underline : TextDecorationType.None })
          .fontFamily(run.code || this.mono ? 'monospace' : 'sans-serif')
          .textBackgroundStyle({ color: run.code ? (this.a.dark ? '#13F1F4FA' : '#130E131A') : Color.Transparent, radius: 6 })
          .fontColor(run.href ? this.a.accent : (this.a.dark ? run.dark : run.light) || this.a.fg)
          .onClick(() => { if (run.href) { this.onLink(run.href); } })
      })
    }.id(this.controlId).width(this.fillWidth ? '100%' : 'auto').fontSize(this.a.font(this.fontPoints)).lineHeight(this.a.font(this.fontPoints * 1.55))
      .copyOption(CopyOptions.LocalDevice).maxLines(this.maxLines).textOverflow({ overflow: TextOverflow.Clip })
      .textAlign(this.cellAlign === 'right' ? TextAlign.End : this.cellAlign === 'center' ? TextAlign.Center : TextAlign.Start)
  }
}
