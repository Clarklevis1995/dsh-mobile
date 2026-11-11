import { Appearance, iconTint } from '../core/Appearance';
import { SharedState, DirectoryEntry, WorkspaceRow } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct WorkspacePicker {
  @ObjectLink a: Appearance;
  @Prop @Watch('syncPath') state: SharedState;
  @Prop directoryMode: boolean = false;
  @Prop workspaceId: string = '';
  @State private pathDraft: string = '';
  private lastPath: string = '';
  aboutToAppear(): void { this.syncPath(); }
  private syncPath(): void {
    if (this.lastPath !== this.state.directory.path) { this.lastPath = this.state.directory.path; this.pathDraft = this.lastPath; }
  }
  onBack: () => void = () => {};
  onSelect: (id: string) => void = () => {};
  onBrowse: (path?: string) => void = () => {};
  onCreate: (path: string) => void = () => {};
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t(this.directoryMode ? '选择目录' : '工作区'), onBack: this.onBack })
      Scroll() {
        Column({ space: 12 }) {
          if (this.directoryMode) {
            Row({ space: 8 }) {
              TextInput({ text: this.pathDraft, placeholder: '/path/to/workspace' }).id('directory-path').height(48).layoutWeight(1).fontColor(this.a.fg).backgroundColor(this.a.surface).onChange(value => this.pathDraft = value)
              Button(this.a.t('前往')).fontSize(this.a.font(16)).id('directory-go').height(48).enabled(!this.state.directoryLoading && !!this.pathDraft.trim()).onClick(() => this.onBrowse(this.pathDraft.trim()))
            }.width('100%')
            Text(this.state.directory.path || this.a.t('加载中')).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%').copyOption(CopyOptions.LocalDevice)
            if (this.state.directoryLoading) { LoadingProgress().width(32).height(32).color(this.a.accent) }
            Row({ space: 8 }) {
              Button('Home').fontSize(this.a.font(14)).backgroundColor(this.a.surface).fontColor(this.a.accent).enabled(!this.state.directoryLoading).onClick(() => this.onBrowse(this.state.directory.home || undefined))
              ForEach(this.state.directory.crumbs, (entry: DirectoryEntry) => {
                Button(entry.name).fontSize(this.a.font(14)).backgroundColor(this.a.surface).fontColor(this.a.accent).enabled(!this.state.directoryLoading).onClick(() => this.onBrowse(entry.path))
              }, (entry: DirectoryEntry) => entry.path)
            }.width('100%')
            ForEach(this.state.directory.entries, (entry: DirectoryEntry) => {
              Row({ space: 12 }) {
                Image($rawfile('icons/folder_outline.svg')).width(24).height(24).colorFilter(iconTint(this.a.accent))
                Text(entry.name).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
                Image($rawfile('icons/chevron_right.svg')).width(16).height(16).colorFilter(iconTint(this.a.muted))
              }.padding(16).backgroundColor(this.a.surface).borderRadius(18).width('100%').enabled(!this.state.directoryLoading)
                .id('directory-' + entry.name).onClick(() => this.onBrowse(entry.path))
            }, (entry: DirectoryEntry) => entry.path)
          } else {
            Button(this.a.t('全部会话')).fontSize(this.a.font(16)).id('workspace-all').height(54).fontColor(this.a.fg).backgroundColor(this.a.surface).width('100%').onClick(() => this.onSelect('__all__'))
            Button(this.a.t('未分组')).fontSize(this.a.font(16)).id('workspace-ungrouped').height(54).fontColor(this.a.fg).backgroundColor(this.a.surface).width('100%').onClick(() => this.onSelect(''))
            ForEach(this.state.workspaces, (workspace: WorkspaceRow) => {
              Column({ space: 6 }) {
                Text(workspace.title).fontSize(this.a.font(17)).fontColor(this.a.fg).width('100%')
                Text(workspace.path).fontSize(this.a.font(12)).fontColor(this.a.muted).maxLines(2).width('100%')
              }.padding(18).backgroundColor(this.a.surface).borderRadius(20).width('100%').id('workspace-' + workspace.workspaceId).onClick(() => this.onSelect(workspace.workspaceId))
            })
            Button(this.a.t('新建工作区')).fontSize(this.a.font(16)).id('workspace-browse').height(48).width('100%').enabled(this.state.connected).onClick(() => this.onBrowse())
          }
        }.padding(20).width('100%').constraintSize({ maxWidth: 720 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
      if (this.directoryMode) {
        Button(this.a.t('使用此目录')).fontSize(this.a.font(16)).id('workspace-create').height(48).margin(16)
          .enabled(this.state.connected && !!this.state.directory.path && !this.state.directoryLoading && !this.state.catalogBusy.includes('workspace-create'))
          .onClick(() => this.onCreate(this.state.directory.path))
      }
    }.width('100%').height('100%').backgroundColor(this.a.bg)
  }
}
