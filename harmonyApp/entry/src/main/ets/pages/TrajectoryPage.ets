import { TrajectoryNodeCard, TrajectoryNodeView } from '../components/TrajectoryNodeCard';
import { PageHeader } from '../components/DesignSystem';
import { Appearance } from '../core/Appearance';
import { Command, SharedState, TrajectoryData, TrajectoryNode } from '../core/Models';

@Component
export struct TrajectoryPage {
  @Prop standalone: boolean = false;
  onBack: () => void = () => {};
  @ObjectLink a: Appearance;
  @Prop @Watch('refresh') state: SharedState;
  @Prop @Watch('received') data: TrajectoryData = { sessionId: '', nodes: [] };
  @Prop @Watch('refresh') active: boolean = false;
  onAction: (command: Command) => void = () => {};
  @State private filter: string = 'all';
  @State private items: TrajectoryNodeView[] = [];
  private models: Map<string, TrajectoryNodeView> = new Map();
  private sourceSession: string = '';
  private revision: number = -1;
  private session: string = '';
  private timer: number = -1;
  aboutToAppear(): void { this.received(); this.refresh(); }
  aboutToDisappear(): void { clearTimeout(this.timer); }
  private received(): void {
    if (this.sourceSession !== this.data.sessionId) { this.models.clear(); this.sourceSession = this.data.sessionId; }
    const ids = new Set(this.data.nodes.map(row => row.id));
    this.models.forEach((_value: TrajectoryNodeView, id: string) => { if (!ids.has(id)) { this.models.delete(id); } });
    this.items = this.data.nodes.map(row => { let item = this.models.get(row.id); if (!item) { item = new TrajectoryNodeView(row); this.models.set(row.id, item); } else { item.update(row); } return item; });
  }
  private refresh(): void {
    if (!this.active || !this.state.selectedSessionId) { return; }
    if (this.session === this.state.selectedSessionId && this.revision === this.state.historyRevision) { return; }
    if (this.timer >= 0) { return; }
    const session = this.state.selectedSessionId;
    this.timer = setTimeout(() => {
      this.timer = -1;
      if (!this.active || this.state.selectedSessionId !== session) { return; }
      this.session = session; this.revision = this.state.historyRevision;
      const command = new Command('trajectory'); command.sessionId = session; this.onAction(command);
    }, 100);
  }
  private nodes(): TrajectoryNodeView[] {
    if (this.data.sessionId !== this.state.selectedSessionId) { return []; }
    return this.items.filter(node => this.filter === 'all' || (this.filter === 'tools' ? ['TOOL','SUBTOOL'].includes(node.row.kind) : node.row.kind === 'REQUEST'));
  }
  private detail(id: string): void { const command = new Command('trajectory-detail'); command.sessionId = this.state.selectedSessionId; command.itemId = id; this.onAction(command); }
  build() {
    Column({ space: 8 }) {
      if (this.standalone) { PageHeader({ a: this.a, title: this.a.t('轨迹'), onBack: this.onBack }) }
      Row({ space: 8 }) {
        Button(this.a.t('全部')).id('trajectory-all').fontSize(this.a.font(12)).fontColor(this.filter === 'all' ? this.a.accent : this.a.muted).backgroundColor(this.a.surface).height(44).onClick(() => this.filter = 'all')
        Button(this.a.t('工具')).id('trajectory-tools').fontSize(this.a.font(12)).fontColor(this.filter === 'tools' ? this.a.accent : this.a.muted).backgroundColor(this.a.surface).height(44).onClick(() => this.filter = 'tools')
        Button(this.a.t('模型请求')).id('trajectory-requests').fontSize(this.a.font(12)).fontColor(this.filter === 'requests' ? this.a.accent : this.a.muted).backgroundColor(this.a.surface).height(44).onClick(() => this.filter = 'requests')
      }.padding({ left: 16, right: 16 }).width('100%')
      if (!this.nodes().length) { Text(this.a.t('暂无轨迹记录')).fontSize(this.a.font(14)).fontColor(this.a.muted).padding(24) }
      List({ space: 12 }) {
        ForEach(this.nodes(), (node: TrajectoryNodeView) => {
          ListItem() {
            TrajectoryNodeCard({ a: this.a, node, onOpen: id => this.detail(id) })
          }
        }, (node: TrajectoryNodeView) => node.row.id)
      }.padding({ left: 16, right: 16, bottom: 16 }).layoutWeight(1).width('100%')
    }.width('100%').height('100%')
  }
}
