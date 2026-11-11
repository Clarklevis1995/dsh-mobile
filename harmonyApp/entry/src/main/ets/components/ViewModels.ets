import { ApprovalRequest, ChatMessage, HumanQuestion, QuestionRequest, SessionRow, GatewayImageData, PresentedFileData } from '../core/Models';

// ForEach 使用稳定 ID 时保留子组件；通过 @Observed + @ObjectLink 传递属性变化。
@Observed
export class QuestionView {
  rpcId: string;
  sessionId: string;
  questions: HumanQuestion[] = [];
  status: string = 'idle';
  error?: string;

  constructor(row: QuestionRequest) {
    this.rpcId = row.rpcId;
    this.sessionId = row.sessionId;
    this.update(row);
  }

  update(row: QuestionRequest): void {
    this.questions = row.questions;
    this.status = row.status;
    this.error = row.error;
  }
}

@Observed
export class ApprovalView {
  rpcId: string;
  sessionId: string;
  toolName: string = '';
  reason?: string;
  reasonEnglish?: string;
  replay: boolean = false;
  arguments?: string;
  status: string = 'idle';
  outcome?: string;
  error?: string;

  constructor(row: ApprovalRequest) {
    this.rpcId = row.rpcId;
    this.sessionId = row.sessionId;
    this.update(row);
  }

  update(row: ApprovalRequest): void {
    this.toolName = row.toolName;
    this.reason = row.reason; this.reasonEnglish = row.reasonEnglish; this.replay = row.replay ?? false;
    this.arguments = row.arguments;
    this.status = row.status;
    this.outcome = row.outcome;
    this.error = row.error;
  }
}

@Observed
export class MessageView {
  expanded: boolean = false;
  expandedCodeIds: string[] = [];
  id: string;
  kind: string = '';
  title: string = '';
  text: string = '';
  isError: boolean = false;
  images: GatewayImageData[] = [];
  files: PresentedFileData[] = [];
  knownFiles: PresentedFileData[] = [];

  constructor(row: ChatMessage) {
    this.id = row.id;
    this.update(row);
  }

  update(row: ChatMessage): void {
    this.kind = row.kind;
    this.title = row.title;
    this.text = row.text;
    this.isError = row.isError; this.images = row.images ?? []; this.files = row.files ?? []; this.knownFiles = row.knownFiles ?? [];
  }
}

@Observed
export class SessionView {
  id: string;
  title: string = '';
  running: boolean = false;
  cwd: string = '';
  hasConversation: boolean = false;
  lastActivityEpochSeconds: number = 0;
  constructor(row: SessionRow) { this.id = row.id; this.update(row); }
  update(row: SessionRow): void {
    this.title = row.title; this.running = row.running; this.cwd = row.cwd ?? '';
    this.hasConversation = row.hasConversation ?? true; this.lastActivityEpochSeconds = row.lastActivityEpochSeconds ?? 0;
  }
}
