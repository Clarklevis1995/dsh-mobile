import { Appearance } from '../core/Appearance';
import { Command, SharedState, SessionFeatureData, QueuedItem, emptyFeatures } from '../core/Models';

@Component
export struct QueueDock {
  @ObjectLink a: Appearance;
  @Prop state: SharedState;
  @Prop @Watch('scopeChanged') data: SessionFeatureData = emptyFeatures();
  @State private expanded: boolean = false;
  private session: string = '';
  onAction: (command: Command) => void = () => {};
  onReconnect: () => void = () => {};
  aboutToAppear(): void { this.session = this.data.sessionId ?? ''; }
  private scopeChanged(): void { if (this.session !== this.data.sessionId) { this.session = this.data.sessionId ?? ''; this.expanded = false; } }
  private canAct(): boolean { return this.state.connected && this.data.queueSupported && !this.data.pendingQueueItem && !this.data.queueUncertain; }
  private update(item: string, action: string): void { const command = new Command('queue-action'); command.sessionId = this.state.selectedSessionId; command.itemId = item; command.value = action; this.onAction(command); }
  build() {
    Column({ space: 4 }) {
      if (this.data.queueError) { Text(this.a.t(this.data.queueError)).id('queue-error').fontSize(this.a.font(13)).fontColor('#D65D64').width('100%') }
      if (this.data.queueUncertain) { Button(this.a.t('重新连接')).id('queue-reconnect').height(44).onClick(this.onReconnect) }
      if (this.data.queue.length > 1) {
        Button(this.a.t('排队消息') + ' · ' + this.data.queue.length).id('queue-expand').fontSize(this.a.font(14)).height(44).width('100%')
          .fontColor(this.a.fg).backgroundColor(Color.Transparent).onClick(() => this.expanded = !this.expanded)
      }
      if (this.data.queue.length === 1 || this.expanded) {
        Scroll() {
          Column({ space: 4 }) {
            ForEach(this.data.queue, (item: QueuedItem) => {
              Row({ space: 6 }) {
                Column({ space: 3 }) {
                  Text(this.a.t(item.preview)).id('queue-text-' + item.id).fontSize(this.a.font(13)).fontColor(this.a.fg).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
                  if (item.attachmentCount) { Text(item.attachmentCount + ' ' + this.a.t('个附件')).fontSize(this.a.font(11)).fontColor(this.a.muted).width('100%') }
                }.layoutWeight(1)
                if (this.data.pendingQueueItem === item.id) { LoadingProgress().width(24).height(24).color(this.a.accent) }
                Button(this.a.t('编辑')).id('queue-edit-' + item.id).height(44).fontSize(this.a.font(12)).fontColor(this.a.accent).backgroundColor(Color.Transparent).enabled(this.canAct() && item.editable).onClick(() => this.update(item.id, 'edit'))
                Button(this.a.t('插话')).id('queue-steer-' + item.id).height(44).fontSize(this.a.font(12)).fontColor(this.a.accent).backgroundColor(Color.Transparent).enabled(this.canAct() && this.state.generating).onClick(() => this.update(item.id, 'steer'))
                Button(this.a.t('移除')).id('queue-remove-' + item.id).height(44).fontSize(this.a.font(12)).fontColor(this.a.muted).backgroundColor(Color.Transparent).enabled(this.canAct()).onClick(() => this.update(item.id, 'remove'))
              }.padding({ left: 12, right: 6 }).width('100%')
            }, (item: QueuedItem) => item.id + ":" + JSON.stringify(item.preview) + ":" + item.attachmentCount + ":" + item.editable)
          }.width('100%')
        }.width('100%').constraintSize({ maxHeight: 192 })
      }
    }.width('100%').backgroundColor(this.a.surface).borderRadius({ topLeft: 24, topRight: 24 })
  }
}
