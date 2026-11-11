import { Appearance } from '../core/Appearance';
import { SessionView } from './ViewModels';

@Component
export struct SessionTile {
  @ObjectLink a: Appearance;
  @ObjectLink row: SessionView;
  @Prop glass: boolean = false;
  @Prop selected: boolean = false;
  @Prop connected: boolean = false;
  onOpen: (id: string) => void = () => {};
  onRename: (id: string) => void = () => {};
  onArchive: (id: string) => void = () => {};
  @Builder private menu() {
    Menu() {
      MenuItem({ content: this.a.t('重命名') }).enabled(this.connected).onClick(() => this.onRename(this.row.id))
      MenuItem({ content: this.a.t('归档') }).enabled(this.connected).onClick(() => this.onArchive(this.row.id))
    }
  }
  build() {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Text(this.row.title).id('session-title-' + this.row.id).fontSize(this.a.font(16)).fontWeight(FontWeight.Medium)
          .fontColor(this.glass ? '#F1F4FA' : this.a.fg).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
        if (this.row.running) { Circle().id('session-running-' + this.row.id).width(7).height(7).fill('#2EB85C').accessibilityText(this.a.t('运行中')).accessibilityLevel('yes') }
      }.width('100%')
      Text(this.row.cwd || this.row.id).fontSize(this.a.font(12)).fontColor(this.glass ? '#8AA1BE' : this.a.muted)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
    }.padding(16).width('100%').borderRadius(20)
      .backgroundColor(this.glass ? (this.selected ? '#374F70' : '#243B56') : this.a.surface)
      .border({ width: 1, color: this.glass ? '#40516A' : this.a.line })
      .id('session-' + this.row.id).onClick(() => this.onOpen(this.row.id)).bindContextMenu(this.menu, ResponseType.LongPress)
  }
}
