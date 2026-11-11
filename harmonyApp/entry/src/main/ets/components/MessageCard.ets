import { MessageAttachments } from './MessageAttachments';
import { GatewayImageData, ImageAssetData, PresentedFileData } from '../core/Models';
import { Appearance, iconTint } from '../core/Appearance';
import { MessageView } from './ViewModels';
import { MarkdownBody } from './MarkdownBody';
import { copyContent } from '../core/ContentActions';
import { markdownTextAsync } from '../core/MarkdownParser';

@Component
export struct MessageCard {
  @ObjectLink a: Appearance;
  @ObjectLink message: MessageView;
  @Prop renderScope: string = '';
  @Prop active: boolean = true;
  @Prop imageAssets: Record<string, ImageAssetData> = {};
  onLoadImage: (image: GatewayImageData, retry: boolean) => void = () => {};
  onImage: (image: GatewayImageData, images: GatewayImageData[]) => void = () => {};
  onFile: (file: PresentedFileData) => void = () => {};
  onRendered: (scope: string, id: string) => void = () => {};
  onLink: (url: string) => void = () => {};
  onPreview: (source: string, table: boolean) => void = () => {};
  onExport: (csv: string) => void = () => {};
  @State private copying: boolean = false;
  private alive: boolean = false;
  aboutToAppear(): void { this.alive = true; if (this.process()) { this.onRendered(this.renderScope, this.message.id); } }
  aboutToDisappear(): void { this.alive = false; }
  private process(): boolean { return ['REASONING','CONTEXT','TOOL','JSON_TOOL','TOOL_RESULT','STATUS'].includes(this.message.kind); }
  private async text(select: boolean): Promise<void> {
    if (this.copying) { return; } this.copying = true;
    try {
      // 固定点击时的文本；流式后续更新不重置选择页。
      const source = this.message.text;
      const text = this.message.kind === 'USER' ? source : await markdownTextAsync(source) as string;
      if (!this.alive) { return; }
      if (select) { this.onPreview(text, false); } else { await copyContent(text); this.getUIContext().getPromptAction().showToast({ message: this.a.t('已复制') }); }
    } catch (_) { if (this.alive) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('复制失败') }); } }
    finally { this.copying = false; }
  }
  @Builder private menu() {
    Menu() {
      MenuItem({ content: this.a.t('复制') }).id('message-copy-' + this.message.id).onClick(() => this.text(false))
      MenuItem({ content: this.a.t('选择文字') }).id('message-select-' + this.message.id).onClick(() => this.text(true))
    }
  }
  @Builder private body() {
    MarkdownBody({ persistCodeExpansion: true, expandedCodes: this.message.expandedCodeIds, onCodeExpanded: (id: string, value: boolean) => { this.message.expandedCodeIds = value ? this.message.expandedCodeIds.filter(old => old !== id).concat([id]) : this.message.expandedCodeIds.filter(old => old !== id); }, a: this.a, active: this.active, text: this.message.text, prefix: 'message-body-' + this.message.id, onRendered: () => this.onRendered(this.renderScope, this.message.id), onLink: this.onLink,
      onPreview: source => this.onPreview(source, true), onExport: this.onExport })
  }
  @Builder private attachments() {
    if (this.message.images.length || this.message.files.length || this.message.knownFiles.length) {
      MessageAttachments({ a: this.a, message: this.message, active: this.active, assets: this.imageAssets, onLoad: this.onLoadImage, onImage: this.onImage, onFile: this.onFile })
    }
  }
  @Builder private more() {
    Button('···').id('message-menu-' + this.message.id).accessibilityText(this.a.t('消息操作')).width(44).height(44).padding(0)
      .fontColor(this.a.muted).backgroundColor(Color.Transparent).bindMenu(this.menu)
  }
  @Builder private copy() {
    Button({ type: ButtonType.Normal }) { Image($rawfile('icons/copy_message.svg')).width(19).height(19).fillColor(this.a.muted) }
      .id('message-copy-direct-' + this.message.id).accessibilityText(this.a.t('复制')).width(44).height(44).padding(0).backgroundColor(Color.Transparent).enabled(!this.copying).onClick(() => this.text(false))
  }
  build() {
    Column({ space: 6 }) {
      if (this.message.kind === 'USER') {
        Column({ space: 8 }) {
          if (this.message.text) { Text(this.message.text).id('message-body-' + this.message.id).fontSize(this.a.font(16)).lineHeight(this.a.font(24)).fontColor(this.a.fg).copyOption(CopyOptions.LocalDevice).onAppear(() => this.onRendered(this.renderScope,this.message.id)) }
          this.attachments()
        }.padding({ left: 14, right: 14, top: 12, bottom: 12 }).constraintSize({ maxWidth: '90%' }).borderRadius(15)
          .backgroundColor(this.a.dark ? '#173052' : '#E9EFFF').border({ width: .7, color: this.a.dark ? '#294B75' : '#D5E0FC', radius: 15 })
        Row() { this.copy(); this.more() }.width('100%').justifyContent(FlexAlign.End)
      } else if (this.process()) {
        Row() {
          Button({ type: ButtonType.Normal }) {
            Row({ space: 8 }) {
              Text(this.message.kind === 'CONTEXT' ? this.a.t('上下文') : this.message.title).fontSize(this.a.font(14)).fontColor(this.message.isError ? '#D65D64' : this.a.muted).layoutWeight(1).textAlign(TextAlign.Start)
              Image($rawfile('icons/chevron_right.svg')).width(16).height(16).colorFilter(iconTint(this.a.muted)).rotate({ angle: this.message.expanded ? 90 : 0 })
            }.width('100%')
          }.id('message-expand-' + this.message.id).height(44).padding(0).layoutWeight(1).backgroundColor(Color.Transparent).onClick(() => this.message.expanded = !this.message.expanded)
          this.more()
        }.width('100%')
        Divider().color(this.a.line)
        if (this.message.expanded) { this.body(); this.attachments() }
      } else {
        this.body()
        this.attachments()
        Row() { this.copy(); this.more() }.width('100%')
      }
    }.id('message-card-' + this.message.id).width('100%').alignItems(this.message.kind === 'USER' ? HorizontalAlign.End : HorizontalAlign.Start)
  }
}
