import { AgentNotice } from './NotificationTypes';
import { VoiceData, VoiceEffect, VoiceFrameHeader } from './VoiceTypes';
import { GoalTasksData } from './Models';
import { webSocket } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import { Engine } from 'libwhaleness_bridge.so';
import { Credential, CredentialStore } from './CredentialStore';
import { Command, SharedResult, SharedState, ChatMessage, ConversationPatch, GatewayConnectionInfo, TrajectoryData, ControlData, SessionFeatureData, SlashData, DraftMutation, PluginData, ScheduleData, TeamData, UploadData, UploadReadData, AttachmentData, RawAttachmentData, WorkspaceFilesData, FileIoData, FileCompletionData, CacheExportData, emptyState } from './Models';

export class GatewayClient {
  onNotifications: (notices: AgentNotice[]) => void = () => {};
  voiceDraft: () => string = () => '';
  onVoice: (value: VoiceData) => void = () => {};
  onVoiceEffects: (effects: VoiceEffect[]) => void = () => {};
  onChange: (state: SharedState, messages: ChatMessage[], status: string, error: string) => void = () => {};
  onPrepared: (preserved: boolean) => Promise<void> = async () => {};
  onGoalTasks: (data: GoalTasksData) => void = () => {};
  onCache: (value: CacheExportData) => void = () => {};
  onWorkspaceFiles: (value: WorkspaceFilesData) => void = () => {};
  onFileIo: (value: FileIoData, data?: string) => void = () => {};
  onFileDiscard: (value: string) => void = () => {};
  onFileCompletion: (value: FileCompletionData) => void = () => {};
  onAttachment: (value: AttachmentData, data?: string) => void = () => {};
  onUploads: (value: UploadData) => void = () => {};
  onUploadRead: (value: UploadReadData) => void = () => {};
  onTeam: (value: TeamData) => void = () => {};
  onSchedules: (value: ScheduleData) => void = () => {};
  onPlugins: (value: PluginData) => void = () => {};
  onSlash: (value: SlashData) => void = () => {};
  onDraftMutation: (value: DraftMutation) => void = () => {};
  onFeatures: (value: SessionFeatureData) => void = () => {};
  onRestoredDraft: (session: string, text: string) => void = () => {};
  onControls: (value: ControlData) => void = () => {};
  onTrajectory: (value: TrajectoryData) => void = () => {};
  onSent: (sessionId: string) => void = () => {};
  onAuthenticated: (info: GatewayConnectionInfo) => Promise<void> = async () => {};
  private announced: boolean = false;
  private retainState: boolean = false;
  private engine: Engine = new Engine();
  private secrets: CredentialStore;
  private credential: Credential = new Credential();
  private control?: webSocket.WebSocket;
  private conversation?: webSocket.WebSocket;
  private state: SharedState = emptyState();
  private messages: ChatMessage[] = [];
  private visibleSession: string = '';
  private generation: number = 0;
  private status: string = '尚未连接';
  private error: string = '';
  private queue: Promise<void> = Promise.resolve();
  private heartbeat: number = -1;
  private handshakeTimer: number = -1;
  private requestTimers: Map<string, number> = new Map();
  private foreground: boolean = true;
  private foregroundProbe: number = -1;
  private lastControlFrame: number = 0;
  private lastConversationFrame: number = 0;
  private credentialWrites: Promise<void> = Promise.resolve();
  private pairingAttempt: boolean = false;
  private desiredConnection: boolean = false;
  private reconnectTimer: number = -1;
  private retryCount: number = 0;

  constructor(profileId?: string) { this.secrets = new CredentialStore(profileId); }

  get connected(): boolean { return this.state.connected; }
  get connectionGeneration(): number { return this.generation; }
  get selectedSessionId(): string | undefined { return this.state.selectedSessionId; }
  refreshView(): void { this.emit(); }

  async restore(): Promise<string> {
    await this.credentialWrites;
    const saved = await this.secrets.load();
    return saved?.endpoint ?? '';
  }

  async connect(endpoint: string, pairingCode: string, payload: string, resume: boolean = false, expectedGatewayId?: string, profileEndpoints?: string[]): Promise<void> {
    this.disconnect();
    const generation = this.generation;
    await this.credentialWrites;
    const saved = await this.secrets.load();
    if (generation !== this.generation) { return; }
    const command = new Command('prepare');
    command.endpoint = endpoint.trim();
    command.pairingCode = pairingCode.trim();
    command.input = payload.trim();
    command.expectedGatewayId = command.input.length === 0 && !command.pairingCode ? (expectedGatewayId ?? saved?.gatewayId) : undefined;
    command.keepState = resume && this.retainState;
    const result = this.call(command);
    if (!result.ok || !result.pairing) { throw new Error(result.error ?? '连接参数无效'); }
    const pair = result.pairing;
    this.credential = new Credential();
    this.retainState = true;
    this.credential.endpoint = pair.endpoint;
    this.credential.endpoints = payload.trim() ? (pair.endpoints ?? [pair.endpoint]) : (profileEndpoints?.length ? profileEndpoints : (saved?.endpoints?.length ? saved.endpoints : [pair.endpoint]));
    this.credential.gatewayName = payload.trim() ? (pair.gatewayName || '已连接主机') : (saved?.gatewayName || pair.gatewayName || '已连接主机');
    this.credential.gatewayId = pair.gatewayId;
    this.credential.deviceId = (saved?.endpoint === pair.endpoint || (!!saved?.gatewayId && saved.gatewayId.toLowerCase() === pair.gatewayId?.toLowerCase())) ? saved!.deviceId : util.generateRandomUUID();
    if (!pair.pairingCode && saved && (saved.endpoint === pair.endpoint || (!!saved.gatewayId && saved.gatewayId.toLowerCase() === pair.gatewayId?.toLowerCase()))) { this.credential.token = saved.token; }
    await this.onPrepared(command.keepState);
    if (generation !== this.generation) { return; }
    if (!pair.pairingCode && !this.credential.token) { throw new Error('首次连接需要一次性配对码或完整配对内容'); }
    this.pairingAttempt = pair.pairingCode.length > 0;
    this.desiredConnection = true;
    this.retryCount = 0;
    this.status = '正在连接主机';
    this.error = '';
    this.emit();
    await this.openSocket('control', pair.pairingCode, generation);
  }

  disconnect(): void {
    this.desiredConnection = false;
    this.closeSockets();
    this.call(new Command('disconnect'));
    this.status = '已断开';
    this.emit();
  }

  dispose(): void { this.onChange = () => {}; this.onNotifications = () => {}; this.closeSockets(); this.engine.close(); }

  async forget(): Promise<void> {
    this.disconnect();
    const generation = this.generation;
    const removal = this.credentialWrites.then(() => this.secrets.clear());
    this.credentialWrites = removal.catch(() => {});
    await removal;
    if (generation !== this.generation) { return; }
    this.credential = new Credential();
    this.retainState = false;
    this.engine.close(); this.engine = new Engine();
    this.state = emptyState();
    this.messages = [];
    this.status = '已移除主机凭据';
    this.error = '';
    this.emit();
  }

  async action(command: Command): Promise<void> {
    this.error = '';
    command.nowMillis = Date.now();
    if (command.op.startsWith('voice-')) { command.text = this.voiceDraft(); command.sessionId = this.selectedSessionId; }
    await this.apply(this.call(command), this.generation);
  }

  setForeground(active: boolean): void {
    this.foreground = active;
    clearTimeout(this.foregroundProbe);
    if (!active || !this.desiredConnection) { return; }
    if (!this.state.connected) {
      if (this.reconnectTimer >= 0) {
        clearTimeout(this.reconnectTimer);
        this.reconnectTimer = -1;
        const generation = this.generation;
        this.openSocket('control', '', generation).catch(() => {});
      }
      return;
    }
    const generation = this.generation;
    const began = Date.now();
    this.lastControlFrame = began;
    this.lastConversationFrame = began;
    this.action(new Command('resume')).catch(() => {});
    this.foregroundProbe = setTimeout(() => {
      if (generation === this.generation && (this.lastControlFrame <= began ||
        (this.conversation !== undefined && this.lastConversationFrame <= began))) { this.failed(generation); }
    }, 8000);
  }

  private reconcileTimeouts(): void {
    const pending = this.state.pendingOperations.concat(
      this.state.questions.filter(row => row.status === 'submitting').map(row => 'question:' + row.rpcId),
      this.state.approvals.filter(row => row.status === 'submitting').map(row => 'approval:' + row.rpcId));
    this.requestTimers.forEach((timer: number, key: string): void => {
      if (!pending.includes(key)) { clearTimeout(timer); this.requestTimers.delete(key); }
    });
    pending.forEach((key: string): void => {
      if (this.requestTimers.has(key)) { return; }
      const generation = this.generation;
      this.requestTimers.set(key, setTimeout((): void => {
        this.requestTimers.delete(key);
        if (generation !== this.generation) { return; }
        const command = new Command(key.startsWith('question:') || key.startsWith('approval:') ? 'request-failed' : 'operation-timeout');
        command.requestKey = key;
        this.apply(this.call(command), generation).catch(() => {});
      }, this.state.operationTimeouts?.[key] ?? 20000));
    });
  }

  private call(command: Command): SharedResult {
    const result = JSON.parse(this.engine.dispatch(JSON.stringify(command))) as SharedResult;
    if (result.state) { this.state = result.state; }
    if (this.visibleSession !== (this.state.selectedSessionId ?? '')) {
      this.messages = [];
      this.visibleSession = this.state.selectedSessionId ?? '';
    }
    result.patches.forEach((patch: ConversationPatch) => this.applyPatch(patch));
    if (result.notifications?.length) { this.onNotifications(result.notifications); }
    if (result.voice) { this.onVoice(result.voice); this.onVoiceEffects(result.voiceEffects ?? []); }
    if (result.goalTasks) { this.onGoalTasks(result.goalTasks); }
    if (result.cache) { this.onCache(result.cache); }
    if (result.workspaceFiles) { this.onWorkspaceFiles(result.workspaceFiles); }
    if (result.fileDiscard) { this.onFileDiscard(result.fileDiscard); }
    if (result.fileIo) { this.onFileIo(result.fileIo, command.op === 'frame' ? (JSON.parse(command.frame) as RawAttachmentData).data : undefined); }
    if (result.fileCompletion) { this.onFileCompletion(result.fileCompletion); }
    if (result.attachment) {
      let data: string | undefined = undefined;
      if (result.attachment.status === 'ready' && command.op === 'frame') { data = (JSON.parse(command.frame) as RawAttachmentData).data; }
      this.onAttachment(result.attachment, data);
    }
    if (result.uploads) { this.onUploads(result.uploads); }
    if (result.uploadRead) { this.onUploadRead(result.uploadRead); }
    if (result.team) { this.onTeam(result.team); }
    if (result.schedules) { this.onSchedules(result.schedules); }
    if (result.plugins) { this.onPlugins(result.plugins); }
    if (result.slash && result.slash.sessionId === this.state.selectedSessionId) { this.onSlash(result.slash); }
    if (result.draftMutation) { this.onDraftMutation(result.draftMutation); }
    if (result.features && result.features.sessionId === this.state.selectedSessionId) { this.onFeatures(result.features); }
    if (result.restoredDrafts) { Object.keys(result.restoredDrafts).forEach(session => this.onRestoredDraft(session, result.restoredDrafts![session])); }
    if (result.controls) { this.onControls(result.controls); }
    if (result.trajectory && result.trajectory.sessionId === this.state.selectedSessionId) { this.onTrajectory(result.trajectory); }
    return result;
  }

  private async transmit(result: SharedResult, generation: number): Promise<void> {
    for (let index = 0; index < result.outbound.length; index++) {
      if (generation !== this.generation) { return; }
      const item = result.outbound[index];
      const socket = item.channel === 'conversation' ? this.conversation : this.control;
      try {
        if (!socket || !await socket.send(item.text)) { throw new Error('send-failed'); }
      } catch (_) {
        this.failed(generation);
        throw new Error('连接中断，发送结果尚未确认，请核对会话后再操作');
      }
      if (generation !== this.generation) { return; }
    }
  }

  private applyPatch(patch: ConversationPatch): void {
    if (patch.sessionId !== this.state.selectedSessionId) { return; }
    if (patch.replacesAll) { this.messages = patch.replacementItems ?? []; return; }
    patch.operations.forEach(operation => {
      const index = this.messages.findIndex(row => row.id === operation.itemId);
      if (operation.kind === 'insert' && operation.item) {
        this.messages = this.messages.filter(row => row.id !== operation.item?.id).concat([operation.item]);
      } else if (operation.kind === 'replace' && operation.item && index >= 0) {
        this.messages[index] = operation.item;
      } else if (operation.kind === 'remove') {
        this.messages = this.messages.filter(row => row.id !== operation.itemId);
      } else if (operation.kind === 'append-text' && index >= 0) {
        const old = this.messages[index];
        const updated = new ChatMessage();
        updated.id = old.id;
        updated.kind = old.kind;
        updated.title = old.title;
        updated.text = old.text + (operation.delta ?? '');
        updated.epochSeconds = operation.epochSeconds ?? old.epochSeconds;
        updated.isError = old.isError; updated.images = old.images; updated.files = old.files; updated.knownFiles = old.knownFiles;
        this.messages[index] = updated;
      }
    });
    this.messages = this.messages.slice();
  }

  private async apply(result: SharedResult, generation: number): Promise<void> {
    if (generation !== this.generation) { return; }
    this.reconcileTimeouts();
    if (result.error) { this.error = result.error; }
    if (!result.ok) {
      this.error = result.error ?? '操作失败';
      if (result.fatal) {
        this.desiredConnection = false;
        this.closeSockets();
        this.call(new Command('disconnect'));
        this.status = '连接校验失败';
      }
      this.emit();
      // 非致命错误仍须执行共享层产生的状态重查；不会重发失败的消息或配置写入。
      if (!result.fatal && this.state.connected) { await this.transmit(result, generation); }
      throw new Error(result.error ?? '操作失败');
    }
    if (result.voice) { await this.transmit(result, generation); return; }
    if (result.token) {
      this.credential.token = result.token;
      this.credential.gatewayId = result.gatewayId;
      const credential = this.credential;
      const saving = this.credentialWrites.then(async (): Promise<void> => {
        if (generation === this.generation) { await this.secrets.save(credential); }
      });
      this.credentialWrites = saving.catch(() => {});
      try { await saving; } catch (_) {
        if (generation !== this.generation) { return; }
        this.desiredConnection = false;
        this.closeSockets();
        this.error = '配对成功，但安全保存凭据失败，请重新配对';
        this.emit();
        return;
      }
      if (generation !== this.generation) { return; }
      this.pairingAttempt = false;
    }
    if (result.openConversation) { await this.openSocket('conversation', '', generation); }
    await this.transmit(result, generation);
    if (generation !== this.generation) { return; }
    if (result.sent && result.sentSessionId) { this.onSent(result.sentSessionId); }
    if (this.state.connected) {
      this.credential.gatewayId = result.gatewayId ?? this.credential.gatewayId;
      if (!this.announced) {
        this.announced = true;
        const credential = this.credential;
        const saving = this.credentialWrites.then(() => this.secrets.save(credential));
        this.credentialWrites = saving.catch(() => {});
        try {
          await saving;
          if (generation !== this.generation) { return; }
          await this.onAuthenticated({ endpoint: credential.endpoint, endpoints: credential.endpoints,
            gatewayId: credential.gatewayId, gatewayName: credential.gatewayName });
        } catch (_) {
          this.desiredConnection = false; this.closeSockets(); this.call(new Command('disconnect'));
          this.status = '保存主机资料失败'; this.error = '请检查安全存储与本地存储后重试'; this.emit(); return;
        }
        if (generation !== this.generation) { return; }
      }
      this.status = '已连接';
      this.retryCount = 0;
      clearTimeout(this.handshakeTimer);
      if (this.heartbeat < 0) {
        this.lastControlFrame = Date.now();
        this.lastConversationFrame = Date.now();
        this.heartbeat = setInterval(() => {
          if (!this.foreground) { return; }
          if (Date.now() - this.lastControlFrame > 60000 ||
            (this.conversation !== undefined && Date.now() - this.lastConversationFrame > 60000)) {
            this.failed(generation);
            return;
          }
          const ping = '{"type":"ping"}';
          this.control?.send(ping).then((sent: boolean): void => { if (!sent) { this.failed(generation); } }).catch(() => this.failed(generation));
          this.conversation?.send(ping).then((sent: boolean): void => { if (!sent) { this.failed(generation); } }).catch(() => this.failed(generation));
        }, 20000);
      }
    }
    this.emit();
  }

  private async openSocket(channel: string, pairingCode: string, generation: number): Promise<void> {
    if (generation !== this.generation) { return; }
    const socket = webSocket.createWebSocket();
    if (channel === 'control') { this.control = socket; } else { this.conversation = socket; }
    socket.on('message', (_error, value: string | ArrayBuffer) => {
      if (generation !== this.generation || typeof value !== 'string') { return; }
      if (channel === 'control') { this.lastControlFrame = Date.now(); } else { this.lastConversationFrame = Date.now(); }
      this.queue = this.queue.then(async () => {
        if (generation !== this.generation) { return; }
        const command = new Command('frame');
        command.frame = value as string;
        command.channel = channel;
        try {
          const header = JSON.parse(command.frame) as VoiceFrameHeader;
          if ((typeof header.kind === 'string' && header.kind.startsWith('speech-')) ||
            (header.kind === 'error' && typeof header.requestType === 'string' && header.requestType.startsWith('speech-'))) {
            command.op = 'voice-frame'; command.text = this.voiceDraft(); command.sessionId = this.selectedSessionId;
          }
        } catch (_) {}
        await this.apply(this.call(command), generation);
      }).catch(() => {
        if (generation === this.generation) { this.emit(); }
      });
    });
    socket.on('close', () => this.failed(generation));
    socket.on('error', () => this.failed(generation));
    const headers: Record<string, string> = {
      'X-DSH-Device-ID': this.credential.deviceId,
      'X-DSH-Device-Name': 'V2hhbGVuZXNzIEhhcm1vbnlPUw==',
      'X-DSH-Channel': channel
    };
    if (this.credential.token) { headers['Authorization'] = 'Bearer ' + this.credential.token; }
    const protocol = pairingCode ? 'dsh-mobile-v1, dsh-pair.' + pairingCode : 'dsh-mobile-v1';
    if (channel === 'control') { this.startHandshakeTimeout(generation); }
    try {
      await socket.connect(this.credential.endpoint, { header: headers, protocol: protocol });
      if (generation !== this.generation) { socket.close().catch(() => {}); }
    } catch (_) {
      this.failed(generation);
      throw new Error('无法连接主机，请确认地址、网络和网关状态');
    }
  }

  private startHandshakeTimeout(generation: number): void {
    clearTimeout(this.handshakeTimer);
    this.handshakeTimer = setTimeout(() => {
      if (generation === this.generation && !this.state.connected) { this.failed(generation); }
    }, 15000);
  }

  private failed(generation: number): void {
    if (generation !== this.generation) { return; }
    const reconnect = this.desiredConnection && !this.pairingAttempt && this.credential.token.length > 0;
    this.closeSockets();
    this.call(new Command('disconnect'));
    this.status = reconnect ? '连接中断，正在重连' : '连接已中断';
    this.error = this.pairingAttempt ? '配对未完成，请检查配对码后手动重试' : '';
    this.emit();
    if (reconnect) {
      const next = this.generation;
      const delay = Math.min(30000, 1000 * Math.pow(2, this.retryCount++));
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = -1;
        if (next !== this.generation || !this.desiredConnection) { return; }
        const endpoints = this.credential.endpoints;
        if (endpoints.length > 1 && this.credential.gatewayId) {
          const index = endpoints.indexOf(this.credential.endpoint);
          const endpoint = endpoints[(index + 1) % endpoints.length];
          const command = new Command('prepare'); command.endpoint = endpoint;
          command.expectedGatewayId = this.credential.gatewayId; command.keepState = true;
          const result = this.call(command);
          if (!result.ok) { this.desiredConnection = false; this.error = result.error ?? '主机身份无效'; this.emit(); return; }
          this.credential.endpoint = endpoint;
        }
        this.openSocket('control', '', next).catch(() => {});
      }, delay);
    }
  }

  private closeSockets(): void {
    this.generation++;
    this.announced = false;
    clearInterval(this.heartbeat);
    this.heartbeat = -1;
    clearTimeout(this.handshakeTimer);
    this.requestTimers.forEach((timer: number): void => clearTimeout(timer));
    this.requestTimers.clear();
    clearTimeout(this.foregroundProbe);
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = -1;
    const control = this.control;
    const conversation = this.conversation;
    this.control = undefined;
    this.conversation = undefined;
    control?.off('message'); control?.off('close'); control?.off('error');
    conversation?.off('message'); conversation?.off('close'); conversation?.off('error');
    control?.close().catch(() => {});
    conversation?.close().catch(() => {});
  }

  private emit(): void { this.onChange(this.state, this.messages.slice(), this.status, this.error); }
}
