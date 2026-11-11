import { AgentNotice } from './NotificationTypes';
import { VoiceData, VoiceEffect } from './VoiceTypes';
export interface SessionRow {
  id: string;
  title: string;
  running: boolean;
  cwd?: string;
  lastActivityEpochSeconds?: number;
  hasConversation?: boolean;
  unread?: boolean;
}

export interface WorkspaceRow {
  workspaceId: string;
  title: string;
  path: string;
  sessionIds: string[];
}

export interface SharedState {
  connected: boolean;
  pendingRequest: boolean;
  pendingMessageSessionId?: string;
  pendingOperations: string[];
  questions: QuestionRequest[];
  approvals: ApprovalRequest[];
  sessions: SessionRow[];
  workspaces: WorkspaceRow[];
  selectedSessionId?: string;
  hasMore: boolean;
  loadingHistory: boolean;
  pendingQuestionCount: number;
  pendingApprovalCount: number;
  archivedSessions: SessionRow[];
  capabilities: string[];
  directory: DirectoryState;
  directoryLoading: boolean;
  catalogBusy: string[];
  searchQuery: string;
  searchSessionIds: string[];
  searching: boolean;
  catalogLoading: boolean;
  generating: boolean;
  historyRevision: number;
  catalogRevision?: number;
  cachedHistory: boolean;
  childConversation: boolean;
  canSend: boolean;
  operationTimeouts?: Record<string, number>;
}

export function emptyState(): SharedState {
  return { connected: false, pendingRequest: false, pendingOperations: [], questions: [], approvals: [], sessions: [], workspaces: [], hasMore: false,
    loadingHistory: false, pendingQuestionCount: 0, pendingApprovalCount: 0,
    archivedSessions: [], capabilities: [], directory: { path: '', home: '', crumbs: [], entries: [] },
    directoryLoading: false, catalogBusy: [], searchQuery: '', searchSessionIds: [], searching: false, catalogLoading: false, generating: false, historyRevision: 0, cachedHistory: false, childConversation: false, canSend: true };
}

export class ChatMessage {
  id: string = '';
  kind: string = '';
  title: string = '';
  text: string = '';
  images: GatewayImageData[] = [];
  files: PresentedFileData[] = [];
  knownFiles: PresentedFileData[] = [];
  isError: boolean = false;
  epochSeconds: number = 0;
}

export interface ProjectionOperation {
  kind: string;
  item?: ChatMessage;
  itemId?: string;
  delta?: string;
  epochSeconds?: number;
}

export interface ConversationPatch {
  sessionId: string;
  replacesAll: boolean;
  replacementItems?: ChatMessage[];
  operations: ProjectionOperation[];
}

export interface Outbound {
  channel: string;
  text: string;
  requestKey?: string;
}

export interface Pairing {
  endpoint: string;
  pairingCode: string;
  gatewayId?: string;
  endpoints?: string[];
  gatewayName?: string;
}

export interface SharedResult {
  voice?: VoiceData;
  notifications?: AgentNotice[];
  voiceEffects?: VoiceEffect[];
  goalTasks?: GoalTasksData;
  ok: boolean;
  state?: SharedState;
  patches: ConversationPatch[];
  outbound: Outbound[];
  pairing?: Pairing;
  token?: string;
  gatewayId?: string;
  openConversation: boolean;
  sent: boolean;
  sentSessionId?: string;
  fatal: boolean;
  error?: string;
  profile?: GatewayProfileRecord;
  trajectory?: TrajectoryData;
  controls?: ControlData;
  features?: SessionFeatureData;
  slash?: SlashData;
  plugins?: PluginData;
  schedules?: ScheduleData;
  team?: TeamData;
  uploads?: UploadData;
  uploadRead?: UploadReadData;
  attachment?: AttachmentData;
  cache?: CacheExportData;
  workspaceFiles?: WorkspaceFilesData;
  fileIo?: FileIoData;
  fileDiscard?: string;
  fileCompletion?: FileCompletionData;
  draftMutation?: DraftMutation;
  restoredDrafts?: Record<string, string>;
}

export class Command {
  imageWidth: number = 0;
  imageHeight: number = 0;
  fileName: string = '';
  byteSize: number = 0;
  sha256: string = '';
  byteCount: number = 0;
  op: string;
  frame: string = '';
  channel: string = 'control';
  input: string = '';
  endpoint: string = '';
  pairingCode: string = '';
  expectedGatewayId?: string;
  sessionId?: string;
  workspaceId?: string;
  text: string = '';
  mode: string = 'queue';
  draftRevision: number = 0;
  locale?: string;
  timeZone: string = 'Asia/Shanghai';
  rpcId: string = '';
  answers: QuestionAnswer[] = [];
  outcome: string = '';
  requestKey: string = '';
  path?: string;
  query: string = '';
  title: string = '';
  provider?: string;
  model?: string;
  reasoningEffort?: string;
  target: string = '';
  value: string = '';
  itemId?: string;
  keepState: boolean = false;
  profile?: GatewayProfileRecord;
  nowMillis: number = Date.now();
  constructor(op: string) { this.op = op; }
}

export interface QuestionOption {
  label: string;
  description?: string;
}

export interface HumanQuestion {
  id: string;
  header?: string;
  question: string;
  detail?: string;
  options?: QuestionOption[];
  multiSelect?: boolean;
}

export interface QuestionRequest {
  rpcId: string;
  sessionId: string;
  questions: HumanQuestion[];
  status: string;
  error?: string;
}

export interface ApprovalRequest {
  rpcId: string;
  sessionId: string;
  toolName: string;
  reason?: string;
  arguments?: string;
  status: string;
  outcome?: string;
  error?: string;
}

export class QuestionAnswer {
  id: string;
  selected: string[] = [];
  custom: string = '';
  constructor(id: string) { this.id = id; }
}

export interface DirectoryEntry { name: string; path: string; hidden: boolean; kind?: string; }
export interface DirectoryState { path: string; home: string; crumbs: DirectoryEntry[]; entries: DirectoryEntry[]; }

export class GatewayProfileRecord {
  localId: string = '';
  gatewayId?: string;
  gatewayName: string = '';
  alias?: string;
  endpoints: string[] = [];
  preferredEndpoint?: string;
  lastConnectedAt?: number;
  server: boolean = false;
}

export interface GatewayConnectionInfo {
  endpoint: string;
  endpoints: string[];
  gatewayId?: string;
  gatewayName: string;
}

export class AppearanceData {
  style: string = 'system';
  language: string = 'system';
  fontFollowsSystem: boolean = false;
  fontScale: number = 1;
  reduceMotion: boolean = false;
}

export interface TrajectoryNode {
  id: string; kind: string; title: string; subtitle: string; startSequence: number; endSequence: number; duration: number; live: boolean;
  model?: string; provider?: string; inputTokens?: number; outputTokens?: number;
}
export interface TrajectoryData { sessionId: string; nodes: TrajectoryNode[]; detail?: string; detailId?: string; }

export interface ModelSelection { provider: string; model: string; reasoningEffort?: string; }
export interface ReasoningEffort { id: string; name: string; }
export interface ModelItem { id: string; name: string; reasoning?: ModelReasoning; }
export interface ModelReasoning { efforts: ReasoningEffort[]; defaultEffort?: string; }
export interface ModelGroup { id: string; name: string; models: ModelItem[]; }
export interface ModelCatalog { current?: ModelSelection; routable: boolean; groups: ModelGroup[]; }
export interface PermissionOption { value: string; name: string; description?: string; }
export interface SessionPermissions { options?: PermissionOption[]; currentValue?: string; preset?: string; sandbox?: string; approval?: string; }
export interface AgentPreset { id: string; name?: string; description?: string; broken?: boolean; brokenReason?: string; }
export interface ContextPressure { pressureTokens?: number; projectedTokens?: number; contextWindow?: number; }
export interface TokenUsageTotals { inputTokens?: number; outputTokens?: number; cacheReadTokens?: number; cacheWriteTokens?: number; reasoningTokens?: number; }
export interface TokenUsage { uncachedInputTokens?: number; outputTokens?: number; cacheReadTokens?: number; cacheWriteTokens?: number; totals?: TokenUsageTotals; }
export interface ContextSnapshot { asOfSeq?: number; tokenUsage?: TokenUsage; pressure?: ContextPressure; }
export interface SessionStats { turns?: number; steps?: number; llmMs?: number; toolMs?: number; ttftMs?: number; decodeTokens?: number; }
export interface StatsTokenUsage { totals?: TokenUsageTotals; }
export interface SessionStatsSnapshot { asOfSeq?: number; stats?: SessionStats; tokenUsage?: StatsTokenUsage; contextPressure?: ContextPressure; }
export interface ControlMetric { id: string; label: string; value: string; }
export interface ControlData {
  agentPresetsAuthorable?: boolean; agentPresetsHasDocument?: boolean;
  metrics: ControlMetric[];
  sessionId?: string; models?: ModelCatalog; permissions?: SessionPermissions; context?: ContextSnapshot; stats?: SessionStatsSnapshot;
  presets: AgentPreset[]; agentPresetDefault?: string; permissionDefault?: string; permissionDefaultOptions: PermissionOption[];
  defaultModel?: ModelSelection; loading: string[]; error?: string;
}
export function emptyControls(): ControlData { return { metrics: [], presets: [], permissionDefaultOptions: [], loading: [] }; }

export interface AgentState { supported: boolean; preset?: string; locked: boolean; known: boolean; loading: boolean; saving: boolean; canSelect: boolean; blocksSending: boolean; presets: AgentPreset[]; }
export interface QueuedItem { id: string; preview: string; attachmentCount: number; editable: boolean; }
export interface SessionFeatureData { sessionId?: string; agent: AgentState; queueSupported: boolean; queue: QueuedItem[]; pendingQueueItem?: string; pendingQueueSession?: string; queueUncertain: boolean; queueError?: string; error?: string; }
export function emptyFeatures(): SessionFeatureData { return { agent: { supported: false, locked: false, known: false, loading: false, saving: false, canSelect: false, blocksSending: false, presets: [] }, queueSupported: false, queue: [], queueUncertain: false }; }

export interface SlashItem { id: string; name: string; description: string; kind: string; }
export interface SlashGroup { id: string; title: string; items: SlashItem[]; }
export interface SlashOption { id: string; label: string; detail?: string; description?: string; selected?: boolean; }
export interface SlashData { sessionId?: string; supported: boolean; visible: boolean; groups: SlashGroup[]; loading: boolean; optionsTitle?: string; options: SlashOption[]; optionsLoading: boolean; selecting: boolean; hint?: string; executing: boolean; uncertain: boolean; error?: string; }
export interface DraftMutation { sessionId: string; expectedRevision: number; expectedText: string; replacement: string; }
export function emptySlash(): SlashData { return { supported: false, visible: false, groups: [], loading: false, options: [], optionsLoading: false, selecting: false, executing: false, uncertain: false }; }

export interface PluginEntryData { id: string; rowId: string; name: string; description: string; module: string; enabled: boolean; phase?: string; readOnlyReason?: string; }
export interface PluginBundleData { name: string; title: string; description: string; version?: string; enabled: boolean; installed: boolean; official: boolean; gateway: boolean; readOnlyReason?: string; error?: string; entries: PluginEntryData[]; }
export interface OfficialPluginData { id: string; title: string; description: string; namespaces: string[]; }
export interface PluginData { loaded: boolean; loading: boolean; fresh: boolean; managementAvailable: boolean; settingsWritable: boolean; bundles: PluginBundleData[]; officialItems: OfficialPluginData[]; pending?: string; error?: string; notice?: string; }
export function emptyPlugins(): PluginData { return { loaded: false, loading: false, fresh: false, managementAvailable: false, settingsWritable: false, bundles: [], officialItems: [] }; }

export interface ScheduledTaskData { id: string; sessionId: string; title: string; prompt: string; kind: string; status: string; scheduledAt: string; clock?: string; timeZone?: string; expression?: string; weekdays: number[]; lastDelivery?: string; everySeconds?: number; }
export interface ScheduleEditorData { token: string; task: ScheduledTaskData; eligible: boolean; pending: boolean; completed: boolean; error?: string; }
export interface ScheduleData { loaded: boolean; loading: boolean; fresh: boolean; uncertain: boolean; items: ScheduledTaskData[]; pendingTask?: string; pendingSession?: string; editor?: ScheduleEditorData; error?: string; notice?: string; }
export function emptySchedules(): ScheduleData { return { loaded: false, loading: false, fresh: false, uncertain: false, items: [] }; }

export interface TeamMemberData { id: string; name: string; role: string; phase: string; mode: string; parentSessionId?: string; model?: string; detail?: string; running?: boolean; error?: string; canOpen: boolean; avatarIndex: number; }
export interface TeamTaskData { id: string; revision: number; subject: string; description: string; status: string; ownerName?: string; blockedBy: string[]; writeScopes: string[]; ready: boolean; writeScopeWarnings: string[]; }
export interface TeamSnapshotData { leadSessionId: string; members: TeamMemberData[]; tasks: TeamTaskData[]; state: string; error?: string; visible: boolean; isTeam: boolean; }
export interface TeamData { lead: TeamSnapshotData; connected: boolean; }
export function emptyTeam(): TeamData { return { lead: { leadSessionId: '', members: [], tasks: [], state: 'loading', visible: false, isTeam: false }, connected: false }; }

export interface UploadDraftData { id: string; name: string; size: number; sha256: string; status: string; confirmedBytes: number; error?: string; }
export interface PhotoDraftData { id: string; name: string; size: number; width: number; height: number; }
export interface UploadData { sessionId?: string; available: boolean; canSend: boolean; drafts: UploadDraftData[]; photosAvailable: boolean; photos: PhotoDraftData[]; }
export interface UploadReadData { sessionId: string; id: string; offset: number; length: number; token: string; }
export function emptyUploads(): UploadData { return { available: false, canSend: false, drafts: [], photosAvailable: false, photos: [] }; }

export interface GatewayImageData { attachmentId: string; mediaType: string; bytes: number; width: number; height: number; name?: string; }
export interface PresentedFileData { path: string; description?: string; attachmentId?: string; name?: string; bytes?: number; }
export interface AttachmentData { key: string; sessionId: string; image: GatewayImageData; status: string; error?: string; }
export interface ImageAssetData { path: string; thumbnail: string; loading: boolean; error: string; }
export interface RawAttachmentData { data?: string; }

export interface WorkspaceEntryData { name: string; path: string; hidden: boolean; kind?: string; bytes?: number; modifiedAt?: number; mediaType?: string; }
export interface FileIoData { token: string; sessionId: string; transferId: string; operation: string; offset: number; length: number; size: number; }
export interface FileCompletionData { sessionId: string; transferId: string; path: string; name: string; mediaType: string; size: number; sha256: string; }
export interface LocalFileData { hostId: string; transferId: string; sessionId: string; remotePath: string; localPath: string; name: string; mediaType: string; size: number; sha256: string; }
export interface DownloadData { transferId: string; name: string; size: number; receivedBytes: number; }
export interface WorkspaceFilesData { sessionId?: string; available: boolean; path: string; entries: WorkspaceEntryData[]; loading: boolean; busy: boolean; download?: DownloadData; error?: string; }
export function emptyWorkspaceFiles(): WorkspaceFilesData { return { available: false, path: '.', entries: [], loading: false, busy: false }; }

export interface CacheDocumentData { id: string; text: string; }
export interface CacheWindowData { sessionId: string; json: string; documents: CacheDocumentData[]; }
export interface CacheExportData { catalog?: string; windows: CacheWindowData[]; }

export interface GoalDefinition { id: string; revision: number; objective: string; phase: string; maxGoalRounds?: number; }
export interface GoalSnapshotData { goal: GoalDefinition; roundsStarted: number; }
export interface TaskEntry { content: string; status: string; }
export interface GoalTasksData { sessionId?: string; goalSupported: boolean; tasks?: TaskEntry[]; goal?: GoalSnapshotData; loading: boolean; mutation?: string; uncertain: boolean; readOnly: boolean; error?: string; }
export function emptyGoalTasks(): GoalTasksData { return { goalSupported: false, loading: false, uncertain: false, readOnly: false }; }
