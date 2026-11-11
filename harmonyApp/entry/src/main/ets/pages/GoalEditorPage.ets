import { Appearance } from '../core/Appearance';
import { Command, GoalTasksData, emptyGoalTasks } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct GoalEditorPage {
  @ObjectLink a: Appearance;
  @Prop sessionId: string = '';
  @Prop goalId: string = '';
  @Prop revision: number = 0;
  @Prop objective: string = '';
  @Prop @Watch('updated') data: GoalTasksData = emptyGoalTasks();
  @Prop connected: boolean = false;
  @State private text: string = '';
  @State private saving: boolean = false;
  onBack: () => void = () => {};
  onSaved: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  aboutToAppear(): void { this.text = this.objective; }
  private updated(): void {
    if (!this.saving || this.data.sessionId !== this.sessionId) { return; }
    if (this.data.error || this.data.uncertain) { this.saving = false; }
    else if (!this.data.loading && !this.data.mutation && this.data.goal?.goal.objective === this.text.trim()) { this.saving = false; this.onSaved(); }
  }
  private save(): void {
    if (this.text.trim() === this.objective) { this.onSaved(); return; }
    this.saving = true; const command = new Command('goal-edit'); command.sessionId = this.sessionId; command.itemId = this.goalId; command.draftRevision = this.revision; command.text = this.text; this.onAction(command);
  }
  build() {
    Column({ space: 16 }) {
      PageHeader({ a: this.a, title: this.a.t('编辑目标'), onBack: this.onBack })
      TextArea({ text: this.text, placeholder: this.a.t('描述目标') }).id('goal-editor-objective').maxLength(100000).fontSize(this.a.font(16)).fontColor(this.a.fg).backgroundColor(this.a.surface).enabled(!this.saving).height('40%').constraintSize({ minHeight: 160 }).onChange(value => this.text = value)
      Text(this.a.t('保存会校验打开时的目标版本；其他端已更新时不会覆盖新目标。')).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')
      if (this.data.error) { Text(this.data.error).fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%') }
      Button(this.a.t(this.saving ? '正在保存' : '保存')).id('goal-editor-save').height(48).width('100%').enabled(this.connected && !this.saving && !this.data.uncertain && !!this.text.trim()).fontColor('#FFFFFF').backgroundColor(this.a.accent).onClick(() => this.save())
    }.padding({ left: 20, right: 20 }).width('100%').height('100%').backgroundColor(this.a.bg)
  }
}
