import { Appearance } from '../core/Appearance';
import { GatewayImageData, ImageAssetData, PresentedFileData } from '../core/Models';
import { MessageView } from './ViewModels';

@Component
struct ImageTile {
  @ObjectLink a: Appearance;
  @Prop image: GatewayImageData;
  @Prop asset: ImageAssetData = { path: '', thumbnail: '', loading: false, error: '' };
  @Prop active: boolean = true;
  onLoad: (image: GatewayImageData, retry: boolean) => void = () => {};
  onOpen: (image: GatewayImageData) => void = () => {};
  private requested: boolean = false;
  private alive: boolean = false;
  aboutToAppear(): void { this.alive = true; }
  aboutToDisappear(): void { this.alive = false; }
  build() {
    Button({ type: ButtonType.Normal }) {
      Column({ space: 6 }) {
        if (this.asset.thumbnail) { Image(this.asset.thumbnail).height(144).width(180).objectFit(ImageFit.Cover).borderRadius(16) }
        else if (this.asset.loading) { LoadingProgress().height(44).width(44).color(this.a.accent).margin(30) }
        else { Text(this.a.t(this.asset.error ? '图片加载失败，点击重试' : '点击加载图片')).fontSize(this.a.font(13)).fontColor(this.a.muted).width(180).height(100).textAlign(TextAlign.Center) }
        Text(this.image.name ?? this.a.t('图片')).fontSize(this.a.font(11)).fontColor(this.a.muted).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width(180)
      }.padding(8)
    }.id('image-attachment-' + this.image.attachmentId).backgroundColor(this.a.surface).borderRadius(18).enabled(!this.asset.loading)
      .accessibilityText(this.image.name ?? this.a.t('图片')).onClick(() => { if (this.alive && this.active) { if (this.asset.path) { this.onOpen(this.image); } else { this.onLoad(this.image, true); } } })
      .onVisibleAreaChange([.1], (visible: boolean) => { if (visible && this.alive && this.active && !this.requested && !this.asset.path && !this.asset.error) { this.requested = true; this.onLoad(this.image, false); } })
  }
}

@Component
export struct MessageAttachments {
  @ObjectLink a: Appearance;
  @ObjectLink message: MessageView;
  @Prop active: boolean = true;
  @Prop assets: Record<string, ImageAssetData> = {};
  onLoad: (image: GatewayImageData, retry: boolean) => void = () => {};
  onImage: (image: GatewayImageData, images: GatewayImageData[]) => void = () => {};
  onFile: (file: PresentedFileData) => void = () => {};
  private asset(image: GatewayImageData): ImageAssetData { return this.assets['asset:' + image.attachmentId] ?? { path: '', thumbnail: '', loading: false, error: '' }; }
  build() {
    Column({ space: 8 }) {
      if (this.message.images.length) {
        Scroll() { Row({ space: 10 }) {
          ForEach(this.message.images, (image: GatewayImageData) => {
            ImageTile({ a: this.a, image: image, asset: this.asset(image), active: this.active, onLoad: this.onLoad, onOpen: value => this.onImage(value, this.message.images) })
          }, (image: GatewayImageData) => JSON.stringify(image))
        } }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%').constraintSize({ minHeight: 180 })
      }
      ForEach(this.message.files, (file: PresentedFileData) => {
        Row({ space: 10 }) {
          Text('▤').fontSize(24).fontColor(this.a.accent)
          Column({ space: 5 }) {
            Text(file.name || file.path || this.a.t('文件')).fontSize(this.a.font(14)).fontColor(this.a.fg).maxLines(2).width('100%')
            if (file.bytes !== undefined) { Text(file.bytes.toString() + ' B').fontSize(this.a.font(11)).fontColor(this.a.muted).width('100%') }
            if (file.description) { Text(file.description).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%') }
          }.layoutWeight(1)
        }.width('100%').padding(12).borderRadius(16).backgroundColor(this.a.surface).id('message-file-' + (file.attachmentId ?? file.path))
          .onClick(() => { if (this.active && file.path) { this.onFile(file); } })
      }, (file: PresentedFileData) => JSON.stringify(file))
    }.width('100%')
  }
}
