import { Appearance } from '../core/Appearance';
import { Command, SharedState, WorkspaceFilesData, WorkspaceEntryData } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct WorkspaceFilesPage {
  @ObjectLink a: Appearance;
  @Prop state: SharedState;
  @Prop data: WorkspaceFilesData;
  onBack: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  aboutToAppear(): void { if (!this.data.busy) { this.load(this.data.path); } }
  private load(path: string): void { const c = new Command('files-list'); c.sessionId = this.state.selectedSessionId; c.path = path; this.onAction(c); }
  private open(path: string): void { const c = new Command('files-resolve'); c.sessionId = this.state.selectedSessionId; c.path = path; this.onAction(c); }
  private up(): string { const parts = this.data.path.split('/').filter(part => !!part && part !== '.'); parts.pop(); return parts.join('/') || '.'; }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t('工作区文件'), onBack: this.onBack })
      Row({ space: 8 }) {
        Button(this.a.t('上一级')).id('files-parent').height(44).fontSize(this.a.font(13)).fontColor(this.a.accent).backgroundColor(this.a.surface).enabled(this.data.available && !this.data.busy && this.data.path !== '.').onClick(() => this.load(this.up()))
        Text(this.data.path === '.' ? '/' : this.data.path).fontSize(this.a.font(13)).fontColor(this.a.muted).layoutWeight(1).maxLines(2).copyOption(CopyOptions.LocalDevice)
        Button(this.a.t('刷新')).id('files-refresh').height(44).fontSize(this.a.font(13)).fontColor(this.a.accent).backgroundColor(this.a.surface).enabled(this.data.available && !this.data.busy).onClick(() => this.load(this.data.path))
      }.width('100%').padding({ left: 16, right: 16 })
      if (!this.data.available) { Text(this.a.t('连接支持文件下载的网关后才能读取工作区')).fontSize(this.a.font(14)).fontColor(this.a.muted).padding(20) }
      if (this.data.error) { Text(this.data.error).id('files-error').fontSize(this.a.font(14)).fontColor('#D65D64').padding(16) }
      if (this.data.busy) {
        Row({ space: 10 }) {
          LoadingProgress().height(24).width(24).color(this.a.accent)
          Text(this.data.download ? this.data.download.name + ' · ' + this.data.download.receivedBytes + ' / ' + this.data.download.size + ' B' : this.a.t('正在读取文件')).fontSize(this.a.font(12)).fontColor(this.a.muted).layoutWeight(1).maxLines(2)
          Button(this.a.t('取消')).id('files-cancel').height(44).fontSize(this.a.font(13)).onClick(() => this.onAction(new Command('files-cancel')))
        }.width('100%').padding(16)
      }
      Scroll() {
        Column({ space: 8 }) {
          if (!this.data.entries.length && !this.data.loading && this.data.available && !this.data.error) { Text(this.a.t('此目录为空')).fontSize(this.a.font(14)).fontColor(this.a.muted).padding(24) }
          ForEach(this.data.entries, (entry: WorkspaceEntryData) => {
            Button({ type: ButtonType.Normal }) {
              Row({ space: 12 }) {
                Text(entry.kind === 'directory' ? '▰' : '▤').fontSize(24).fontColor(this.a.accent)
                Column({ space: 5 }) {
                  Text(entry.name).fontSize(this.a.font(15)).fontColor(this.a.fg).width('100%').maxLines(2)
                  if (entry.bytes !== undefined) { Text(entry.bytes.toString() + ' B').fontSize(this.a.font(11)).fontColor(this.a.muted).width('100%') }
                }.layoutWeight(1)
                Text('›').fontSize(24).fontColor(this.a.muted)
              }.padding(16).width('100%')
            }.id('workspace-file-' + entry.path).width('100%').backgroundColor(this.a.surface).borderRadius(20).enabled(this.data.available && !this.data.busy).onClick(() => { if (entry.kind === 'directory') { this.load(entry.path); } else { this.open(entry.path); } })
          }, (entry: WorkspaceEntryData) => JSON.stringify(entry))
        }.width('100%').padding(16).constraintSize({ maxWidth: 900 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.bg)
  }
}
