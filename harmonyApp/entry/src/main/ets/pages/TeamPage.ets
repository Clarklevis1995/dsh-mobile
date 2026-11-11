import { Appearance } from '../core/Appearance';
import { Command, SharedState, TeamData, TeamTaskData, TeamMemberData } from '../core/Models';
import { CircleIcon } from '../components/DesignSystem';

@Component
export struct TeamPage {
  @ObjectLink a: Appearance;
  @Prop state: SharedState;
  @Prop data: TeamData;
  @State private filter: string = 'all';
  @State private expanded: string[] = [];
  onAction: (command: Command) => void = () => {};
  onBack: () => void = () => {};
  onMember: (id: string) => void = () => {};
  aboutToAppear(): void { this.request(false); }
  private request(refresh: boolean): void { const c = new Command(refresh ? 'team-refresh' : 'team-open'); c.sessionId = this.state.selectedSessionId; this.onAction(c); }
  private status(value: string): string {
    if (value === 'completed') { return this.a.t('已完成'); }
    if (value === 'in_progress') { return this.a.t('进行中'); }
    if (value === 'pending') { return this.a.t('待处理'); }
    if (value === 'active') { return this.a.t('活跃'); }
    return value;
  }
  private tasks(): TeamTaskData[] { return this.data.lead.tasks.filter(row => this.filter === 'all' || row.status === this.filter); }
  private toggle(id: string): void { this.expanded = this.expanded.includes(id) ? this.expanded.filter(value => value !== id) : this.expanded.concat(id); }
  build() {
    Column() {
      Row({ space: 12 }) {
        CircleIcon({ icon: 'back_chevron', label: this.a.t('返回'), controlId: 'page-back', ink: this.a.fg, fill: this.a.surface, onPress: this.onBack })
        Text(this.a.t('团队')).fontSize(this.a.font(23)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).layoutWeight(1)
        Button(this.a.t('刷新')).id('team-refresh').height(44).fontSize(this.a.font(13)).fontColor(this.a.accent).backgroundColor(Color.Transparent).enabled(this.state.connected).onClick(() => this.request(true))
      }.width('100%').height(72).padding({ left: 16, right: 16 })
      Scroll() {
        Column({ space: 16 }) {
          if (!this.state.connected) { Text(this.a.t('连接已断开，当前显示上次读取的团队')).fontSize(this.a.font(13)).fontColor(this.a.muted) }
          if (this.data.lead.error) { Text(this.a.t(this.data.lead.error)).id('team-error').fontSize(this.a.font(14)).fontColor('#D65D64') }
          if (!this.data.lead.members.length) {
            Text(this.a.t(this.data.lead.state === 'unsupported' ? '此主机未启用团队能力' : this.data.lead.state === 'loading' ? '正在读取团队' : '当前会话暂无团队成员')).fontSize(this.a.font(15)).fontColor(this.a.muted).padding(24)
          }
          ForEach(this.data.lead.members, (member: TeamMemberData) => {
            Button({ type: ButtonType.Normal }) {
              Row({ space: 14 }) {
                Text(member.name.substring(0, 1)).fontSize(this.a.font(19)).fontWeight(FontWeight.Bold).fontColor(this.a.accent).width(46).height(46).textAlign(TextAlign.Center).borderRadius(23).backgroundColor(this.a.bg)
                Column({ space: 7 }) {
                  Text(member.name).fontSize(this.a.font(16)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).width('100%')
                  Text((member.role === 'lead' ? this.a.t('主智能体') : member.role === 'subagent' ? this.a.t('子智能体') : this.a.t('团队成员')) + ' · ' + this.status(member.phase) + (member.running === true ? ' · ' + this.a.t('运行中') : '')).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%')
                  if (member.detail || member.model) { Text((member.detail ?? '') + (member.detail && member.model ? ' · ' : '') + (member.model ?? '')).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%') }
                  if (member.error) { Text(member.error).fontSize(this.a.font(13)).fontColor('#D65D64').width('100%') }
                }.layoutWeight(1)
                if (member.id !== this.data.lead.leadSessionId && member.canOpen) { Text('›').fontSize(24).fontColor(this.a.muted) }
              }.width('100%').padding(18)
            }.id('team-member-' + member.id).width('100%').borderRadius(24).backgroundColor(this.a.surface)
              .enabled(this.state.connected && member.canOpen && member.id !== this.data.lead.leadSessionId).onClick(() => this.onMember(member.id))
          }, (member: TeamMemberData) => JSON.stringify(member))
          Text(this.a.t('团队任务')).fontSize(this.a.font(19)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).width('100%')
          Scroll() {
            Row({ space: 8 }) {
              ForEach(['all', 'pending', 'in_progress', 'completed'], (value: string) => {
                Button(value === 'all' ? this.a.t('全部') : this.status(value)).id('team-filter-' + value).height(44).fontSize(this.a.font(13)).fontColor(this.filter === value ? this.a.accent : this.a.muted).backgroundColor(this.a.surface).onClick(() => this.filter = value)
              })
            }
          }.scrollable(ScrollDirection.Horizontal).width('100%').scrollBar(BarState.Off)
          if (!this.tasks().length) { Text(this.a.t('暂无任务')).fontSize(this.a.font(14)).fontColor(this.a.muted).padding(24) }
          ForEach(this.tasks(), (task: TeamTaskData) => {
            Column({ space: 12 }) {
              Button(task.subject).id('team-task-' + task.id).fontSize(this.a.font(16)).fontColor(this.a.fg).backgroundColor(Color.Transparent).width('100%').constraintSize({ minHeight: 48 }).onClick(() => this.toggle(task.id))
              Text(this.status(task.status) + (task.ownerName ? ' · ' + task.ownerName : '')).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%')
              if (this.expanded.includes(task.id)) {
                Text(task.description || this.a.t('暂无说明')).fontSize(this.a.font(14)).fontColor(this.a.fg).copyOption(CopyOptions.LocalDevice).width('100%')
                if (task.blockedBy.length) { Text(this.a.t('依赖任务') + ': ' + task.blockedBy.join(', ')).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%') }
                if (task.writeScopes.length) { Text(task.writeScopes.join('\n')).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%') }
                ForEach(task.writeScopeWarnings, (warning: string) => { Text(warning).fontSize(this.a.font(13)).fontColor('#B87C00').width('100%') })
              }
            }.padding(18).width('100%').borderRadius(24).backgroundColor(this.a.surface)
          }, (task: TeamTaskData) => JSON.stringify(task))
        }.padding(16).width('100%').constraintSize({ maxWidth: 760 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.height('100%').width('100%').backgroundColor(this.a.settingsBg)
  }
}
