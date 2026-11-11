import { Appearance } from '../core/Appearance';
import { Command, HumanQuestion, QuestionAnswer, QuestionOption } from '../core/Models';
import { ApprovalView, QuestionView } from './ViewModels';

@Component
export struct QuestionCard {
  @ObjectLink a: Appearance;
  @ObjectLink @Watch('refreshQuestions') request: QuestionView;
  @Prop connected: boolean = false;
  onAction: (command: Command) => void = () => {};
  @State private answers: QuestionAnswer[] = [];
  private signature: string = '';

  aboutToAppear(): void { this.refreshQuestions(); }

  private refreshQuestions(): void {
    const signature = JSON.stringify(this.request.questions);
    if (signature === this.signature) { return; }
    this.signature = signature;
    this.answers = this.request.questions.map(question => new QuestionAnswer(question.id));
  }

  private locked(): boolean {
    return !this.connected || this.request.status === 'submitting' || this.request.status === 'accepted';
  }

  private answer(index: number): QuestionAnswer {
    return this.answers[index] ?? new QuestionAnswer(this.request.questions[index].id);
  }

  private update(index: number, label?: string, selected?: boolean, custom?: string): void {
    this.answers = this.answers.map((old: QuestionAnswer, position: number): QuestionAnswer => {
      if (position !== index) { return old; }
      const next = new QuestionAnswer(old.id);
      next.selected = old.selected.slice();
      next.custom = old.custom;
      const multi = this.request.questions[index].multiSelect === true;
      if (custom !== undefined) {
        next.custom = custom;
        if (!multi && custom.trim().length > 0) { next.selected = []; }
      } else if (label !== undefined) {
        next.selected = selected ? (multi ? old.selected.filter(value => value !== label).concat([label]) : [label]) :
          old.selected.filter(value => value !== label);
        if (!multi && selected) { next.custom = ''; }
      }
      return next;
    });
  }

  private submit(cancel: boolean): void {
    const command = new Command(cancel ? 'question-cancel' : 'question-answer');
    command.rpcId = this.request.rpcId;
    command.sessionId = this.request.sessionId;
    command.answers = this.answers;
    this.onAction(command);
  }

  build() {
    Column({ space: 12 }) {
      Text('需要你的回答').fontSize(this.a.font(18)).fontWeight(FontWeight.Medium).fontColor(this.a.fg).width('100%')
      ForEach(this.request.questions, (question: HumanQuestion, index: number) => {
        Column({ space: 10 }) {
          if (question.header) { Text(question.header).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%') }
          Text(question.question).fontSize(this.a.font(16)).fontColor(this.a.fg).width('100%').copyOption(CopyOptions.LocalDevice)
          if (question.detail) { Text(question.detail).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%') }
          if (question.options && question.options.length > 0) {
            Text(question.multiSelect ? '可选择多项，也可补充说明' : '选择一项，或填写自己的答案').fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%')
            ForEach(question.options, (option: QuestionOption, optionIndex: number) => {
              Row({ space: 8 }) {
                Checkbox({ name: option.label, group: this.request.rpcId + question.id })
                  .id('question-option-' + this.request.rpcId + '-' + index + '-' + optionIndex)
                  .select(this.answer(index).selected.includes(option.label)).enabled(!this.locked())
                  .selectedColor('#62D5C6').onChange((selected: boolean) => this.update(index, option.label, selected))
                Column({ space: 4 }) {
                  Text(option.label).fontColor(this.a.fg).fontSize(this.a.font(14)).width('100%')
                  if (option.description) { Text(option.description).fontColor(this.a.muted).fontSize(this.a.font(12)).width('100%') }
                }.layoutWeight(1)
              }.width('100%')
            }, (option: QuestionOption) => option.label)
          }
          TextArea({ placeholder: question.options?.length ? '自定义答案 / 补充说明' : '输入你的回答', text: this.answer(index).custom })
            .id('question-custom-' + this.request.rpcId + '-' + index).height(78).maxLength(20000)
            .backgroundColor(this.a.bg).fontColor(this.a.fg).placeholderColor('#7F9CAF').enabled(!this.locked())
            .onChange((value: string) => this.update(index, undefined, undefined, value))
        }.width('100%')
      }, (question: HumanQuestion) => question.id)
      if (this.request.error) { Text(this.request.error).fontSize(this.a.font(13)).fontColor('#F1B9AC').width('100%') }
      if (this.request.status === 'accepted') {
        Text('网关已接收，等待会话继续…').fontSize(this.a.font(13)).fontColor('#62D5C6').width('100%')
      }
      Row({ space: 10 }) {
        Button('取消回答').id('question-cancel-' + this.request.rpcId).backgroundColor(this.a.bg).fontColor(this.a.muted)
          .enabled(!this.locked()).onClick(() => this.submit(true))
        Button(this.request.status === 'submitting' ? '提交中…' : '提交回答').id('question-submit-' + this.request.rpcId)
          .backgroundColor('#62D5C6').fontColor('#082333').enabled(!this.locked() && this.answers.length > 0 &&
            this.answers.every(answer => answer.selected.length > 0 || answer.custom.trim().length > 0))
          .onClick(() => this.submit(false))
      }.width('100%').justifyContent(FlexAlign.End)
    }.padding(16).width('100%').borderRadius(16).backgroundColor(this.a.surface)
  }
}

@Component
export struct ApprovalCard {
  @ObjectLink a: Appearance;
  @ObjectLink request: ApprovalView;
  @Prop connected: boolean = false;
  onAction: (command: Command) => void = () => {};

  private locked(): boolean {
    return !this.connected || this.request.status === 'submitting' || this.request.status === 'accepted';
  }

  private decide(outcome: string): void {
    const command = new Command('approval-decision');
    command.rpcId = this.request.rpcId;
    command.sessionId = this.request.sessionId;
    command.outcome = outcome;
    this.onAction(command);
  }

  build() {
    Column({ space: 12 }) {
      Text('工具请求审批').fontSize(this.a.font(18)).fontWeight(FontWeight.Medium).fontColor(this.a.fg).width('100%')
      Text(this.request.toolName).fontSize(this.a.font(16)).fontColor(this.a.fg).width('100%').copyOption(CopyOptions.LocalDevice)
      if (this.request.reason) { Text(this.request.reason).fontSize(this.a.font(14)).fontColor(this.a.fg).width('100%') }
      Text(this.request.arguments ?? '网关未提供调用参数，请根据工具名称和原因确认。')
        .fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%').copyOption(CopyOptions.LocalDevice).lineHeight(21)
      if (this.request.error) { Text(this.request.error).fontSize(this.a.font(13)).fontColor('#F1B9AC').width('100%') }
      if (this.request.status === 'accepted') {
        Text(this.request.outcome === 'allowed-once' ? '已允许此次调用，等待会话继续…' : '已拒绝，等待网关确认…')
          .fontSize(this.a.font(13)).fontColor('#62D5C6').width('100%')
      }
      Row({ space: 10 }) {
        Button('拒绝').id('approval-reject-' + this.request.rpcId).backgroundColor('#4B3037').fontColor('#F1B9AC')
          .enabled(!this.locked()).onClick(() => this.decide('rejected'))
        Button(this.request.status === 'submitting' ? '提交中…' : '单次允许').id('approval-allow-' + this.request.rpcId)
          .backgroundColor('#62D5C6').fontColor('#082333').enabled(!this.locked()).onClick(() => this.decide('allowed-once'))
      }.width('100%').justifyContent(FlexAlign.End)
    }.padding(16).width('100%').borderRadius(16).backgroundColor(this.a.surface)
  }
}
