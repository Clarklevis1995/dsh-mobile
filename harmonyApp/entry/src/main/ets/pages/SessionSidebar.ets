import { Appearance } from '../core/Appearance';
import { SharedState } from '../core/Models';
import { SessionView } from '../components/ViewModels';
import { SessionTile } from '../components/SessionTile';
import { CircleIcon, PageHeader } from '../components/DesignSystem';

@Component
export struct SessionSidebar {
  @ObjectLink a: Appearance;
  @Prop state: SharedState;
  @Prop scope: string = '';
  @Prop workspaceId: string = '__all__';
  @Link sessions: SessionView[];
  @Prop query: string = '';
  onBack: () => void = () => {};
  onSearch: (query: string) => void = () => {};
  onSelect: (id: string) => void = () => {};
  onRename: (id: string) => void = () => {};
  onArchive: (id: string) => void = () => {};
  onCreate: () => void = () => {};
  private visible(): SessionView[] { return this.sessions.filter(row => (this.workspaceId === '__all__' || (this.workspaceId ? this.state.workspaces.some(group => group.workspaceId === this.workspaceId && group.sessionIds.includes(row.id)) : !this.state.workspaces.some(group => group.sessionIds.includes(row.id)))) && (!this.query.trim() || row.title.toLowerCase().includes(this.query.trim().toLowerCase()) || this.state.searchSessionIds.includes(row.id))); }
  build() {
    Column({ space: 12 }) {
      PageHeader({ a: this.a, title: this.a.t('会话'), onBack: this.onBack })
      Row({ space: 8 }) {
        TextInput({ text: this.query, placeholder: this.a.t('搜索会话') }).id('sidebar-search').height(48).layoutWeight(1)
          .fontColor(this.a.fg).backgroundColor(this.a.surface).onChange(this.onSearch)
        CircleIcon({ icon: 'add', controlId: 'sidebar-create', label: this.a.t('新建会话'), ink: this.a.accent, fill: this.a.surface, available: this.state.connected, onPress: this.onCreate })
      }.padding({ left: 16, right: 16 }).width('100%')
      Scroll() {
        Column({ space: 10 }) {
          ForEach(this.visible(), (row: SessionView) => {
            SessionTile({ a: this.a, row: row, connected: this.state.connected, selected: row.id === this.state.selectedSessionId,
              onOpen: this.onSelect, onRename: this.onRename, onArchive: this.onArchive })
          }, (row: SessionView) => this.scope + ':' + row.id)
          if (!this.visible().length && (this.state.catalogLoading || this.state.searching)) {
            LoadingProgress().width(32).height(32).color(this.a.accent).margin(32)
          } else if (!this.visible().length) {
            Text(this.a.t(this.query.trim() ? '搜索结果为空' : '暂无会话')).fontSize(this.a.font(16)).fontColor(this.a.muted).padding(32)
          }
        }.padding(16).width('100%')
      }.layoutWeight(1).width('100%').align(Alignment.TopStart)
    }.width('100%').height('100%').backgroundColor(this.a.bg)
  }
}
