import { ContentResponse } from '../core/ContentTypes';
import { highlightMarkdownCodeAsync } from '../core/MarkdownParser';
import { Appearance } from '../core/Appearance';
import { MarkdownRun, textRun } from '../core/MarkdownModel';
import { MarkdownBlock } from '../core/MarkdownBlock';
import { copyContent } from '../core/ContentActions';
import { MarkdownText } from './MarkdownText';

@Component
export struct MarkdownCode {
  @ObjectLink a: Appearance;
  @ObjectLink @Watch('changed') block: MarkdownBlock;
  @Prop prefix: string = '';
  @State private runs: MarkdownRun[] = [];
  @Prop @Watch('changed') expanded: boolean = false;
  onExpanded: (value: boolean) => void = () => {};
  @State private copied: boolean = false;
  @State private language: string = '';
  private colorReady: boolean = false;
  private alive: boolean = false;
  private revision: number = 0;
  private lastLanguage: string = '';
  private last: string = '';
  private timer: number = -1;
  aboutToAppear(): void { this.alive = true; if (!this.colorReady) { this.last = '\u0000'; } this.changed(); }
  aboutToDisappear(): void { this.alive = false; this.revision++; clearTimeout(this.timer); }
  private async changed(): Promise<void> {
    let source = this.block.text;
    if (!this.expanded) { let end = 0; for (let line = 0; line < 24; line++) { const next = source.indexOf('\n', end); if (next < 0) { end = source.length; break; } end = next + 1; } source = source.substring(0, Math.min(end, 4000)); }
    const language = this.block.language;
    if (this.last === source && this.lastLanguage === language) { return; } this.last = source; this.lastLanguage = language; this.copied = false;
    this.colorReady = false; const ticket = ++this.revision;
    // 先显示正文，后台高亮；长输出不用在 UI 线程执行共享词法扫描。
    this.runs = [textRun(source)]; this.language = language || this.a.t('代码');
    if (source.length > 20000) { this.colorReady = true; return; }
    let response: ContentResponse;
    try { response = JSON.parse(await highlightMarkdownCodeAsync(source, language) as string) as ContentResponse; }
    catch (_) { return; }
    if (!this.alive || ticket !== this.revision || !response.ok || !response.presentation) { return; }
    const value = response.presentation;
    this.language = value.language === 'plaintext' ? this.block.language || this.a.t('代码') : value.language;
    const runs: MarkdownRun[] = []; let cursor = 0;
    value.spans.forEach(span => {
      if (span.start > cursor) { runs.push(textRun(source.substring(cursor, span.start))); }
      const run = textRun(source.substring(span.start, span.end)); run.light = span.light; run.dark = span.dark; runs.push(run); cursor = span.end;
    });
    if (cursor < source.length) { runs.push(textRun(source.substring(cursor))); }
    this.runs = runs; this.colorReady = true;
  }
  private async copy(): Promise<void> {
    try { await copyContent(this.block.text); this.copied = true; clearTimeout(this.timer); this.timer = setTimeout(() => this.copied = false, 1400); }
    catch (_) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('复制失败') }); }
  }
  build() {
    Column({ space: 6 }) {
      Row() {
        Text(this.language.toUpperCase()).fontSize(this.a.font(11)).fontFamily('monospace').fontColor(this.a.muted).layoutWeight(1)
        if (this.expanded && (this.block.text.length > 4000 || this.block.text.split('\n').length > 24)) {
          Button(this.a.t('收起代码')).id(this.prefix + '-code-collapse-top-' + this.block.id).height(44).fontSize(this.a.font(12)).fontColor(this.a.accent).backgroundColor(Color.Transparent).onClick(() => this.onExpanded(false))
        }
        Button(this.a.t(this.copied ? '已复制' : '复制代码')).id(this.prefix + '-code-copy-' + this.block.id).height(44)
          .fontSize(this.a.font(12)).fontColor(this.a.accent).backgroundColor(Color.Transparent).onClick(() => this.copy())
      }.width('100%')
      Scroll() {
        MarkdownText({ a: this.a, runs: this.runs, fontPoints: 13, mono: true, maxLines: this.expanded ? 100000 : 24, controlId: this.prefix + '-code-' + this.block.id })
      }.id(this.prefix + '-code-scroll-' + this.block.id).scrollable(ScrollDirection.Horizontal).align(Alignment.TopStart).scrollBar(BarState.Auto).width('100%')
      if (this.block.text.split('\n').length > 24 || this.block.text.length > 4000) {
        Button(this.a.t(this.expanded ? '收起代码' : '展开完整代码')).id(this.prefix + '-code-expand-' + this.block.id).height(44).fontSize(this.a.font(12))
          .fontColor(this.a.accent).backgroundColor(Color.Transparent).onClick(() => this.onExpanded(!this.expanded))
      }
    }.padding({ left: 12, right: 12, bottom: 12 }).width('100%').borderRadius(9).backgroundColor(this.a.dark ? '#2C2C2E' : '#E9E9EB')
  }
}
