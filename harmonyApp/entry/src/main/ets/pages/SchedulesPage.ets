import { Appearance } from '../core/Appearance';
import { Command, SharedState, ScheduleData, ScheduledTaskData, emptySchedules } from '../core/Models';
import { scheduleDate } from '../core/ScheduleDates';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct SchedulesPage {
  @ObjectLink a: Appearance;
  @Prop @Watch('connectionChanged') state: SharedState;
  @Prop data: ScheduleData = emptySchedules();
  @State private query: string = '';
  @State private filter: string = 'all';
  @State private expanded: string[] = [];
  private wasConnected: boolean = false;
  onBack: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  onEdit: (task: ScheduledTaskData) => void = () => {};
  onDelete: (task: ScheduledTaskData) => void = () => {};
  onSession: (id: string) => void = () => {};
  onReconnect: () => void = () => {};
  aboutToAppear(): void { this.wasConnected = this.state.connected; this.refresh(); }
  private connectionChanged(): void { if (this.state.connected && !this.wasConnected) { this.refresh(); } this.wasConnected = this.state.connected; }
  private refresh(): void { this.onAction(new Command('schedules-open')); }
  private taskKey(task: ScheduledTaskData): string { return task.sessionId + ':' + task.id; }
  private canChange(): boolean { return this.state.connected && this.data.fresh && !this.data.loading && !this.data.uncertain && !this.data.pendingTask; }
  private items(): ScheduledTaskData[] { const query = this.query.trim().toLocaleLowerCase(); return this.data.items.filter(task => (this.filter === 'all' || (this.filter === 'active' ? task.status === 'active' : task.status !== 'active')) && (!query || task.title.toLocaleLowerCase().includes(query) || task.prompt.toLocaleLowerCase().includes(query))); }
  private rule(task: ScheduledTaskData): string {
    if (task.kind === 'every') { return this.a.t('每隔') + ' ' + (task.everySeconds ?? 0).toString() + ' ' + this.a.t('秒执行一次'); }
    const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    const weekly = task.kind === 'weekly' ? ' · ' + task.weekdays.map(day => this.a.t(days[day - 1] ?? day.toString())).join('、') : '';
    return this.a.t(task.kind === 'daily' ? '每天' : task.kind === 'weekly' ? '每周' : task.kind === 'cron' ? 'Cron' : '单次') + weekly + (task.clock ? ' · ' + task.clock : task.expression ? ' · ' + task.expression : '') + (task.timeZone ? ' · ' + task.timeZone : '');
  }
  private toggle(task: ScheduledTaskData): void { const key = this.taskKey(task); this.expanded = this.expanded.includes(key) ? this.expanded.filter(value => value !== key) : this.expanded.concat([key]); }
  @Builder private detail(label: string, value: string) { Column({ space: 3 }) { Text(this.a.t(label)).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%'); Text(value).fontSize(this.a.font(14)).fontColor(this.a.fg).width('100%').copyOption(CopyOptions.LocalDevice) }.width('100%') }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t('定时任务'), onBack: this.onBack })
      Row({ space: 8 }) {
        Button(this.a.t('全部')).id('schedule-filter-all').height(44).fontSize(this.a.font(13)).fontColor(this.filter === 'all' ? this.a.accent : this.a.muted).backgroundColor(this.a.surface).onClick(() => this.filter = 'all')
        Button(this.a.t('已开启')).id('schedule-filter-active').height(44).fontSize(this.a.font(13)).fontColor(this.filter === 'active' ? this.a.accent : this.a.muted).backgroundColor(this.a.surface).onClick(() => this.filter = 'active')
        Button(this.a.t('已结束')).id('schedule-filter-ended').height(44).fontSize(this.a.font(13)).fontColor(this.filter === 'ended' ? this.a.accent : this.a.muted).backgroundColor(this.a.surface).onClick(() => this.filter = 'ended')
      }.padding({ left: 16, right: 16 }).width('100%')
      TextInput({ placeholder: this.a.t('搜索定时任务'), text: this.query }).id('schedule-search').fontSize(this.a.font(15)).height(48).fontColor(this.a.fg).backgroundColor(this.a.surface).margin(16).onChange(value => this.query = value)
      if (!this.state.connected) { Text(this.a.t('网关已断开，状态待确认')).fontSize(this.a.font(13)).fontColor(this.a.muted).padding(8) }
      if (this.data.error) { Text(this.data.error).id('schedule-error').fontSize(this.a.font(13)).fontColor('#D65D64').padding(12) }
      if (this.data.notice) { Text(this.data.notice).fontSize(this.a.font(13)).fontColor(this.a.muted).padding(8) }
      if (this.data.uncertain) { Button(this.a.t('重新连接')).height(44).onClick(this.onReconnect) }
      if (this.data.loading) { LoadingProgress().width(26).height(26).color(this.a.accent) }
      Scroll() {
        Column({ space: 16 }) {
          if (this.data.loaded && !this.items().length) { Text(this.a.t('暂无定时任务')).fontSize(this.a.font(15)).fontColor(this.a.muted).padding(24) }
          ForEach(this.items(), (task: ScheduledTaskData) => {
            Column({ space: 12 }) {
              Column({ space: 10 }) {
                Text(this.rule(task)).fontSize(this.a.font(13)).fontColor(this.a.accent).width('100%')
                Text(task.title).fontSize(this.a.font(21)).fontWeight(FontWeight.Medium).fontColor(this.a.fg).width('100%')
                Text(task.prompt).fontSize(this.a.font(16)).fontColor(this.a.muted).maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
              }.id('schedule-open-' + task.id).width('100%').onClick(() => this.onSession(task.sessionId))
              Divider().color(this.a.line)
              Row() {
                Text(task.status === 'active' ? scheduleDate(task.scheduledAt) : this.a.t('已结束')).fontSize(this.a.font(14)).fontColor(this.a.muted).layoutWeight(1)
                Button(this.a.t(this.expanded.includes(this.taskKey(task)) ? '收起' : '详情')).id('schedule-details-' + task.id).fontSize(this.a.font(12)).height(44).fontColor(this.a.accent).backgroundColor(Color.Transparent).onClick(() => this.toggle(task))
              }.width('100%')
              if (this.expanded.includes(this.taskKey(task))) {
                this.detail('执行规则', this.rule(task)); this.detail('下次计划时间', task.status === 'active' ? scheduleDate(task.scheduledAt) : '—')
                this.detail('所属会话', this.state.sessions.find(session => session.id === task.sessionId)?.title ?? task.sessionId)
                this.detail('状态', this.a.t(task.status === 'active' ? '运行中' : '已结束')); this.detail('最近投递', task.lastDelivery ? scheduleDate(task.lastDelivery) : this.a.t('暂无投递记录'))
              }
              Row({ space: 10 }) {
                Button(this.a.t('编辑')).id('schedule-edit-' + task.id).height(44).fontSize(this.a.font(13)).fontColor(this.a.accent).backgroundColor(this.a.bg).enabled(this.canChange() && task.status === 'active').onClick(() => this.onEdit(task))
                Button(this.a.t('删除')).id('schedule-delete-' + task.id).height(44).fontSize(this.a.font(13)).fontColor('#D65D64').backgroundColor(this.a.bg).enabled(this.canChange()).onClick(() => this.onDelete(task))
                if (this.data.pendingTask === task.id && this.data.pendingSession === task.sessionId) { LoadingProgress().width(22).height(22).color(this.a.accent) }
              }.width('100%').justifyContent(FlexAlign.End)
            }.padding(20).width('100%').borderRadius(28).backgroundColor(this.a.surface)
          }, (task: ScheduledTaskData) => JSON.stringify(task))
          Button(this.a.t('刷新')).id('schedules-refresh').height(48).width('100%').enabled(this.state.connected && !this.data.loading && !this.data.uncertain).onClick(() => this.refresh())
        }.padding(16).width('100%').constraintSize({ maxWidth: 760 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
