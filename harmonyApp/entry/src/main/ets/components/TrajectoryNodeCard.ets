import { Appearance } from '../core/Appearance';
import { TrajectoryNode } from '../core/Models';

@Observed
export class TrajectoryNodeView {
  row: TrajectoryNode;
  constructor(row: TrajectoryNode) { this.row = row; }
  update(row: TrajectoryNode): void { if (JSON.stringify(this.row) !== JSON.stringify(row)) { this.row = row; } }
}

@Component
export struct TrajectoryNodeCard {
  @ObjectLink a: Appearance;
  @ObjectLink node: TrajectoryNodeView;
  onOpen: (id: string) => void = () => {};
  build() {
            Row({ space: 12 }) {
              Column() { Circle().width(10).height(10).fill(this.node.row.live ? '#2EB85C' : this.a.accent) }.width(16).padding({ top: 10 })
              Column({ space: 6 }) {
                Row() {
                  Text(this.a.t(this.node.row.title)).fontSize(this.a.font(15)).fontWeight(FontWeight.Medium).fontColor(this.a.fg).layoutWeight(1)
                  Text(this.node.row.live ? this.a.t('实时更新') : this.node.row.duration.toFixed(1) + 's').fontSize(this.a.font(11)).fontColor(this.a.muted)
                }.width('100%')
                Text(this.node.row.subtitle).fontSize(this.a.font(13)).fontColor(this.a.muted).maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
                if (this.node.row.model) { Text((this.node.row.provider ?? '') + ' / ' + this.node.row.model).fontSize(this.a.font(12)).fontColor(this.a.accent).width('100%') }
                if (this.node.row.inputTokens !== undefined) { Text('Input ' + this.node.row.inputTokens + ' · Output ' + (this.node.row.outputTokens ?? 0)).fontSize(this.a.font(11)).fontColor(this.a.muted).width('100%') }
                Text('#' + this.node.row.startSequence + '–' + this.node.row.endSequence).fontSize(this.a.font(10)).fontColor(this.a.muted).width('100%')
              }.layoutWeight(1)
            }.id('trajectory-node-' + this.node.row.id).padding(16).width('100%').borderRadius(15).backgroundColor(this.a.surface).onClick(() => this.onOpen(this.node.row.id))
  }
}
