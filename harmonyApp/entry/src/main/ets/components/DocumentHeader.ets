import { Appearance, iconTint } from '../core/Appearance';

export class DocumentPalette {
  background: string;
  foreground: string;
  close: string;
  header: string;
  alternate: string;
  border: string;
  toolbar: string;
  icon: string;
  constructor(dark: boolean) {
    this.background = dark ? '#000000' : '#FFFFFF';
    this.foreground = dark ? '#FFFFFF' : '#000000';
    this.close = dark ? '#252528' : '#F5F5F7';
    this.header = dark ? '#252527' : '#EFEFF0';
    this.alternate = dark ? '#111113' : '#F8F8FA';
    this.border = dark ? '#353538' : '#DADADD';
    this.toolbar = dark ? '#1C1C1E' : '#F5F5F7';
    this.icon = dark ? '#AAAAB0' : '#65656B';
  }
}

@Component
export struct DocumentIcon {
  @ObjectLink a: Appearance;
  @Prop icon: string = '';
  @Prop label: string = '';
  @Prop controlId: string = '';
  @Prop available: boolean = true;
  onPress: () => void = () => {};
  build() {
    Button({ type: ButtonType.Normal }) {
      Image($rawfile('icons/' + this.icon + '.svg')).width(20).height(20)
        .colorFilter(iconTint(new DocumentPalette(this.a.dark).icon))
    }.id(this.controlId).accessibilityText(this.label).width(44).height(44).padding(0)
      .backgroundColor(Color.Transparent).enabled(this.available).onClick(this.onPress)
  }
}

@Component
export struct DocumentHeader {
  @ObjectLink a: Appearance;
  @Prop title: string = '';
  @BuilderParam actions: () => void;
  onBack: () => void = () => {};
  build() {
    Stack({ alignContent: Alignment.Center }) {
      Text(this.title).id('document-page-title').fontSize(this.a.font(17)).fontWeight(600)
        .fontColor(new DocumentPalette(this.a.dark).foreground).maxLines(1).padding({ left: 90, right: 90 })
      Row() {
        Button(this.a.t('关闭'), { type: ButtonType.Capsule }).id('page-back').width(64).height(44).padding(0)
          .fontSize(this.a.font(17)).fontWeight(FontWeight.Normal).fontColor(new DocumentPalette(this.a.dark).foreground)
          .backgroundColor(new DocumentPalette(this.a.dark).close).onClick(this.onBack)
        Blank()
        this.actions()
      }.width('100%')
    }.height(60).width('100%').padding({ left: 20, right: 20 }).backgroundColor(new DocumentPalette(this.a.dark).background)
  }
}
