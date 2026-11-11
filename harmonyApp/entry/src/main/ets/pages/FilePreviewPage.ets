import { common, wantConstant } from '@kit.AbilityKit';
import { fileUri, picker } from '@kit.CoreFileKit';
import { systemShare } from '@kit.ShareKit';
import { taskpool } from '@kit.ArkTS';
import { PdfView, pdfService, pdfViewManager } from '@kit.PDFKit';
import { Appearance } from '../core/Appearance';
import { LocalFileData } from '../core/Models';
import { previewFileText, exportWorkspaceFile } from '../core/FileWorker';
import { copyContent } from '../core/ContentActions';
import { PageHeader } from '../components/DesignSystem';
import { MarkdownBody } from '../components/MarkdownBody';
import { ZoomPhoto } from './PhotoPreviewPage';

@Component
struct NativePdfPanel {
  @ObjectLink a: Appearance;
  @Prop path: string;
  @State private loading: boolean = true;
  @State private error: string = '';
  @State private password: string = '';
  @State private needsPassword: boolean = false;
  @State private pages: number = 0;
  private pdf: pdfViewManager.PdfController = new pdfViewManager.PdfController();
  private alive: boolean = false;
  aboutToAppear(): void { this.alive = true; this.load(); }
  aboutToDisappear(): void { this.alive = false; this.password = ''; this.pdf.releaseDocument(); }
  private async load(): Promise<void> {
    this.loading = true; this.error = '';
    try {
      const result = await this.pdf.loadDocument(this.path, this.password);
      if (!this.alive) { this.pdf.releaseDocument(); return; }
      this.needsPassword = result === pdfService.ParseResult.PARSE_ERROR_PASSWORD;
      if (result !== pdfService.ParseResult.PARSE_SUCCESS) { this.error = this.a.t(this.needsPassword ? '请输入 PDF 密码后重试' : 'PDF 解析失败，可使用系统应用打开'); }
      else { this.pages = this.pdf.getPageCount(); }
    } catch (_) { if (this.alive) { this.error = this.a.t('PDF 暂不可用，请使用系统应用打开'); } }
    finally { if (this.alive) { this.loading = false; } }
  }
  build() {
    Column() {
      if (this.loading) { LoadingProgress().width(28).height(28).color(this.a.accent) }
      if (this.error) { Text(this.error).id('pdf-error').fontSize(this.a.font(14)).fontColor(this.a.muted).padding(16) }
      if (this.needsPassword) { Row() { TextInput({ text: this.password, placeholder: this.a.t('密码') }).type(InputType.Password).onChange(value => this.password = value).layoutWeight(1); Button(this.a.t('重试')).height(48).onClick(() => this.load()) }.padding(16) }
      if (this.pages) { Text(this.pages.toString() + ' ' + this.a.t('页')).id('pdf-pages').fontSize(this.a.font(12)).fontColor(this.a.muted) }
      PdfView({ controller: this.pdf, pageLayout: pdfService.PageLayout.LAYOUT_SINGLE, pageFit: pdfService.PageFit.FIT_WIDTH, isContinuous: true, showScroll: true }).id('native-pdf-view').layoutWeight(1).width('100%')
    }.height('100%').width('100%')
  }
}

@Component
export struct FilePreviewPage {
  @ObjectLink a: Appearance;
  @Prop file: LocalFileData;
  @State private text: string = '';
  @State private loading: boolean = false;
  @State private error: string = '';
  @State private exporting: boolean = false;
  @State private raw: boolean = false;
  private alive: boolean = false;
  private opened?: LocalFileData;
  onBack: () => void = () => {};
  onLink: (url: string) => void = () => {};
  onClosed: (file: LocalFileData) => void = () => {};
  onAcquire: (file: LocalFileData) => (() => void) | undefined = () => undefined;
  aboutToAppear(): void { this.alive = true; this.opened = this.file; if (this.isText()) { this.read(); } }
  aboutToDisappear(): void { this.alive = false; const file = this.opened; if (file) { setTimeout(() => this.onClosed(file), 0); } }
  private isText(): boolean { return this.file.mediaType.startsWith('text/') || /\.(md|txt|json|ya?ml|xml|csv|kt|kts|js|ts|ets|tsx|jsx|py|c|cpp|h|hpp|java|swift|sh|log|toml|css|html)$/i.test(this.file.name); }
  private isMarkdown(): boolean { return /\.md$/i.test(this.file.name) && this.file.size <= 524288; }
  private async read(): Promise<void> {
    this.loading = true; const file = this.file;
    try { const text = await taskpool.execute(new taskpool.Task(previewFileText, file.localPath)) as string; if (this.alive && this.file.transferId === file.transferId) { this.text = text; } }
    catch (_) { if (this.alive) { this.error = this.a.t('文件较大或不适合文本预览，请使用系统应用打开'); } }
    finally { if (this.alive) { this.loading = false; } }
  }
  private context(): common.UIAbilityContext | undefined { return this.getUIContext().getHostContext() as common.UIAbilityContext | undefined; }
  private async save(): Promise<void> {
    const context = this.context(); if (!context || this.exporting) { return; }
    const file = this.file; const release = this.onAcquire(file);
    if (!release) { this.error = this.a.t('文件副本已失效，请重新下载'); return; }
    this.exporting = true;
    try { const uris = await new picker.DocumentViewPicker(context).save({ newFileNames: [file.name] }); if (uris.length) { await taskpool.execute(new taskpool.Task(exportWorkspaceFile, file.localPath, uris[0]), taskpool.Priority.HIGH); if (this.alive) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('已保存') }); } } }
    catch (_) { if (this.alive) { this.error = this.a.t('文件保存失败，请重试'); } }
    finally { release(); if (this.alive) { this.exporting = false; } }
  }
  private async open(): Promise<void> {
    try { await this.context()?.startAbility({ action: 'ohos.want.action.viewData', uri: fileUri.getUriFromPath(this.file.localPath), type: this.file.mediaType, flags: wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION }); }
    catch (_) { if (this.alive) { this.error = this.a.t('未找到可打开此文件的系统应用'); } }
  }
  private async share(): Promise<void> {
    const context = this.context(); if (!context) { return; }
    try { const data = new systemShare.SharedData({ utd: 'general.file', uri: fileUri.getUriFromPath(this.file.localPath), title: this.file.name }); await new systemShare.ShareController(data).show(context, {}); }
    catch (_) { if (this.alive) { this.error = this.a.t('系统分享暂不可用'); } }
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.file.name, onBack: this.onBack })
      Row({ space: 10 }) {
        Button(this.a.t('保存')).id('file-preview-save').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).fontSize(this.a.font(13)).enabled(!this.exporting).onClick(() => this.save())
        Button(this.a.t('打开')).id('file-preview-open').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).fontSize(this.a.font(13)).onClick(() => this.open())
        Button(this.a.t('分享')).id('file-preview-share').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).fontSize(this.a.font(13)).onClick(() => this.share())
        if (this.isText() && !this.loading && !this.error) { Button(this.a.t('复制')).id('file-preview-copy').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).fontSize(this.a.font(13)).onClick(() => copyContent(this.text)) }
      }.width('100%').padding({ left: 12, right: 12 })
      if (this.loading) { LoadingProgress().height(30).width(30).color(this.a.accent).margin(24) }
      if (this.error) { Text(this.error).id('file-preview-error').fontSize(this.a.font(14)).fontColor(this.a.muted).padding(16) }
      if (this.file.mediaType === 'application/pdf' && canIUse('SystemCapability.OfficeService.PDFService.Core')) { NativePdfPanel({ a: this.a, path: this.file.localPath }).layoutWeight(1) }
      else if (this.file.mediaType.startsWith('image/')) { ZoomPhoto({ source: 'file://' + this.file.localPath }).layoutWeight(1) }
      else if (this.isText()) {
        if (this.isMarkdown()) { Button(this.a.t(this.raw ? '预览' : '原文')).id('file-preview-mode').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).fontSize(this.a.font(13)).onClick(() => this.raw = !this.raw) }
        Scroll() {
          Column() {
            if (this.isMarkdown() && !this.raw) { MarkdownBody({ a: this.a, text: this.text, prefix: 'file-markdown', onLink: this.onLink }) }
            else { Text(this.text).id('file-preview-text').fontSize(this.a.font(14)).fontColor(this.a.fg).fontFamily('monospace').width('100%').copyOption(CopyOptions.LocalDevice) }
          }.padding(16).width('100%')
        }.layoutWeight(1).width('100%').align(Alignment.TopStart)
      } else { Text(this.a.t('此文件可保存或使用系统应用打开')).fontSize(this.a.font(15)).fontColor(this.a.muted).padding(30).layoutWeight(1) }
    }.width('100%').height('100%').backgroundColor(this.a.bg)
  }
}
