import { Appearance } from '../core/Appearance';
import { Command, UploadData, UploadDraftData, PhotoDraftData, emptyUploads } from '../core/Models';

@Component
export struct UploadDock {
  @ObjectLink a: Appearance;
  @Prop data: UploadData = emptyUploads();
  @Prop preparing: boolean = false;
  @Prop submitting: boolean = false;
  imagePath: (id: string) => string = () => '';
  onPhoto: (id: string) => void = () => {};
  onAction: (command: Command) => void = () => {};
  private action(op: string, id: string): void { const c = new Command(op); c.sessionId = this.data.sessionId; c.itemId = id; this.onAction(c); }
  private label(status: string): string {
    return this.a.t(status === 'ready' ? '已就绪' : status === 'opening' ? '准备上传' : status === 'uploading' ? '正在上传' : status === 'committing' ? '正在校验' : status === 'error' ? '上传失败' : '等待上传');
  }
  build() {
    Column({ space: 8 }) {
      if (this.preparing) { Row({ space: 8 }) { LoadingProgress().width(20).height(20).color(this.a.accent); Text(this.a.t('正在准备附件')).fontSize(this.a.font(12)).fontColor(this.a.muted) }.width('100%') }
      if (this.data.photos.length) {
        Scroll() {
          Row({ space: 10 }) {
            ForEach(this.data.photos, (photo: PhotoDraftData) => {
              Stack({ alignContent: Alignment.TopEnd }) {
                Image(this.imagePath(photo.id)).width(96).height(96).objectFit(ImageFit.Cover).borderRadius(18).accessibilityText(photo.name).id('photo-draft-' + photo.id).onClick(() => this.onPhoto(photo.id))
                Button('×').width(40).height(40).fontColor(this.a.fg).backgroundColor(this.a.surface).accessibilityText(this.a.t('移除图片')).id('photo-remove-' + photo.id).enabled(!this.submitting).onClick(() => this.action('image-remove', photo.id))
              }.width(96).height(96)
            }, (photo: PhotoDraftData) => photo.id)
          }
        }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%').height(104)
      }
      if (this.data.drafts.length) {
      Scroll() { Row({ space: 10 }) {
      ForEach(this.data.drafts, (row: UploadDraftData) => {
        Row({ space: 8 }) {
          Column({ space: 4 }) {
            Text(row.name).fontSize(this.a.font(13)).fontColor(this.a.fg).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
            Text(this.label(row.status) + ' · ' + row.confirmedBytes.toString() + ' / ' + row.size.toString() + ' B').id('upload-status-' + row.id).fontSize(this.a.font(11)).fontColor(this.a.muted).width('100%')
            if (row.error) { Text(this.a.t(row.error)).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).fontSize(this.a.font(12)).fontColor('#D65D64').width('100%') }
            if (row.status === 'uploading') { Progress({ value: row.confirmedBytes, total: Math.max(1, row.size), type: ProgressType.Linear }).height(3).color(this.a.accent).width('100%') }
          }.layoutWeight(1)
          if (row.status === 'error') { Button(this.a.t('重试')).id('upload-retry-' + row.id).fontSize(this.a.font(12)).height(44).enabled(this.data.available && !this.submitting).onClick(() => this.action('file-retry', row.id)) }
          Button('×').id('upload-remove-' + row.id).accessibilityText(this.a.t('移除附件')).height(44).width(44).fontColor(this.a.muted).backgroundColor(Color.Transparent).enabled(!this.submitting).onClick(() => this.action('file-remove', row.id))
        }.padding(10).width(240).height(112).borderRadius(16).backgroundColor(this.a.surface)
      }, (row: UploadDraftData) => JSON.stringify(row))
      } }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%').height(120)
      }
    }.width('100%').padding(this.data.drafts.length || this.data.photos.length || this.preparing ? { left: 12, right: 12, top: 6 } : 0)
  }
}
