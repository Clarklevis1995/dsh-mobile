import { GatewayClient } from './GatewayClient';
import { DraftStore } from './DraftStore';
import { ChatMessage, SharedState, emptyState } from './Models';
import { ApprovalView, MessageView, QuestionView, SessionView } from '../components/ViewModels';

// 控制握手的空快照与重放可能在同一绘制帧内合并；保留有界原对象，ForEach才能继续观察。
function trimInteractionViews<T>(views: Map<string, T>, activeIds: Set<string>): void {
  if (views.size <= 128) { return; }
  for (const id of views.keys()) {
    if (views.size <= 128) { break; }
    if (!activeIds.has(id)) { views.delete(id); }
  }
}

export interface ReadingAnchor { messageId: string; inset: number; }

/** 所有易变状态归属本地主机 ID；相同远端会话 ID 也不会共享视图或草稿。 */
export class HostRuntime {
  readonly client: GatewayClient;
  readonly drafts: DraftStore = new DraftStore();
  state: SharedState = emptyState();
  draft: string = '';
  workspaceId: string = '';
  query: string = '';
  readonly readingAnchors: Map<string, ReadingAnchor> = new Map();
  readonly scrollPositions: Map<string, number> = new Map();
  get scrollY(): number { return this.state.selectedSessionId ? this.scrollPositions.get(this.state.selectedSessionId) ?? -1 : -1; }
  set scrollY(value: number) { if (this.state.selectedSessionId) { this.scrollPositions.delete(this.state.selectedSessionId); this.scrollPositions.set(this.state.selectedSessionId, value); if (this.scrollPositions.size > 64) { const oldest = this.scrollPositions.keys().next().value as string; this.scrollPositions.delete(oldest); } } }
  status: string = '尚未连接';
  error: string = '';
  messages: MessageView[] = [];
  questions: QuestionView[] = [];
  approvals: ApprovalView[] = [];
  sessions: SessionView[] = [];
  navigateCreated: boolean = false;
  private messageViews: Map<string, MessageView> = new Map();
  private questionViews: Map<string, QuestionView> = new Map();
  private approvalViews: Map<string, ApprovalView> = new Map();
  private sessionViews: Map<string, SessionView> = new Map();
  readonly id: string;
  constructor(id: string) { this.id = id; this.client = new GatewayClient(id); }

  update(state: SharedState, messages: ChatMessage[], status: string, error: string): boolean {
    const changed = this.state.selectedSessionId !== state.selectedSessionId;
    if (changed) {
      this.draft = this.drafts.switchSession(this.state.selectedSessionId, state.selectedSessionId, this.draft);
      this.messageViews.clear();
    }
    this.state = state; this.status = status; this.error = error;
    const messageIds = new Set(messages.map(row => row.id));
    this.messageViews.forEach((_view: MessageView, id: string) => { if (!messageIds.has(id)) { this.messageViews.delete(id); } });
    this.messages = messages.map(row => {
      let view = this.messageViews.get(row.id);
      if (view) { view.update(row); } else { view = new MessageView(row); this.messageViews.set(row.id, view); }
      return view;
    });
    this.questions = state.questions.map(row => {
      let view = this.questionViews.get(row.rpcId);
      if (view) { view.update(row); } else { view = new QuestionView(row); this.questionViews.set(row.rpcId, view); }
      return view;
    });
    trimInteractionViews(this.questionViews, new Set(state.questions.map(row => row.rpcId)));
    this.approvals = state.approvals.map(row => {
      let view = this.approvalViews.get(row.rpcId);
      if (view) { view.update(row); } else { view = new ApprovalView(row); this.approvalViews.set(row.rpcId, view); }
      return view;
    });
    trimInteractionViews(this.approvalViews, new Set(state.approvals.map(row => row.rpcId)));
    this.sessions = state.sessions.map(row => {
      let view = this.sessionViews.get(row.id);
      if (view) { view.update(row); } else { view = new SessionView(row); this.sessionViews.set(row.id, view); }
      return view;
    });
    this.sessionViews.forEach((_view: SessionView, id: string) => { if (!state.sessions.some(row => row.id === id)) { this.sessionViews.delete(id); } });
    return changed;
  }
}
