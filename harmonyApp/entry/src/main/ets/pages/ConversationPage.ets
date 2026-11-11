import { VoiceStateBar, VoiceHoldButton } from '../components/VoiceInput';
import { VoiceData, emptyVoice } from '../core/VoiceTypes';
import { GoalDock } from '../components/GoalDock';
import { GoalTasksData, emptyGoalTasks } from '../core/Models';
import { MessageDataSource } from '../core/MessageDataSource';
import { UploadDock } from '../components/UploadDock';
import { SlashSuggestions } from '../components/SlashSuggestions';
import { QueueDock } from '../components/QueueDock';
import { TrajectoryPage } from './TrajectoryPage';
import { Appearance, iconTint } from '../core/Appearance';
import { Command, SharedState, TrajectoryData, SessionFeatureData, SlashData, UploadData, GatewayImageData, ImageAssetData, PresentedFileData, emptyUploads, emptySlash, emptyFeatures, ControlData, emptyControls } from '../core/Models';
import { ApprovalView, MessageView, QuestionView } from '../components/ViewModels';
import { MessageCard } from '../components/MessageCard';
import { ApprovalCard, QuestionCard } from '../components/InteractionCards';
import { CircleIcon } from '../components/DesignSystem';

@Component
export struct ConversationPage {
  @Prop voice: VoiceData = emptyVoice();
  onVoice: (op: string) => void = () => {};
  onVoiceMode: (enabled: boolean) => void = () => {};
  onVoiceMove: (distance: number) => void = () => {};
  @Prop goalTasks: GoalTasksData = emptyGoalTasks();
  onGoalEdit: () => void = () => {};
  onGoalClear: () => void = () => {};
  @Prop heading: string = '';
  @Prop imageAssets: Record<string, ImageAssetData> = {};
  onLoadImage: (image: GatewayImageData, retry: boolean) => void = () => {};
  onImage: (image: GatewayImageData, images: GatewayImageData[]) => void = () => {};
  onFiles: () => void = () => {};
  onFile: (file: PresentedFileData) => void = () => {};
  @Prop uploads: UploadData = emptyUploads();
  @Prop preparingFiles: boolean = false;
  imagePath: (id: string) => string = () => '';
  onDraftPhoto: (id: string) => void = () => {};
  onChooseAttachments: (images: boolean) => void = () => {};
  @Prop teamVisible: boolean = false;
  @State private paneWidth: number = 360;
  @Prop slash: SlashData = emptySlash();
  @State private sendMode: string = 'queue';
  @State private voiceRequested: boolean = false;
  @Prop features: SessionFeatureData = emptyFeatures();
  @State private trajectoryOpen: boolean = false;
  @Prop trajectory: TrajectoryData = { sessionId: '', nodes: [] };
  @ObjectLink a: Appearance;
  @Prop @Watch('stateChanged') state: SharedState;
  @Link @Watch('changed') messages: MessageView[];
  @Link questions: QuestionView[];
  @Link approvals: ApprovalView[];
  @Prop @Watch('stateChanged') scope: string = '';
  @Prop @Watch('changed') active: boolean = false;
  @Prop draft: string = '';
  @Prop status: string = '';
  @Prop showSidebarButton: boolean = true;
  @Prop savedAnchor: string = '';
  @Prop savedInset: number = 0;
  onSearchHandled: () => void = () => {};
  @Prop @Watch('searchChanged') searchTarget: string = '';
  onAnchor: (scope: string, session: string, message: string, inset: number) => void = () => {};
  private dataSource: MessageDataSource = new MessageDataSource();
  private anchorTarget: string = '';
  private anchorInset: number = 0;
  private anchorTries: number = 0;
  private visibleStart: number = 0;
  @Prop savedScrollY: number = -1;
  @Prop @Watch('jumpToTail') tailRevision: number = 0;
  onLink: (url: string) => void = () => {};
  onPreview: (source: string, table: boolean) => void = () => {};
  onExport: (csv: string) => void = () => {};
  onInbox: () => void = () => {};
  onReconnect: () => void = () => {};
  onTeam: () => void = () => {};
  @Prop controls: ControlData = emptyControls();
  onControls: () => void = () => {};
  onTrajectory: () => void = () => {};
  onBack: () => void = () => {};
  onSidebar: () => void = () => {};
  onDraft: (value: string) => void = () => {};
  onAction: (command: Command) => void = () => {};
  onOffset: (scope: string, sessionId: string, value: number) => void = () => {};
  private scroller: Scroller = new Scroller();
  private followTail: boolean = true;
  @State private unread: boolean = false;
  @State private atTail: boolean = true;
  private historyTimer: number = -1;
  private lastTail: string = '';
  private renderedIds: Set<string> = new Set();
  private timer: number = -1;
  private conversationKey: string = '';
  private contentHeight: number = 0;
  private historyHeight: number = -1;
  private historyOffset: number = 0;
  private restoreY: number = -1;
  private scopeChanged: boolean = false;
  private userScrolling: boolean = false;
  private tailTries: number = 0;

  private hasContent(): boolean { return !!this.draft.trim() || !!this.uploads.drafts.length || !!this.uploads.photos.length; }
  private showsStop(): boolean { return this.state.generating && !this.hasContent() && !this.preparingFiles && this.state.canSend && this.state.capabilities.includes('session-cancel'); }
  private canSubmit(): boolean { return !this.voice.blocked && this.state.canSend && this.state.connected && !!this.state.selectedSessionId && !this.preparingFiles && (!!this.draft.trim() || this.uploads.canSend) && (!this.uploads.drafts.length || this.uploads.canSend) && (this.state.childConversation || !this.currentFeatures().agent.blocksSending) && !this.currentSlash().selecting && !this.currentSlash().executing && !this.currentSlash().uncertain && this.state.pendingMessageSessionId !== this.state.selectedSessionId; }
  private currentControls(): ControlData { return this.controls.sessionId === this.state.selectedSessionId ? this.controls : emptyControls(); }
  private modelTitle(): string { const catalog = this.currentControls().models; const selected = catalog?.current; return catalog?.groups.find(group => group.id === selected?.provider)?.models.find(model => model.id === selected?.model)?.name || selected?.model || this.a.t('模型'); }
  private permissionTitle(): string { const permissions = this.currentControls().permissions; const preset = permissions?.preset || permissions?.currentValue; if (preset === 'read-only') { return this.a.t('只读'); } if (preset === 'workspace-write') { return this.a.t('工作区写入'); } if (preset === 'danger-full-access') { return this.a.t('完全访问'); } return permissions?.options?.find(row => row.value === permissions.currentValue)?.name || this.a.t('默认权限'); }
  private contextPercent(): number { const value = this.currentControls().context?.pressure; return value?.contextWindow ? Math.min(100,Math.max(0,(value.pressureTokens ?? 0)*100/value.contextWindow)) : 0; }
  aboutToAppear(): void { this.stateChanged(); if (this.state.connected && this.state.selectedSessionId && !this.state.childConversation && this.controls.sessionId !== this.state.selectedSessionId) { const command = new Command('controls-open'); command.sessionId = this.state.selectedSessionId; this.onAction(command); } }
  aboutToDisappear(): void { this.captureAnchor(); clearTimeout(this.timer); clearTimeout(this.historyTimer); }
  private currentSlash(): SlashData { return this.slash.sessionId === this.state.selectedSessionId ? this.slash : emptySlash(); }
  @Builder private slashPopup() { SlashSuggestions({ a: this.a, data: this.currentSlash(), sessionId: this.state.selectedSessionId ?? '', onAction: this.onAction, onReconnect: this.onReconnect }) }
  private closeSlash(): void { const command = new Command('slash-dismiss'); command.sessionId = this.state.selectedSessionId; this.onAction(command); }
  private currentFeatures(): SessionFeatureData { return this.features.sessionId === this.state.selectedSessionId ? this.features : emptyFeatures(); }
  private sendMessage(): void { const command = new Command('send'); command.mode = this.state.generating ? this.sendMode : 'queue'; this.onAction(command); this.jumpToTail(); }
  private searchChanged(): void {
    if (this.searchTarget) { this.anchorTarget = this.searchTarget; this.anchorInset = 0; this.anchorTries = 0; this.followTail = false; this.atTail = false; this.changed(); }
  }
  private stateChanged(): void {
    if (!this.state.generating) { this.sendMode = 'queue'; }
    const key = this.scope + ':' + (this.state.selectedSessionId ?? '');
    if (key !== this.conversationKey) {
      this.conversationKey = key; this.voiceRequested = false; this.sendMode = 'queue'; this.renderedIds.clear(); this.unread = false; this.lastTail = '';
      this.anchorTarget = this.searchTarget || this.savedAnchor; this.anchorInset = this.searchTarget ? 0 : this.savedInset; this.anchorTries = 0;
      this.followTail = !this.anchorTarget && this.savedScrollY < 0; this.atTail = this.followTail; this.restoreY = this.anchorTarget ? -1 : this.savedScrollY;
      this.scopeChanged = true;
    }
    this.changed();
  }
  private changed(): void {
    if (this.timer >= 0) { return; }
    this.timer = setTimeout(() => {
      this.timer = -1;
      if (this.dataSource.update(this.messages, this.conversationKey)) { this.changed(); return; }
      if (!this.active || this.trajectoryOpen || this.state.loadingHistory && !this.messages.length) { return; }
      const tail = this.messages[this.messages.length - 1]; const stamp = tail ? tail.id + ':' + tail.text.length : '';
      if (!this.followTail && this.lastTail && stamp !== this.lastTail) { this.unread = true; } if (stamp !== this.lastTail) { this.tailTries = 0; } this.lastTail = stamp;
      if (this.anchorTarget) { this.restoreAnchor(); return; }
      this.scopeChanged = false;
      if (this.restoreY >= 0) { this.scroller.scrollTo({ xOffset: 0, yOffset: this.restoreY, animation: false }); this.restoreY = -1; }
      else if (this.followTail) {
        this.unread = false;
        const pending = this.questions.some(row => row.sessionId === this.state.selectedSessionId) || this.approvals.some(row => row.sessionId === this.state.selectedSessionId);
        this.scroller.scrollToIndex(this.messages.length + (pending ? 1 : 0), false, ScrollAlign.END);
        // 惰性条目的真实高度须在挂载后确认，有限重排不打断用户滚动。
        if (this.tailTries++ < 3) { this.changed(); }
      }
    }, 50);
  }
  private restoreAnchor(): void {
    const index = this.messages.findIndex(message => message.id === this.anchorTarget);
    if (index < 0) {
      if (this.state.loadingHistory) { return; }
      // 搜索只索引磁盘窗口；若网络已经删除该条目，不能假造定位成功。
      if (this.searchTarget && this.anchorTries === 0) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('该内容不在当前历史窗口中，可加载更早消息后查看') }); }
      this.anchorTarget = ''; this.scopeChanged = false; return;
    }
    const rectangle = this.scroller.getItemRect(index + 1);
    if (this.anchorTries === 0 || rectangle.height <= 0) { this.scroller.scrollToIndex(index + 1, false, ScrollAlign.START); }
    else { const delta = rectangle.y - this.anchorInset; if (Math.abs(delta) > 0.5) { this.scroller.scrollBy(0, delta); } }
    this.anchorTries++;
    if (this.anchorTries >= 40 || this.anchorTries >= 2 && this.renderedIds.has(this.anchorTarget) && rectangle.height > 0 && Math.abs(rectangle.y - this.anchorInset) <= 1) {
      this.anchorTarget = ''; this.scopeChanged = false; this.captureAnchor(); if (this.searchTarget) { this.onSearchHandled(); } return;
    }
    this.changed();
  }
  private captureAnchor(): void {
    if (!this.active || this.trajectoryOpen || this.scopeChanged || this.anchorTarget) { return; }
    const index = Math.max(0, Math.min(this.messages.length - 1, this.visibleStart - 1)); const message = this.messages[index];
    if (message) { const rectangle = this.scroller.getItemRect(index + 1); if (rectangle.height > 0) { this.onAnchor(this.scope, this.state.selectedSessionId ?? '', this.followTail ? '' : message.id, rectangle.y); } }
    this.onOffset(this.scope, this.state.selectedSessionId ?? '', this.followTail ? -1 : this.scroller.currentOffset().yOffset);
  }
  private jumpToTail(): void { this.userScrolling = false; this.tailTries = 0; this.anchorTarget = ''; this.scopeChanged = false; this.restoreY = -1; this.unread = false; this.atTail = true; this.followTail = true; this.changed(); }
  private older(): void { this.followTail = false; this.onAction(new Command('older')); }
  private rendered(scope: string, id: string): void {
    if (scope !== this.conversationKey) { return; }
    this.renderedIds.add(id); if (this.renderedIds.size > 64) { const oldest = this.renderedIds.keys().next().value as string; this.renderedIds.delete(oldest); } this.changed();
  }
  @Builder private attachmentMenu() {
    Menu() {
      MenuItem({ content: this.a.t('图片') }).id('attach-images').enabled(this.uploads.photosAvailable && this.uploads.photos.length < 4).onClick(() => this.onChooseAttachments(true))
      MenuItem({ content: this.a.t('文件') }).id('attach-files').enabled(this.uploads.available).onClick(() => this.onChooseAttachments(false))
    }
  }
  private modeTitle(): string { const agent = this.currentFeatures().agent; return agent.presets.find(row => row.id === agent.preset)?.name || this.a.t('标准模式'); }
  @Builder private conversationMenu() {
    Menu() {
      if (!this.state.childConversation) { MenuItem({ content: this.a.t('会话设置') }).id('conversation-controls').onClick(this.onControls) }
      MenuItem({ content: this.a.t('轨迹') }).id('conversation-tab-trajectory').onClick(this.onTrajectory)
      if (this.state.capabilities.includes('file-downloads')) { MenuItem({ content: this.a.t('文件') }).id('conversation-files').onClick(this.onFiles) }
      if (!this.state.childConversation && this.teamVisible) { MenuItem({ content: this.a.t('团队') }).id('conversation-team').onClick(this.onTeam) }
      MenuItem({ content: this.a.t('待处理') }).id('interaction-inbox').onClick(this.onInbox)
    }
  }
  build() {
    Column() {
      Row({ space: 8 }) {
        CircleIcon({ diameter: 46, lifted: true, icon: 'back_chevron', label: this.a.t('返回'), controlId: 'conversation-back', ink: this.a.fg, fill: this.a.surface, onPress: this.onBack })
        Column({ space: 3 }) {
          Text(this.heading || (this.state.sessions.find(row => row.id === this.state.selectedSessionId)?.title ?? this.a.t('会话'))).fontSize(this.a.font(17)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
          Row({ space: 6 }) {
            Circle().width(8).height(8).fill(this.state.connected ? '#2EB85C' : this.a.muted).accessibilityText(this.a.t(this.state.connected ? '已连接' : this.status))
            Text(this.state.connected ? this.modeTitle() : this.a.t(this.status)).fontSize(this.a.font(13)).fontColor(this.a.muted).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }.width('100%').id('conversation-connection-state').accessibilityGroup(true).accessibilityLevel('yes')
            .accessibilityText(this.a.t(this.state.connected ? '已连接' : this.status) + (this.state.connected ? ' · ' + this.modeTitle() : ''))
        }.layoutWeight(1)
        if (this.showSidebarButton) { CircleIcon({ diameter: 46, lifted: true, icon: 'conversation_sidebar', label: this.a.t('会话'), controlId: 'conversation-sidebar', ink: this.a.fg, fill: this.a.surface, onPress: this.onSidebar }) }
        Button({ type: ButtonType.Circle }) {
          Stack() {
            Image($rawfile('icons/more_horizontal.svg')).width(22).height(22).fillColor(this.a.fg)
            if (this.state.pendingQuestionCount + this.state.pendingApprovalCount > 0) { Text((this.state.pendingQuestionCount + this.state.pendingApprovalCount).toString()).id('conversation-pending-badge').accessibilityText(this.a.t('待处理')).fontSize(10).fontColor('#FFFFFF').width(16).height(16).textAlign(TextAlign.Center).borderRadius(8).backgroundColor('#D84B53').position({ x: 18, y: -4 }) }
          }.width(24).height(24)
        }
          .id('conversation-overflow').accessibilityText(this.a.t('更多') + (this.state.pendingQuestionCount + this.state.pendingApprovalCount > 0 ? ' · '+this.a.t('待处理')+' '+(this.state.pendingQuestionCount + this.state.pendingApprovalCount) : '')).width(46).height(46).padding(0).backgroundColor(this.a.surface).shadow({ radius: 12, color: '#13000000', offsetY: 4 }).border({ width: .7, color: this.a.dark ? '#22F1F4FA' : '#0E0E131A', radius: 23 }).bindMenu(this.conversationMenu)
      }.height(78).padding({ left: 12, right: 12, top: 14 }).width('100%')
      if (this.state.childConversation && !this.state.canSend) { Text(this.a.t('此子智能体会话仅支持阅读')).id('child-read-only').fontSize(this.a.font(13)).fontColor(this.a.muted).padding(12) }
      if (this.state.cachedHistory || !this.state.connected) { Text(this.a.t(this.state.connected ? '正在同步，先显示缓存内容' : this.state.cachedHistory ? '离线缓存，部分历史可能尚未缓存' : '离线，当前会话尚无可用历史缓存')).id('cached-history-notice').fontSize(this.a.font(12)).fontColor(this.a.muted).padding(10) }
      Stack() {
      List({ space: 0, scroller: this.scroller }) {
        ListItem() {
          Column() {
            if (this.state.hasMore) {
              Button(this.a.t(!this.state.connected && this.state.cachedHistory ? '连接后加载更早消息' : this.state.loadingHistory ? '历史加载中' : '更早消息')).id('history-older').height(44).fontSize(this.a.font(13))
                .fontColor(this.a.accent).backgroundColor(this.a.surface).enabled(this.state.connected && !this.state.loadingHistory).onClick(() => this.older())
            }
            if (this.state.loadingHistory && !this.messages.length) { LoadingProgress().width(32).height(32).color(this.a.accent) }
          }.width('100%').padding({ bottom: this.state.hasMore || this.state.loadingHistory && !this.messages.length ? 16 : 0 })
        }
        LazyForEach(this.dataSource, (message: MessageView) => {
          ListItem() { MessageCard({ imageAssets: this.imageAssets, onLoadImage: this.onLoadImage, onImage: this.onImage, onFile: this.onFile, message: message, a: this.a, active: this.active && !this.trajectoryOpen, renderScope: this.conversationKey, onRendered: (scope: string, id: string) => this.rendered(scope, id), onLink: this.onLink, onPreview: this.onPreview, onExport: this.onExport }) }.padding({ bottom: 16 }).width('100%')
        }, (message: MessageView) => this.conversationKey + ':' + message.id)
        ListItem() { Column({ space: 16 }) {
          ForEach(this.questions.filter(row => row.sessionId === this.state.selectedSessionId), (question: QuestionView) => {
            QuestionCard({ a: this.a, request: question, connected: this.state.connected, onAction: this.onAction })
          }, (question: QuestionView) => this.scope + ':' + question.rpcId)
          ForEach(this.approvals.filter(row => row.sessionId === this.state.selectedSessionId), (approval: ApprovalView) => {
            ApprovalCard({ a: this.a, request: approval, connected: this.state.connected, onAction: this.onAction })
          }, (approval: ApprovalView) => this.scope + ':' + approval.rpcId)
        }.width('100%') }
      }.id('conversation-list').cachedCount(2).maintainVisibleContentPosition(true).scrollBar(BarState.Off)
        .contentStartOffset(16).contentEndOffset(16).padding({ left: 20, right: 20 })
        .width('100%').constraintSize({ maxWidth: 1040 }).height('100%').align(Alignment.Top).visibility(this.trajectoryOpen ? Visibility.None : Visibility.Visible)
        .onScrollIndex((start: number, _end: number, _center: number) => { this.visibleStart = start; this.captureAnchor(); })
        .onWillScroll((offset: number, _state: ScrollState, source: ScrollSource) => {
          if (offset !== 0 && source !== ScrollSource.SCROLLER && source !== ScrollSource.SCROLLER_ANIMATION) { this.userScrolling = true; this.followTail = false; this.atTail = false; this.anchorTarget = ''; this.scopeChanged = false; }
        })
        .onTouch((event: TouchEvent) => {
          if (event.type === TouchType.Down) { this.userScrolling = true; this.followTail = false; this.anchorTarget = ''; this.scopeChanged = false; }
          else if (event.type === TouchType.Up || event.type === TouchType.Cancel) { this.followTail = this.scroller.isAtEnd(); }
        })
        .onScrollStop(() => { if (this.trajectoryOpen || this.anchorTarget) { return; } if (this.userScrolling) { this.followTail = this.scroller.isAtEnd(); this.userScrolling = false; } this.atTail = this.followTail; if (this.followTail) { this.unread = false; } this.captureAnchor(); })
        .onReachEnd(() => { if (!this.trajectoryOpen && !this.anchorTarget) { this.followTail = true; this.atTail = true; this.unread = false; } })
        .onDidScroll(() => this.captureAnchor())
        if (this.trajectoryOpen) {
          TrajectoryPage({ a: this.a, state: this.state, data: this.trajectory, active: this.active, onAction: this.onAction })
        }
      }.layoutWeight(1).width('100%')
      if (!this.atTail && !this.trajectoryOpen) {
        Button(this.a.t(this.unread ? '有新消息 · 回到底部' : '回到底部')).id('conversation-latest').height(44)
          .fontSize(this.a.font(13)).fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(() => this.jumpToTail())
      }
      if (this.currentSlash().uncertain) {
        Row({ space: 8 }) {
          Text(this.a.t('命令结果未确认')).fontSize(this.a.font(12)).fontColor(this.a.muted).layoutWeight(1)
          Button(this.a.t('重新连接')).id('command-reconnect').fontSize(this.a.font(12)).height(44).onClick(this.onReconnect)
        }.width('100%').padding({ left: 16, right: 16 })
      }
      if (this.currentSlash().hint) { Text(this.currentSlash().hint).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%').padding({ left: 16, right: 16 }) }
      GoalDock({ scope: this.scope, onReconnect: this.onReconnect, a: this.a, sessionId: this.state.selectedSessionId ?? '', data: this.goalTasks, connected: this.state.connected, onAction: this.onAction, onEdit: this.onGoalEdit, onClear: this.onGoalClear })
      UploadDock({ a: this.a, imagePath: this.imagePath, onPhoto: this.onDraftPhoto, data: this.uploads, preparing: this.preparingFiles, submitting: this.state.pendingMessageSessionId === this.state.selectedSessionId, onAction: this.onAction })
      QueueDock({ a: this.a, state: this.state, data: this.currentFeatures(), onAction: this.onAction, onReconnect: this.onReconnect })
      if (this.currentFeatures().queueSupported && (this.state.generating || this.currentFeatures().queue.length > 0)) {
        Row({ space: 8 }) {
          Button(this.a.t('排队')).id('send-mode-queue').height(44).fontSize(this.a.font(12)).fontColor(this.sendMode === 'queue' ? this.a.accent : this.a.muted)
            .backgroundColor(Color.Transparent).onClick(() => this.sendMode = 'queue')
          Button(this.a.t('插话')).id('send-mode-steer').height(44).fontSize(this.a.font(12)).fontColor(this.sendMode === 'steer' ? this.a.accent : this.a.muted)
            .backgroundColor(Color.Transparent).enabled(this.state.generating).onClick(() => this.sendMode = 'steer')
        }.width('100%').padding({ left: 12 })
      }
      if (this.voiceRequested || this.voice.voiceMode || this.voice.overlay || this.voice.phase === 'transcribing' || this.voice.phase === 'stopping' || this.voice.pendingTranscript) {
        VoiceStateBar({ a: this.a, data: this.voice, onCommand: (op: string) => { if (op === 'voice-dismiss') { this.voiceRequested = false; } this.onVoice(op); } })
      }
      Column({ space: 8 }) {
        if (this.voice.voiceMode && !this.state.childConversation) {
          VoiceHoldButton({ a: this.a, data: this.voice, onCommand: this.onVoice, onMove: this.onVoiceMove }).width('100%')
        } else {
        TextArea({ placeholder: this.a.t('描述你想要构建的内容'), text: this.draft }).id('message-draft').height('auto').constraintSize({ minHeight: 38, maxHeight: 120 }).width('100%').maxLength(100000).enabled(!this.state.childConversation || this.state.canSend)
          .fontSize(this.a.font(16)).fontColor(this.a.fg).backgroundColor(this.a.surface).onChange(this.onDraft)
          .bindPopup(this.currentSlash().visible, { builder: this.slashPopup, placement: Placement.Top, width: Math.min(540, Math.max(200, this.paneWidth - 24)),
            enableArrow: false, focusable: false, popupColor: this.a.surface, autoCancel: true, onStateChange: event => { if (!event.isVisible && this.currentSlash().visible) { this.closeSlash(); } } })
        }
        Row({ space: 8 }) {
          if (!this.state.childConversation && !!this.state.selectedSessionId) {
            Button({ type: ButtonType.Circle }) { Image($rawfile('icons/add.svg')).width(23).height(23).colorFilter(iconTint(this.a.muted)) }
              .id('message-attach').accessibilityText(this.a.t('添加附件')).height(44).width(44).padding(0).backgroundColor(Color.Transparent)
              .enabled(this.state.connected && !this.preparingFiles && (this.uploads.drafts.length < 20 || this.uploads.photos.length < 4) && this.state.pendingMessageSessionId !== this.state.selectedSessionId).bindMenu(this.attachmentMenu)
            Button(this.permissionTitle()).id('composer-permission').height(44).width(82).padding(4).fontSize(this.a.font(12)).fontColor(this.a.muted).backgroundColor(Color.Transparent).onClick(this.onControls)
            Button(this.modelTitle()).id('composer-model').height(44).layoutWeight(1).padding(4).fontSize(this.a.font(12)).fontColor(this.a.muted).backgroundColor(Color.Transparent).onClick(this.onControls)
            Button({ type: ButtonType.Circle }) { Progress({ value: this.contextPercent(), total: 100, type: ProgressType.Ring }).width(22).height(22).color(this.a.accent).backgroundColor(this.a.line) }
              .id('composer-context').accessibilityText(this.a.t('上下文')+' '+this.contextPercent().toFixed(0)+'%').width(44).height(44).padding(0).backgroundColor(Color.Transparent).onClick(this.onControls)
          } else { Blank().layoutWeight(1) }
          if (this.showsStop()) {
            Button({ type: ButtonType.Circle }) { Rect().width(14).height(14).radius(3).fill('#FFFFFF') }
              .id('message-stop').accessibilityText(this.a.t('停止生成')).width(44).height(44).padding(0).backgroundColor('#2E6BE6').enabled(this.state.connected).onClick(() => this.onAction(new Command('cancel')))
          } else if (this.hasContent() || this.state.childConversation) {
            Button({ type: ButtonType.Circle }) { Image($rawfile('icons/arrow_up.svg')).width(24).height(24).colorFilter(iconTint('#FFFFFF')) }
              .id('message-send').accessibilityText(this.a.t(this.state.pendingMessageSessionId === this.state.selectedSessionId ? '消息发送中' : '发送')).width(44).height(44).padding(0).backgroundColor('#2E6BE6').enabled(this.canSubmit()).onClick(() => this.sendMessage())
          } else {
            Button({ type: ButtonType.Circle }) { Image($rawfile('icons/'+(this.voice.voiceMode ? 'composer_keyboard' : 'voice_waveform')+'.svg')).width(24).height(24).colorFilter(iconTint('#FFFFFF')) }
              .id('voice-mode').accessibilityText(this.a.t(this.voice.voiceMode ? '键盘' : '语音')).width(44).height(44).padding(0).backgroundColor('#2E6BE6').enabled(this.state.connected && !!this.state.selectedSessionId).onClick(() => { if (!this.voice.voiceMode) { this.getUIContext().getFocusController().clearFocus(); } this.voiceRequested = true; this.onVoiceMode(!this.voice.voiceMode); })
          }
        }.width('100%')
      }.padding(12).width('calc(100% - 24vp)').margin({ left: 12, right: 12, bottom: 8 }).backgroundColor(this.a.surface).borderRadius(24).border({ width: .8, color: this.a.dark ? '#40FFFFFF' : '#0C0E131A', radius: 24 }).shadow({ radius: 10, color: this.a.dark ? '#3D000000' : '#14000000', offsetY: 4 })
    }.width('100%').height('100%').backgroundColor(this.a.surface).onAreaChange((_old: Area, area: Area) => this.paneWidth = Number(area.width))
  }
}
