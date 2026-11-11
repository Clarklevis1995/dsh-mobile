import { i18n } from '@kit.LocalizationKit';
import { Appearance } from '../core/Appearance';
import { Command, SharedState, ScheduleData, emptySchedules } from '../core/Models';
import { localScheduleInstant } from '../core/ScheduleDates';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct ScheduleEditorPage {
  @ObjectLink a: Appearance;
  @Prop state: SharedState;
  @Prop @Watch('received') data: ScheduleData = emptySchedules();
  @Prop scope: string = '';
  @State private taskTitle: string = '';
  @State private prompt: string = '';
  @State private timing: string = 'keep';
  @State private clock: string = '';
  @State private selectedYear: number = 2030;
  @State private selectedMonth: number = 0;
  @State private selectedDay: number = 1;
  @State private selectedHour: number = 9;
  @State private selectedMinute: number = 0;
  @State private localError: string = '';
  private token: string = '';
  private alive: boolean = false;
  private closing: boolean = false;
  onBack: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  onSaved: () => void = () => {};
  aboutToAppear(): void { this.alive = true; this.received(); }
  aboutToDisappear(): void { this.alive = false; }
  private received(): void {
    const editor = this.data.editor; if (!editor) { return; }
    if (this.token !== editor.token) {
      this.token = editor.token; this.closing = false; this.taskTitle = editor.task.title; this.prompt = editor.task.prompt; this.clock = editor.task.clock?.substring(0, 5) ?? '09:00'; this.timing = 'keep'; this.localError = '';
      let date = new Date(editor.task.scheduledAt); if (!Number.isFinite(date.getTime())) { date = new Date(Date.now() + 3600000); }
      this.selectedYear = date.getFullYear(); this.selectedMonth = date.getMonth(); this.selectedDay = date.getDate(); this.selectedHour = date.getHours(); this.selectedMinute = date.getMinutes();
    }
    if (editor.completed && !this.closing) { this.closing = true; const scope = this.scope; const token = this.token; setTimeout(() => { if (this.alive && this.scope === scope && this.data.editor?.token === token) { this.onSaved(); } }, 0); }
  }
  private pickerDate(): Date { return new Date(this.selectedYear, this.selectedMonth, this.selectedDay, this.selectedHour, this.selectedMinute); }
  private dateLabel(): string { return new Date(Date.UTC(this.selectedYear, this.selectedMonth, this.selectedDay)).toLocaleDateString(this.a.english ? 'en' : 'zh-CN', { timeZone: 'UTC' }); }
  private timeLabel(): string { return this.selectedHour.toString().padStart(2, '0') + ':' + this.selectedMinute.toString().padStart(2, '0'); }
  private pickDate(): void {
    DatePickerDialog.show({ selected: this.pickerDate(), onAccept: value => { this.selectedYear = value.year ?? this.selectedYear; this.selectedMonth = value.month ?? this.selectedMonth; this.selectedDay = value.day ?? this.selectedDay; } });
  }
  private pickTime(): void {
    TimePickerDialog.show({ selected: this.pickerDate(), useMilitaryTime: true, onAccept: value => { this.selectedHour = value.hour; this.selectedMinute = value.minute; } });
  }
  private save(): void {
    const editor = this.data.editor; if (!editor) { return; }
    const command = new Command('schedule-save'); command.sessionId = editor.task.sessionId; command.itemId = editor.task.id; command.requestKey = editor.token;
    command.title = this.taskTitle; command.text = this.prompt; command.target = this.timing; command.query = this.clock; command.timeZone = i18n.getTimeZone().getID();
    if (this.timing === 'clock' && !/^([01]\d|2[0-3]):[0-5]\d$/.test(this.clock)) { this.localError = this.a.t('时间格式应为 HH:mm'); return; }
    if (this.timing === 'at') {
      const instant = localScheduleInstant(this.selectedYear, this.selectedMonth, this.selectedDay, this.selectedHour, this.selectedMinute);
      if (!instant || Date.parse(instant) <= Date.now()) { this.localError = this.a.t('请选择有效的未来时间'); return; } command.path = instant;
    }
    this.localError = ''; this.onAction(command);
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t('编辑定时任务'), onBack: this.onBack })
      Scroll() {
        Column({ space: 16 }) {
          TextInput({ placeholder: this.a.t('标题'), text: this.taskTitle }).id('schedule-title-input').fontSize(this.a.font(16)).fontColor(this.a.fg).backgroundColor(this.a.surface).height(52).maxLength(120).enabled(!this.data.editor?.pending).onChange(value => this.taskTitle = value)
          TextArea({ placeholder: this.a.t('任务内容'), text: this.prompt }).id('schedule-prompt-input').fontSize(this.a.font(16)).fontColor(this.a.fg).backgroundColor(this.a.surface).height(180).maxLength(100000).enabled(!this.data.editor?.pending).onChange(value => this.prompt = value)
          Button((this.timing === 'keep' ? '✓ ' : '') + this.a.t('保留原规则')).id('schedule-timing-keep').height(48).width('100%').enabled(!this.data.editor?.pending).onClick(() => this.timing = 'keep')
          Text((this.a.t(this.data.editor?.task.kind === 'daily' ? '每天' : this.data.editor?.task.kind === 'weekly' ? '每周' : this.data.editor?.task.kind === 'cron' ? 'Cron' : this.data.editor?.task.kind === 'every' ? '固定间隔' : '单次')) + ' · ' + (this.data.editor?.task.timeZone ?? i18n.getTimeZone().getID())).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')
          if (this.data.editor?.task.kind === 'daily' || this.data.editor?.task.kind === 'weekly') {
            Button((this.timing === 'clock' ? '✓ ' : '') + this.a.t('修改执行时间')).id('schedule-timing-clock').height(48).width('100%').enabled(!this.data.editor?.pending).onClick(() => this.timing = 'clock')
            if (this.timing === 'clock') { TextInput({ placeholder: 'HH:mm', text: this.clock }).id('schedule-clock-input').height(48).maxLength(5).backgroundColor(this.a.surface).fontColor(this.a.fg).onChange(value => this.clock = value) }
          }
          Button((this.timing === 'at' ? '✓ ' : '') + this.a.t('改为指定日期单次执行')).id('schedule-timing-at').height(48).width('100%').enabled(!this.data.editor?.pending).onClick(() => this.timing = 'at')
          if (this.timing === 'at') {
            Text(this.a.t('设备时区') + ' · ' + i18n.getTimeZone().getID()).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')
            Button(this.dateLabel()).id('schedule-date-picker').height(48).width('100%').enabled(!this.data.editor?.pending).onClick(() => this.pickDate())
            Button(this.timeLabel()).id('schedule-time-picker').height(48).width('100%').enabled(!this.data.editor?.pending).onClick(() => this.pickTime())
          }
          if (this.localError || this.data.editor?.error) { Text(this.localError || this.data.editor?.error).id('schedule-editor-error').fontSize(this.a.font(14)).fontColor('#D65D64').width('100%') }
          if (!this.state.connected || !this.data.editor?.eligible) { Text(this.a.t('任务状态待确认，请返回列表刷新')).fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%') }
          Button(this.a.t('保存')).id('schedule-save').height(48).width('100%').enabled(this.state.connected && this.data.editor?.eligible === true && !this.data.editor?.pending && !!this.taskTitle.trim() && !!this.prompt.trim()).onClick(() => this.save())
          if (this.data.editor?.pending) { LoadingProgress().width(26).height(26).color(this.a.accent) }
        }.padding(20).width('100%').constraintSize({ maxWidth: 760 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
