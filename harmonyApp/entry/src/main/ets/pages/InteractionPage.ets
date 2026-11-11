import { Appearance } from '../core/Appearance';
import { Command, SharedState } from '../core/Models';
import { ApprovalView, QuestionView } from '../components/ViewModels';
import { ApprovalCard, QuestionCard } from '../components/InteractionCards';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct InteractionPage {
  @ObjectLink a: Appearance;
  @Prop state: SharedState;
  @Link questions: QuestionView[];
  @Link approvals: ApprovalView[];
  onBack: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t('待处理'), onBack: this.onBack })
      Button(this.a.t('返回')).id('interaction-inbox').fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(this.onBack)
      Scroll() {
        Column({ space: 16 }) {
          if (!this.questions.length && !this.approvals.length) { Text('当前没有待处理事项').fontColor(this.a.muted).fontSize(this.a.font(16)).padding(32) }
          ForEach(this.questions, (question: QuestionView) => {
            Text(this.state.sessions.find(row => row.id === question.sessionId)?.title ?? question.sessionId).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')
            QuestionCard({ a: this.a, request: question, connected: this.state.connected, onAction: this.onAction })
          }, (question: QuestionView) => question.rpcId)
          ForEach(this.approvals, (approval: ApprovalView) => {
            Text(this.state.sessions.find(row => row.id === approval.sessionId)?.title ?? approval.sessionId).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')
            ApprovalCard({ a: this.a, request: approval, connected: this.state.connected, onAction: this.onAction })
          }, (approval: ApprovalView) => approval.rpcId)
        }.padding(20).width('100%').constraintSize({ maxWidth: 720 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.bg)
  }
}
