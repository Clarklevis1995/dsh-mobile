import { Appearance, iconTint } from '../core/Appearance';
import { Command, GoalTasksData, TaskEntry, emptyGoalTasks } from '../core/Models';

@Component
export struct GoalDock {
  @ObjectLink a: Appearance;
  @Prop @Watch('changedScope') scope: string = '';
  @Prop @Watch('changedScope') sessionId: string = '';
  @Prop data: GoalTasksData = emptyGoalTasks();
  @Prop connected: boolean = false;
  @State private tasksExpanded: boolean = false;
  private changedScope(): void { this.tasksExpanded = false; }
  onReconnect: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  onEdit: () => void = () => {};
  onClear: () => void = () => {};
  private current(): GoalTasksData { return this.data.sessionId === this.sessionId ? this.data : emptyGoalTasks(); }
  private completed(): number { return this.current().tasks?.filter(row => row.status === 'completed').length ?? 0; }
  private toggle(): void {
    const goal = this.current().goal?.goal; if (!goal) { return; }
    const c = new Command(goal.phase === 'active' ? 'goal-pause' : 'goal-resume'); c.sessionId = this.sessionId; c.itemId = goal.id; c.draftRevision = goal.revision; this.onAction(c);
  }
  private writable(): boolean { const d = this.current(); return this.connected && !d.readOnly && !d.loading && !d.mutation && !d.uncertain; }
  build() {
    Column({ space: 8 }) {
      if (this.current().tasks?.length) {
        Button(this.a.t('任务') + ' · ' + this.completed() + ' / ' + this.current().tasks!.length + (this.tasksExpanded ? ' ⌄' : ' ›')).id('session-tasks-toggle').fontSize(this.a.font(13)).height(44).width('100%').fontColor(this.a.fg).backgroundColor(this.a.surface).onClick(() => this.tasksExpanded = !this.tasksExpanded)
        if (this.tasksExpanded) {
          Scroll() {
            Column({ space: 10 }) {
              ForEach(this.current().tasks!, (task: TaskEntry) => {
                Text((task.status === 'completed' ? '✓ ' : task.status === 'in_progress' ? '◉ ' : '○ ') + task.content).fontSize(this.a.font(14)).fontColor(task.status === 'completed' ? this.a.muted : this.a.fg).width('100%')
              })
            }.width('100%').padding(12)
          }.constraintSize({ maxHeight: 160 }).width('100%')
        }
      }
      if (this.current().goal && this.current().goal!.goal.phase !== 'complete') {
        Row({ space: 4 }) {
          Column({ space: 4 }) {
            Text(this.a.t(!this.connected ? '离线目标快照' : this.current().goal!.goal.phase === 'active' ? '目标进行中' : '目标已暂停')).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%')
            Text(this.current().goal!.goal.objective).id('session-goal-objective').fontSize(this.a.font(15)).fontColor(this.a.fg).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
          }.layoutWeight(1)
          if (!this.current().readOnly) {
            Button() { Image($rawfile('icons/' + (this.current().goal!.goal.phase === 'active' ? 'pause_circle' : 'play_circle') + '.svg')).width(20).height(20).colorFilter(iconTint(this.a.fg)) }.id('session-goal-toggle').accessibilityText(this.a.t(this.current().goal!.goal.phase === 'active' ? '暂停目标' : '继续目标')).width(44).height(44).padding(0).fontColor(this.a.fg).backgroundColor(Color.Transparent).enabled(this.writable()).onClick(() => this.toggle())
            Button() { Image($rawfile('icons/pencil_line.svg')).width(20).height(20).colorFilter(iconTint(this.a.fg)) }.id('session-goal-edit').accessibilityText(this.a.t('编辑目标')).width(44).height(44).padding(0).fontColor(this.a.fg).backgroundColor(Color.Transparent).enabled(this.writable()).onClick(this.onEdit)
            Button() { Image($rawfile('icons/trash.svg')).width(20).height(20).colorFilter(iconTint(this.a.muted)) }.id('session-goal-clear').accessibilityText(this.a.t('删除目标')).width(44).height(44).padding(0).fontColor(this.a.muted).backgroundColor(Color.Transparent).enabled(this.writable()).onClick(this.onClear)
          }
        }.padding(12).width('100%').borderRadius(18).backgroundColor(this.a.surface)
      }
      if (this.current().uncertain) { Button(this.a.t('重新连接以核对目标')).height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(this.onReconnect) }
      if (this.current().error) { Text(this.current().error).id('session-goal-error').fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%') }
    }.width('100%').padding({ left: 12, right: 12 })
  }
}
