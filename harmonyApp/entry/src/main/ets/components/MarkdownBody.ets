import { taskpool } from '@kit.ArkTS';
import { Appearance } from '../core/Appearance';
import { MarkdownData } from '../core/MarkdownModel';
import { MarkdownBlock } from '../core/MarkdownBlock';
import { parseMarkdownInWorker } from '../core/MarkdownWorker';
import { MarkdownText } from './MarkdownText';
import { MarkdownCode } from './MarkdownCode';
import { MarkdownTable } from './MarkdownTable';

@Component
export struct MarkdownBody {
  @ObjectLink a: Appearance;
  @Prop @Watch('changed') text: string = '';
  @Prop prefix: string = '';
  @Prop @Watch('changed') active: boolean = true;
  @Prop persistCodeExpansion: boolean = false;
  @Prop expandedCodes: string[] = [];
  @State private localExpandedCodes: string[] = [];
  onCodeExpanded: (id: string, expanded: boolean) => void = () => {};
  @Prop preview: boolean = false;
  onRendered: () => void = () => {};
  onLink: (url: string) => void = () => {};
  onPreview: (source: string) => void = () => {};
  onExport: (csv: string) => void = () => {};
  @State private blocks: MarkdownBlock[] = [];
  @State private failed: boolean = false;
  private timer: number = -1;
  private alive: boolean = false;
  private busy: boolean = false;
  private epoch: number = 0;
  private parsed: boolean = false;
  private source: string = '';
  aboutToAppear(): void { this.alive = true; this.changed(); }
  aboutToDisappear(): void { this.alive = false; this.epoch++; clearTimeout(this.timer); this.timer = -1; }
  private changed(): void {
    if (this.parsed && this.source === this.text) { if (this.alive && this.active) { this.onRendered(); } return; }
    if (this.busy || this.timer >= 0 || !this.alive || !this.active) { return; }
    this.timer = setTimeout(() => { this.timer = -1; this.parse(); }, 64);
  }
  private async parse(): Promise<void> {
    const ticket = this.epoch; const source = this.text; this.busy = true;
    try {
      const json = await taskpool.execute(new taskpool.Task(parseMarkdownInWorker, source)) as string;
      if (!this.alive || !this.active || ticket !== this.epoch) { return; }
      const values = JSON.parse(json) as MarkdownData[];
      this.blocks = values.map(value => {
        const old = this.blocks.find(block => block.id === value.id);
        if (old) { old.update(value); return old; } return new MarkdownBlock(value);
      });
      this.source = source; this.parsed = true; this.failed = false; this.onRendered();
    } catch (_) { if (this.alive && ticket === this.epoch) { this.failed = true; this.source = source; this.onRendered(); } }
    finally { this.busy = false; if (this.alive && this.source !== this.text) { this.changed(); } }
  }
  private codeExpanded(id: string): boolean { return (this.persistCodeExpansion ? this.expandedCodes : this.localExpandedCodes).includes(id); }
  private expandCode(id: string, value: boolean): void {
    if (this.persistCodeExpansion) { this.onCodeExpanded(id, value); }
    else { this.localExpandedCodes = value ? this.localExpandedCodes.filter(old => old !== id).concat([id]) : this.localExpandedCodes.filter(old => old !== id); }
  }
  build() {
    Column({ space: 10 }) {
      if (this.failed) {
        Text(this.a.t('内容解析失败')).fontSize(this.a.font(12)).fontColor('#D65D64')
        Text(this.text).id(this.prefix).fontSize(this.a.font(16)).fontColor(this.a.fg).copyOption(CopyOptions.LocalDevice).width('100%')
      } else {
        ForEach(this.blocks, (block: MarkdownBlock, index: number) => {
          if (block.kind === 'code') {
            MarkdownCode({ a: this.a, block, prefix: this.prefix, expanded: this.codeExpanded(block.id), onExpanded: value => this.expandCode(block.id, value) })
          } else if (block.kind === 'table') {
            MarkdownTable({ a: this.a, block, prefix: this.prefix, preview: this.preview, onLink: this.onLink, onPreview: this.onPreview, onExport: this.onExport })
          } else if (block.kind === 'hr') { Divider().color(this.a.line).margin({ top: 4, bottom: 4 }) }
          else {
            Row({ space: 6 }) {
              if (block.prefix) { Text(block.prefix).fontSize(this.a.font(16)).fontColor(this.a.fg).alignSelf(ItemAlign.Start) }
              MarkdownText({ a: this.a, runs: block.runs, fillWidth: true,
                fontPoints: block.kind === 'heading' ? (block.depth === 1 ? 26 : block.depth === 2 ? 22 : 18) : 16,
                bold: block.kind === 'heading', controlId: index === 0 ? this.prefix : this.prefix + '-block-' + block.id, onLink: this.onLink }).layoutWeight(1)
            }.width('100%').padding({ left: block.kind !== 'heading' && block.depth ? (block.depth - 1) * 14 : block.quote ? 12 : 0 })
              .border({ width: { left: block.quote ? 3 : 0 }, color: this.a.line })
          }
        }, (block: MarkdownBlock) => block.id)
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}
