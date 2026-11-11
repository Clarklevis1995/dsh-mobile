import { ReleasePage } from './ReleasePage';
import { SettingsData } from '../core/SettingsData';
import { TrajectoryPage } from './TrajectoryPage';
import { AgentNotifications } from '../core/AgentNotifications';
import { NotificationTarget } from '../core/NotificationTypes';
import { parseNotificationTarget } from '../core/NotificationTarget';
import { VoiceController } from '../core/VoiceController';
import { VoiceData, emptyVoice } from '../core/VoiceTypes';
import { PhotoPreviewPage } from './PhotoPreviewPage';
import { CacheController } from '../core/CacheController';
import { GoalEditorPage } from './GoalEditorPage';
import { GoalTasksData, emptyGoalTasks } from '../core/Models';
import { AgentPresetsPage } from './AgentPresetsPage';
import { StoragePage } from './StoragePage';
import { OfflineSearchPage } from './OfflineSearchPage';
import { LocalDatabase, StorageStats, OfflineSearchRow } from '../core/LocalDatabase';
import { WorkspaceFilesPage } from './WorkspaceFilesPage';
import { FilePreviewPage } from './FilePreviewPage';
import { WorkspaceController } from '../core/WorkspaceController';
import { ImageCache } from '../core/ImageCache';
import { FileController } from '../core/FileController';
import { TeamPage } from './TeamPage';
import { SchedulesPage } from './SchedulesPage';
import { ScheduleEditorPage } from './ScheduleEditorPage';
import { PluginsPage } from './PluginsPage';
import { SessionControlPage } from './SessionControlPage';
import { ContentPreviewPage } from './ContentPreviewPage';
import { openContentLink, saveCsvContent, contentAction } from '../core/ContentActions';
import { AppNavigation, AppRoute } from '../core/AppNavigation';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { window } from '@kit.ArkUI';
import { common, bundleManager, Configuration, ConfigurationConstant, EnvironmentCallback } from '@kit.AbilityKit';
import { i18n } from '@kit.LocalizationKit';
import { inputMethod } from '@kit.IMEKit';
import { Appearance } from '../core/Appearance';
import { AppearanceData, Command, GatewayConnectionInfo, GatewayProfileRecord, SharedState, ChatMessage, TrajectoryData, ControlData, SessionFeatureData, SlashData, PluginData, ScheduleData, ScheduledTaskData, TeamData, UploadData, GatewayImageData, ImageAssetData, PresentedFileData, WorkspaceFilesData, LocalFileData, emptyWorkspaceFiles, emptyUploads, emptyTeam, emptySchedules, emptyPlugins, emptySlash, emptyFeatures, emptyControls, emptyState } from '../core/Models';
import { HostRepository } from '../core/HostRepository';
import { HostRuntime } from '../core/HostRuntime';
import { PreferenceStore } from '../core/PreferenceStore';
import { SessionView, MessageView, QuestionView, ApprovalView } from '../components/ViewModels';
import { ErrorBanner } from '../components/DesignSystem';
import { RenameDialog } from '../components/RenameDialog';
import { WorkspacePage } from './WorkspacePage';
import { ConversationPage } from './ConversationPage';
import { SettingsPage } from './SettingsPage';
import { HostPage } from './HostPage';
import { WorkspacePicker } from './WorkspacePicker';
import { InteractionPage } from './InteractionPage';
import { SessionSidebar } from './SessionSidebar';

@Entry
@Component
struct Index {
  @State private settingsData: SettingsData = new SettingsData();
  @State private a: Appearance = new Appearance();
  @State private startupPhase: string = 'loading';
  @State private nav: AppNavigation = new AppNavigation();
  @State @Watch('updateBars') private drawerOpen: boolean = false;
  @State private activeId: string = '';
  @State private profiles: GatewayProfileRecord[] = [];
  @State private editing: GatewayProfileRecord = new GatewayProfileRecord();
  @State private shared: SharedState = emptyState();
  @State private sessions: SessionView[] = [];
  @State private messages: MessageView[] = [];
  @State private questions: QuestionView[] = [];
  @State private approvals: ApprovalView[] = [];
  @State private draft: string = '';
  @State private query: string = '';
  @State private workspaceId: string = '';
  @State private status: string = '尚未连接';
  @State private errorText: string = '';
  @State private connecting: boolean = false;
  @State private appForeground: boolean = true;
  @State private wide: boolean = false;
  @State private version: string = '';
  @State private buildCode: number = 0;
  @State private tailRevision: number = 0;
  @State private goalTasks: GoalTasksData = emptyGoalTasks();
  @State private goalEditSession: string = '';
  @State private goalEditId: string = '';
  @State private goalEditRevision: number = 0;
  @State private goalEditObjective: string = '';
  @State private storageStats: StorageStats = { windows: 0, historyBytes: 0, drafts: 0, draftBytes: 0 };
  @State private storageLoading: boolean = false;
  @State private offlineQuery: string = '';
  @State private searchMessage: string = '';
  @State private searchMessageSession: string = '';
  @State private storageStatus: string = '';
  private database: LocalDatabase | undefined = undefined;
  private cacheControllers: Map<string, CacheController> = new Map();
  @State private workspaceFiles: WorkspaceFilesData = emptyWorkspaceFiles();
  @State private readyFile: LocalFileData | undefined = undefined;
  private pendingFile: LocalFileData | undefined = undefined;
  private filesHost: string = '';
  private filesSession: string = '';
  private workspaceControllers: Map<string, WorkspaceController> = new Map();
  @State private imageAssets: Record<string, ImageAssetData> = {};
  private imageCaches: Map<string, ImageCache> = new Map();
  @State private photoSources: string[] = [];
  @State private photoIndex: number = 0;
  @State private uploads: UploadData = emptyUploads();
  @State private preparingFiles: boolean = false;
  private fileControllers: Map<string, FileController> = new Map();
  @State private teamData: TeamData = emptyTeam();
  private childLead: string = '';
  private childHost: string = '';
  @State private scheduleData: ScheduleData = emptySchedules();
  @State private pluginData: PluginData = emptyPlugins();
  @State private pluginBundle: string = '';
  @State private pluginEntry: string = '';
  @State private pluginOfficial: string = '';
  @State private slashData: SlashData = emptySlash();
  private slashTimer: number = -1;
  @State private sessionFeatures: SessionFeatureData = emptyFeatures();
  @State private controlData: ControlData = emptyControls();
  @State private trajectory: TrajectoryData = { sessionId: '', nodes: [] };
  @State private previewContent: string = '';
  @State private previewTable: boolean = false;
  @State private previewHeading: string = '';
  @State private renameTitle: string = '';
  private renameId: string = '';
  @State private confirmTitle: string = '';
  @State private confirmMessage: string = '';
  @State private confirmLabel: string = '';
  private confirmAction: () => void = () => {};
  private confirmation: CustomDialogController = new CustomDialogController({
    builder: ConfirmDialog({ a: this.a, title: this.confirmTitle, message: this.confirmMessage, confirmLabel: this.confirmLabel, onConfirm: () => this.confirmAction() }),
    autoCancel: true, alignment: DialogAlignment.Center
  });
  private confirm(title: string, message: string, label: string, action: () => void): void {
    this.confirmation.close(); this.confirmTitle = this.a.t(title); this.confirmMessage = this.a.t(message); this.confirmLabel = this.a.t(label); this.confirmAction = action; this.confirmation.open();
  }
  private storage: PreferenceStore = new PreferenceStore();
  private repository: HostRepository = new HostRepository(this.storage);
  @State private voiceData: VoiceData = emptyVoice();
  private voiceControllers: Map<string, VoiceController> = new Map();
  private refreshVoiceActive(): void { this.voiceControllers.forEach((voice: VoiceController, id: string) => voice.setActive(this.active && this.appForeground && id === this.activeId && (this.nav.current === AppRoute.Conversation || this.nav.current === AppRoute.SearchConversation) && !this.drawerOpen)); }
  private notifications?: AgentNotifications;
  @State private notificationsEnabled: boolean = false;
  @State private notificationStatus: string = '点击开关申请系统通知授权';
  private notificationTarget?: NotificationTarget;
  private offlineLead: string | undefined = undefined;
  private offlineHost: string = '';
  private offlineScopeSession: string = '';
  private notificationDeadline: number = 0;
  private notificationListener: () => void = () => this.consumeNotificationTarget();
  private runtimes: Map<string, HostRuntime> = new Map();
  private runtime?: HostRuntime;
  private authId: string = '';
  private canceledAuthIds: Set<string> = new Set();
  private active: boolean = false;
  private busy: boolean = false;
  private viewportWidth: number = 0;
  private viewportHeight: number = 0;
  private systemFontScale: number = 1;
  private searchTimer: number = -1;
  private context?: common.UIAbilityContext;
  private appContext?: common.ApplicationContext;
  private environmentId: number = -1;
  private hostWindow?: window.Window;
  private keyboardVisible: boolean = false;
  private switchRevision: number = 0;
  private foreground: () => void = () => { this.appForeground = true; this.refreshVoiceActive(); this.runtime?.client.setForeground(true); };
  private backgroundListener: () => void = () => { this.appForeground = false; this.refreshVoiceActive(); this.runtime?.client.setForeground(false); };
  private keyboard: (height: number) => void = height => this.keyboardVisible = height > 0;
  private environment: EnvironmentCallback = {
    onConfigurationUpdated: (config: Configuration): void => this.configure(config),
    onMemoryLevel: (_level: number): void => { this.runtimes.forEach((runtime: HostRuntime, id: string) => { if (id !== this.activeId && !runtime.client.connected) { runtime.client.action(new Command('release-history')).catch(() => {}); this.imageCaches.get(id)?.releaseMemory(); } }); }
  };
  private renameDialog: CustomDialogController = new CustomDialogController({
    builder: RenameDialog({ a: this.a, initial: this.renameTitle, onSave: (title: string) => this.rename(title) }),
    autoCancel: true, alignment: DialogAlignment.Center
  });

  aboutToAppear(): void {
    this.active = true;
    this.nav.onChanged = () => { this.updateBars(); this.settleFilePreview(); this.refreshVoiceActive(); };
    this.nav.onRemoved = route => {
      if (route === AppRoute.SearchConversation) {
        const host = this.offlineHost; const lead = this.offlineLead; this.offlineHost = ''; this.offlineLead = undefined;
        this.searchMessage = ''; this.searchMessageSession = '';
        if (host === this.activeId && this.nav.paths.getAllPathName().includes(AppRoute.OfflineSearch) && lead !== this.runtime?.client.selectedSessionId) {
          const command = new Command('select'); command.sessionId = lead; command.keepState = true; this.action(command);
        }
      }

      if (route === AppRoute.Files) {
        const host = this.filesHost; const session = this.filesSession; this.filesHost = ''; this.filesSession = '';
        if (host === this.activeId && session === this.runtime?.client.selectedSessionId) { this.action(new Command('files-close')); }
      }
      if (route === AppRoute.Photos) { this.imageCaches.get(this.activeId)?.releasePreview(); this.photoSources = []; }
      if (route === AppRoute.ChildConversation) {
        const lead = this.childLead; const host = this.childHost; this.childLead = ''; this.childHost = '';
        if (lead && host === this.activeId && this.runtime && this.nav.paths.getAllPathName().includes(AppRoute.Team)) { const c = new Command('select'); c.sessionId = lead; c.keepState = true; this.action(c); }
      } if (route === AppRoute.HostAdd && this.authId) { this.cancelAuth(); } };
    const context = this.getUIContext().getHostContext();
    if (!context) { this.errorText = '应用上下文暂不可用'; this.startupPhase = 'error'; return; }
    this.context = context as common.UIAbilityContext;
    this.notifications = new AgentNotifications(this.context);
    this.notifications.translate = value => this.a.t(value); this.notifications.knownHost = id => this.profiles.some(profile => profile.localId === id);
    this.notifications.onChange = (enabled, status) => { if (this.active) { this.notificationsEnabled = enabled; this.notificationStatus = status; } };
    this.notifications.open(); this.context.eventHub.on('notificationTarget', this.notificationListener);
    this.database = new LocalDatabase(this.context);
    this.context.eventHub.on('gatewayForeground', this.foreground);
    this.context.eventHub.on('gatewayBackground', this.backgroundListener);
    this.appContext = this.context.getApplicationContext();
    this.environmentId = this.appContext.on('environment', this.environment);
    this.configure(this.context.config);
    context.resourceManager.getConfiguration().then(config => { this.a.systemDark = Number(config.colorMode) === 0; this.a.systemEnglish = i18n.System.getSystemLanguage().startsWith('en'); }).catch(() => {});
    window.getLastWindow(context).then(host => { if (this.active) { this.hostWindow = host; host.on('keyboardHeightChange', this.keyboard); this.updateBars(); } }).catch(() => {});
    bundleManager.getBundleInfoForSelf(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT).then(info => {
      this.version = info.versionName; this.buildCode = info.versionCode;
    }).catch(() => { this.errorText = '无法读取当前应用版本'; });
    this.initialize();
  }
  private configure(config: Configuration): void {
    this.a.systemDark = config.colorMode === ConfigurationConstant.ColorMode.COLOR_MODE_DARK;
    this.a.systemEnglish = i18n.System.getSystemLanguage().startsWith('en');
    this.systemFontScale = config.fontSizeScale ?? 1; this.resize(); this.updateBars();
  }
  private applyColorMode(): void {
    // 应用级外观同步给原生菜单／对话框；不修改设备系统设置。
    const mode = this.a.data.style === 'system' ? ConfigurationConstant.ColorMode.COLOR_MODE_NOT_SET :
      this.a.data.style === 'dark' ? ConfigurationConstant.ColorMode.COLOR_MODE_DARK : ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT;
    this.appContext?.setColorMode(mode);
  }
  private updateBars(): void {
    const brand = this.nav.current === AppRoute.Home && !this.drawerOpen;
    const dark = brand || this.a.dark;
    const chat = [AppRoute.Conversation, AppRoute.ChildConversation, AppRoute.SearchConversation].includes(this.nav.current as AppRoute);
    const color = brand ? '#06172B' : chat ? this.a.surface : this.nav.current.startsWith('settings') ? (this.nav.current === AppRoute.Font && !this.a.dark ? '#F7F7F8' : this.a.settingsBg) : this.a.bg;
    try { this.hostWindow?.setWindowBackgroundColor(color); } catch (_) {}
    this.hostWindow?.setWindowSystemBarProperties({ statusBarColor: color, navigationBarColor: color,
      statusBarContentColor: dark ? '#FFFFFF' : '#0E131A', navigationBarContentColor: dark ? '#FFFFFF' : '#0E131A' }).catch(() => {});
  }
  private resize(): void {
    const scale = this.a.data.fontFollowsSystem ? this.systemFontScale : this.a.data.fontScale;
    this.wide = this.viewportWidth >= 900 && this.viewportHeight >= 500 && scale < 1.5;
  }
  private async initialize(): Promise<void> {
    this.startupPhase = 'loading';
    try {
      if (!this.context) { throw new Error('应用上下文暂不可用'); }
      await this.storage.open(this.context); await this.repository.load();
      if (!this.active) { return; }
      this.profiles = this.repository.profiles; this.a.data = this.repository.appearance; this.applyColorMode(); this.resize();
      if (this.repository.selectedId) { await this.activate(this.repository.selectedId); }
      this.startupPhase = 'ready'; this.consumeNotificationTarget();
    } catch (error) { this.startupPhase = 'error'; this.errorText = (error as Error).message; }
  }
  aboutToDisappear(): void {
    this.nav.onChanged = () => {}; this.nav.onRemoved = () => {};
    this.active = false; clearTimeout(this.searchTimer); clearTimeout(this.slashTimer); this.renameDialog.close(); this.confirmation.close(); this.confirmAction = () => {};
    this.hostWindow?.off('keyboardHeightChange', this.keyboard);
    this.context?.eventHub.off('gatewayForeground', this.foreground);
    this.context?.eventHub.off('gatewayBackground', this.backgroundListener);
    if (this.environmentId >= 0) { this.appContext?.off('environment', this.environmentId); }
    this.cacheControllers.forEach(controller => controller.dispose()); this.cacheControllers.clear();
    this.workspaceControllers.forEach(controller => controller.dispose()); this.workspaceControllers.clear();
    this.imageCaches.forEach(cache => cache.dispose()); this.imageCaches.clear();
    this.repository.dispose(); this.context?.eventHub.off('notificationTarget', this.notificationListener); if (this.notifications) { this.notifications.onChange = () => {}; this.notifications.knownHost = () => false; }
    this.voiceControllers.forEach(voice => voice.dispose()); this.voiceControllers.clear();
    this.fileControllers.forEach(files => files.dispose()); this.fileControllers.clear();
    this.runtimes.forEach(runtime => {
      runtime.client.onChange = () => {}; runtime.client.onSent = () => {}; runtime.client.onTrajectory = () => {}; runtime.client.onControls = () => {}; runtime.client.onFeatures = () => {}; runtime.client.onSlash = () => {}; runtime.client.onPlugins = () => {}; runtime.client.onSchedules = () => {}; runtime.client.onTeam = () => {}; runtime.client.onDraftMutation = () => {}; runtime.client.onRestoredDraft = () => {};
      runtime.client.onAuthenticated = async () => {}; runtime.client.dispose();
    });
  }
  onBackPress(): boolean { return this.interceptBack(); }
  private interceptBack(): boolean {
    if (this.keyboardVisible) { inputMethod.getController().hideTextInput().catch(() => {}); return true; }
    if (this.drawerOpen) { this.drawerOpen = false; return true; }
    // 未消费时交给 Navigation，保留系统返回及交互式侧滑转场。
    return false;
  }
  private back(): boolean { return this.interceptBack() || this.nav.back(); }
  private hostName(): string {
    const profile = this.profiles.find(row => row.localId === this.activeId);
    return profile?.alias || profile?.gatewayName || '';
  }
  private runtimeFor(id: string): HostRuntime {
    const old = this.runtimes.get(id); if (old) { return old; }
    const runtime = new HostRuntime(id); this.runtimes.set(id, runtime);
    if (this.database) {
      const cache = new CacheController(id, this.database, runtime.client, runtime.drafts); this.cacheControllers.set(id, cache);
      cache.onDraft = (session: string, text: string) => {
        if (runtime.client.selectedSessionId === session) { runtime.draft = text; if (this.activeId === id && this.active) { this.draft = text; } }
      };
      cache.onStatus = message => { if (this.activeId === id && this.active) { this.storageStatus = message; } };
    }
    runtime.client.onNotifications = notices => this.notifications?.accept(id, notices, this.appForeground);
    runtime.client.voiceDraft = () => runtime.draft;
    if (this.context) {
      const voice = new VoiceController(this.context, runtime.client); this.voiceControllers.set(id, voice);
      voice.onToast = message => { if (this.active && this.activeId === id) { this.getUIContext().getPromptAction().showToast({ message: this.a.t(message) }); } };
      voice.onChange = value => { if (this.active && this.activeId === id && value.sessionId === runtime.client.selectedSessionId) { this.voiceData = value; } };
      voice.onDraft = (session, text) => { if (runtime.client.selectedSessionId === session) { runtime.draft = text; runtime.drafts.changed(session, text); if (this.active && this.activeId === id) { this.draft = text; } } };
      const workspace = new WorkspaceController(runtime.client, this.context, id); this.workspaceControllers.set(id, workspace);
      workspace.onChange = value => { if (this.activeId === id && this.active) { this.workspaceFiles = value; } };
      workspace.onReady = file => {
        if (this.activeId === id && this.active && this.shared.selectedSessionId === file.sessionId) { this.pendingFile = file; this.settleFilePreview(); }
        else { workspace.release(file.transferId); }
      };
      const cache = new ImageCache(runtime.client, this.context); this.imageCaches.set(id, cache);
      cache.onChange = () => { if (this.activeId === id && this.active) { this.imageAssets = cache.snapshot(); } };
    }
    const files = new FileController(runtime.client); this.fileControllers.set(id, files);
    files.onChange = (value: UploadData, preparing: boolean) => { if (this.activeId === id && this.active) { this.uploads = value; this.preparingFiles = preparing; } };
    files.onError = message => { if (this.activeId === id && this.active) { this.errorText = message; } };
    runtime.client.onChange = (state: SharedState, rows: ChatMessage[], status: string, error: string) => {
      this.workspaceControllers.get(id)?.scopeChanged(state.selectedSessionId);
      this.imageCaches.get(id)?.scopeChanged(state.selectedSessionId);
      files.scopeChanged(state.selectedSessionId);
      const changed = runtime.update(state, rows, status, error);
      this.voiceControllers.get(id)?.stateChanged(state);
      this.cacheControllers.get(id)?.stateChanged(state);
      if (this.authId === id) { if (error || state.connected) { this.connecting = false; } if (error) { this.errorText = error; } }
      if (this.activeId !== id || !this.active) { return; }
      if (!error && this.shared.catalogBusy.includes('workspace-create') && !state.catalogBusy.includes('workspace-create')) {
        const created = state.workspaces.find(row => row.path === this.shared.directory.path);
        if (created) { runtime.workspaceId = created.workspaceId; this.workspaceId = created.workspaceId; this.nav.home(); }
      }
      this.shared = state; this.messages = runtime.messages; this.sessions = runtime.sessions;
      this.questions = runtime.questions; this.approvals = runtime.approvals; this.draft = runtime.draft;
      this.status = status; this.syncSettings(); this.errorText = error; this.refreshVoiceActive(); this.settleNotificationTarget();
      if (changed && runtime.navigateCreated && state.selectedSessionId) { runtime.navigateCreated = false; this.nav.conversation(); }
      if (!state.selectedSessionId && this.nav.paths.getAllPathName().includes(AppRoute.Conversation)) { this.nav.home(); }
    };
    runtime.client.onGoalTasks = value => { if (this.active && this.activeId === id && value.sessionId === runtime.client.selectedSessionId) { this.goalTasks = value; } };
    runtime.client.onTeam = value => { if (this.activeId === id && this.active) { this.teamData = value; } };
    runtime.client.onSchedules = value => { if (this.activeId === id && this.active) { this.scheduleData = value; } };
    runtime.client.onPlugins = value => { if (this.activeId === id && this.active) { this.pluginData = value; } };
    runtime.client.onSlash = value => { if (this.activeId === id && this.active) { this.slashData = value; } };
    runtime.client.onDraftMutation = value => {
      const replaced = runtime.drafts.replace(value.sessionId, value.expectedRevision, value.expectedText, value.replacement);
      if (replaced !== undefined && runtime.state.selectedSessionId === value.sessionId) { runtime.draft = replaced; if (this.activeId === id) { this.draft = replaced; } }
    };
    runtime.client.onFeatures = value => { if (this.activeId === id && this.active) { this.sessionFeatures = value; } };
    runtime.client.onRestoredDraft = (session: string, text: string) => {
      const restored = runtime.drafts.append(session, text);
      if (runtime.state.selectedSessionId === session) { runtime.draft = restored; if (this.activeId === id) { this.draft = restored; } }
    };
    runtime.client.onControls = value => {
      if (this.activeId === id && this.active && (!value.sessionId || value.sessionId === this.shared.selectedSessionId)) { this.controlData = value; this.syncSettings(); }
    };
    runtime.client.onTrajectory = value => {
      if (this.activeId !== id || value.sessionId !== this.shared.selectedSessionId) { return; }
      if (value.detail !== undefined) { this.preview(value.detail, false, '轨迹详情'); } else { this.trajectory = value; }
    };
    runtime.client.onSent = sessionId => {
      runtime.draft = runtime.drafts.acknowledged(sessionId, runtime.state.selectedSessionId, runtime.draft);
      if (this.activeId === id) { this.draft = runtime.draft; }
    };
    runtime.client.onAuthenticated = async (info: GatewayConnectionInfo): Promise<void> => {
      if (this.canceledAuthIds.has(id)) { throw new Error('认证已取消'); }
      await this.repository.connected(id, info);
      if (this.canceledAuthIds.has(id)) { await this.repository.remove(id); throw new Error('认证已取消'); }
      if (!this.active) { return; }
      this.profiles = this.repository.profiles;
      if (this.authId === id) {
        await this.activate(id, false, false);
        if (this.authId === id) { this.authId = ''; this.connecting = false; this.nav.home(false); }
      }
    };
    return runtime;
  }
  private async activate(id: string, reconnect: boolean = true, resetNavigation: boolean = true): Promise<void> {
    const revision = ++this.switchRevision;
    try { await this.repository.select(id); } catch (error) { this.errorText = (error as Error).message; return; }
    if (revision !== this.switchRevision || !this.active) { return; }
    if (this.activeId !== id && this.runtime) {
      const previous = this.runtime; const previousId = this.activeId;
      // 导出先取得独立字符串快照，后台加密写入仍按数据库队列和 epoch 仲裁。
      try { await previous.client.action(new Command('cache-export')); } catch (_) {}
      if (revision !== this.switchRevision || !this.active) { return; }
      previous.client.disconnect();
      try { await previous.client.action(new Command('release-history')); } catch (_) {}
      this.imageCaches.get(previousId)?.releaseMemory();
      if (revision !== this.switchRevision || !this.active) { return; }
    }
    if (this.activeId !== id) { clearTimeout(this.slashTimer); this.voiceData = emptyVoice(); this.goalTasks = emptyGoalTasks(); this.teamData = emptyTeam(); this.scheduleData = emptySchedules(); this.pluginData = emptyPlugins(); this.slashData = emptySlash(); this.sessionFeatures = emptyFeatures(); this.controlData = emptyControls(); this.trajectory = { sessionId: '', nodes: [] }; this.previewContent = ''; }
    clearTimeout(this.searchTimer); clearTimeout(this.slashTimer); this.drawerOpen = false; this.renameDialog.close(); this.confirmation.close(); this.confirmAction = () => {}; this.renameId = ''; this.activeId = id;
    const runtime = this.runtimeFor(id); this.runtime = runtime; this.refreshVoiceActive();
    this.workspaceFiles = this.workspaceControllers.get(id)?.snapshot ?? emptyWorkspaceFiles();
    this.imageAssets = this.imageCaches.get(id)?.snapshot() ?? {};
    this.uploads = this.fileControllers.get(id)?.snapshot ?? emptyUploads(); this.preparingFiles = this.fileControllers.get(id)?.preparing ?? false;
    this.workspaceId = runtime.workspaceId; this.query = runtime.query; this.draft = runtime.draft;
    if (resetNavigation) { this.nav.home(false); } this.errorText = ''; runtime.client.refreshView();
    if (reconnect && !runtime.state.connected) {
      const profile = this.profiles.find(row => row.localId === id);
      if (!profile) { return; }
      try { await runtime.client.connect(profile.preferredEndpoint || profile.endpoints[0], '', '', true, profile.gatewayId, profile.endpoints); }
      catch (error) { if (this.activeId === id) { this.errorText = (error as Error).message; } }
    }
  }
  private syncSettings(): void {
    const profile = this.profiles.find(row => row.localId === this.activeId);
    this.settingsData.hostSnapshot = this.shared.hostSnapshot; this.settingsData.connected = this.shared.connected; this.settingsData.controls = this.controlData;
    this.settingsData.status = this.status; this.settingsData.hostName = this.hostName();
    this.settingsData.endpoint = profile?.preferredEndpoint || profile?.endpoints[0] || '';
  }
  private async pingGateway(): Promise<void> {
    const runtime = this.runtime; if (!runtime?.client.connected) { return; }
    const host = this.activeId; const generation = runtime.client.connectionGeneration;
    const ok = await runtime.client.ping();
    if (this.active && host === this.activeId && generation === runtime.client.connectionGeneration) { this.getUIContext().getPromptAction().showToast({ message: this.a.t(ok ? '网关已响应' : '网关未响应，请检查连接') }); }
  }
  private reconnect(): void { const id = this.activeId; if (!id) { return; } this.runtime?.client.disconnect(); this.activate(id, true, false); }
  private showHosts(): void { this.nav.push(AppRoute.Hosts); this.errorText = ''; }
  private async connect(endpoint: string, code: string, payload: string): Promise<void> {
    if (this.connecting) { return; }
    this.connecting = true; this.errorText = ''; const id = this.authId || this.repository.newId(); this.authId = id; this.canceledAuthIds.delete(id);
    try { await this.runtimeFor(id).client.connect(endpoint, code, payload); }
    catch (error) { if (this.authId === id) { this.connecting = false; this.errorText = (error as Error).message; } }
  }
  private async cancelAuth(): Promise<void> {
    const id = this.authId; this.authId = ''; if (id) { this.canceledAuthIds.add(id); this.switchRevision++; } this.connecting = false; this.errorText = '';
    try {
      if (id && id !== this.activeId) {
        const runtime = this.runtimes.get(id); runtime?.client.disconnect();
        await runtime?.client.forget(); await this.repository.remove(id);
        if (this.activeId && this.repository.profiles.some(row => row.localId === this.activeId)) { await this.repository.select(this.activeId); }
        this.profiles = this.repository.profiles; this.notifications?.removeHost(id); await this.cacheControllers.get(id)?.removed().catch(() => { this.storageStatus = '主机缓存清理失败，请在存储设置中重试'; }); this.cacheControllers.delete(id); this.workspaceControllers.get(id)?.dispose(); this.workspaceControllers.delete(id); this.imageCaches.get(id)?.dispose(); this.imageCaches.delete(id); this.fileControllers.get(id)?.dispose(); this.fileControllers.delete(id); this.voiceControllers.get(id)?.dispose(); this.voiceControllers.delete(id); this.runtimes.get(id)?.client.dispose(); this.runtimes.delete(id);
      }
    } catch (error) { this.errorText = (error as Error).message; }
  }
  private async saveHost(profile: GatewayProfileRecord): Promise<void> {
    try {
      const before = this.profiles.find(row => row.localId === profile.localId);
      await this.repository.edit(profile); this.profiles = this.repository.profiles;
      if (this.nav.current === AppRoute.HostEdit) { this.nav.back(); }
      if (JSON.stringify(before?.endpoints) !== JSON.stringify(profile.endpoints) || before?.preferredEndpoint !== profile.preferredEndpoint) {
        this.runtimes.get(profile.localId)?.client.disconnect();
        if (this.activeId === profile.localId) { await this.activate(profile.localId, true, false); }
      }
    }
    catch (error) { this.errorText = (error as Error).message; }
  }
  private removeHost(id: string): void {
    this.confirm('确认移除该主机？', '仅移除此设备上的资料和凭据', '移除', () => this.remove(id));
  }
  private async remove(id: string): Promise<void> {
    if (this.busy) { return; } this.busy = true;
    try {
      this.runtimes.get(id)?.client.disconnect(); await this.repository.remove(id); await this.cacheControllers.get(id)?.removed().catch(() => { this.storageStatus = '主机缓存清理失败，请在存储设置中重试'; }); this.cacheControllers.delete(id); this.workspaceControllers.get(id)?.dispose(); this.workspaceControllers.delete(id); this.imageCaches.get(id)?.dispose(); this.imageCaches.delete(id); this.fileControllers.get(id)?.dispose(); this.fileControllers.delete(id); this.voiceControllers.get(id)?.dispose(); this.voiceControllers.delete(id); this.runtimes.get(id)?.client.dispose(); this.runtimes.delete(id); this.profiles = this.repository.profiles; this.notifications?.removeHost(id);
      if (this.activeId === id) {
        clearTimeout(this.slashTimer); this.goalTasks = emptyGoalTasks(); this.teamData = emptyTeam(); this.scheduleData = emptySchedules(); this.pluginData = emptyPlugins(); this.slashData = emptySlash(); this.sessionFeatures = emptyFeatures(); this.controlData = emptyControls(); this.trajectory = { sessionId: '', nodes: [] }; this.previewContent = ''; this.runtime = undefined; this.activeId = ''; this.shared = emptyState(); this.sessions = []; this.messages = []; this.questions = []; this.approvals = []; this.draft = ''; this.query = '';
        this.workspaceId = ''; this.status = '尚未连接'; this.errorText = ''; this.syncSettings();
        if (this.repository.selectedId) { await this.activate(this.repository.selectedId); }
      }
    } catch (error) { this.errorText = (error as Error).message; } finally { this.busy = false; }
  }
  private forgetAll(): void {
    this.confirm('删除全部主机', '仅移除此设备上的资料和凭据', '移除', async () => {
        try {
          if (this.authId) { this.canceledAuthIds.add(this.authId); this.authId = ''; this.connecting = false; }
          this.runtimes.forEach(runtime => runtime.client.disconnect()); const removedProfiles = this.profiles; await this.repository.forgetAll(); this.profiles = []; for (const controller of this.cacheControllers.values()) { await controller.removed().catch(() => {}); } this.cacheControllers.clear(); this.workspaceControllers.forEach(controller => controller.dispose()); this.workspaceControllers.clear(); this.imageCaches.forEach(cache => cache.dispose()); this.imageCaches.clear(); this.fileControllers.forEach(files => files.dispose()); this.fileControllers.clear(); this.voiceControllers.forEach(voice => voice.dispose()); this.voiceControllers.clear(); for (const profile of removedProfiles) { await this.notifications?.removeHost(profile.localId); } this.runtimes.forEach(runtime => runtime.client.dispose()); this.runtimes.clear(); this.voiceData = emptyVoice();
          clearTimeout(this.slashTimer); this.goalTasks = emptyGoalTasks(); this.teamData = emptyTeam(); this.scheduleData = emptySchedules(); this.pluginData = emptyPlugins(); this.slashData = emptySlash(); this.sessionFeatures = emptyFeatures(); this.controlData = emptyControls(); this.trajectory = { sessionId: '', nodes: [] }; this.previewContent = ''; this.runtime = undefined; this.activeId = ''; this.profiles = []; this.shared = emptyState(); this.messages = []; this.sessions = [];
          this.questions = []; this.approvals = []; this.draft = ''; this.query = ''; this.workspaceId = ''; this.status = '尚未连接'; this.errorText = ''; this.syncSettings(); this.nav.home();
        } catch (error) { this.errorText = (error as Error).message; }
      });
  }
  private async appearance(value: AppearanceData): Promise<void> {
    this.a.data = value; this.applyColorMode(); this.resize(); this.updateBars();
    try { await this.repository.saveAppearance(value); } catch (_) { this.errorText = '设置保存失败，请检查本地存储后重试'; }
  }
  private editGoal(): void {
    const goal = this.goalTasks.goal?.goal; if (!goal || this.goalTasks.sessionId !== this.shared.selectedSessionId) { return; }
    this.goalEditSession = this.shared.selectedSessionId ?? ''; this.goalEditId = goal.id; this.goalEditRevision = goal.revision; this.goalEditObjective = goal.objective; this.nav.push(AppRoute.GoalEdit);
  }
  private clearGoal(): void {
    const host = this.activeId; const session = this.shared.selectedSessionId; const goal = this.goalTasks.goal?.goal; if (!goal) { return; }
    this.confirm('删除目标？', goal.objective, '删除', () => { if (host !== this.activeId || session !== this.shared.selectedSessionId) { return; } const c = new Command('goal-clear'); c.sessionId = session; c.itemId = goal.id; c.draftRevision = goal.revision; this.action(c); });
  }
  private defaultAgent(id: string): void {
    const host = this.activeId; const preset = this.controlData.presets.find(row => row.id === id);
    if (!preset || preset.broken || this.controlData.agentPresetDefault === id) { return; }
    this.confirm('设为全局默认预设？', '将“' + (preset.name || id) + '”设为新会话的默认 Agent。这会更新部署级设置，并同步影响 WebUI。', '设为默认', () => {
      if (host !== this.activeId) { return; } const command = new Command('controls-default'); command.target = 'agent-preset'; command.value = id; this.action(command);
    });
  }
  private async refreshStorage(): Promise<void> {
    const host = this.activeId; if (!host || !this.database) { return; } this.storageLoading = true;
    try { const stats = await this.database.stats(host); if (this.active && this.activeId === host) { this.storageStats = stats; this.storageStatus = this.a.t('加密本地数据库已就绪'); } }
    catch (_) { if (this.active && this.activeId === host) { this.storageStatus = this.a.t('本地数据库暂不可用，请重启后重试'); } }
    finally { if (this.active && this.activeId === host) { this.storageLoading = false; } }
  }
  private clearStorage(drafts: boolean): void {
    const host = this.activeId;
    this.confirm(drafts ? '清理历史与文字草稿？' : '清理历史缓存？', drafts ? '只清理此主机的本地数据，登录信息保留。' : '只清理此主机的本地历史，文字草稿和登录信息保留。', '清理', async () => {
      if (host !== this.activeId) { return; } this.storageLoading = true;
      try { await this.cacheControllers.get(host)?.clear(drafts); await this.refreshStorage(); }
      catch (_) { if (host === this.activeId) { this.storageStatus = this.a.t('清理失败，请重启后重试'); } }
      finally { if (host === this.activeId) { this.storageLoading = false; } }
    });
  }
  private openOfflineSearch(query: string = ''): void { this.offlineQuery = query; this.offlineScopeSession = this.shared.selectedSessionId ?? ""; this.nav.push(AppRoute.OfflineSearch); }
  private offlineResult(host: string, row: OfflineSearchRow): void {
    if (host !== this.activeId || this.nav.current !== AppRoute.OfflineSearch) { return; }
    this.offlineHost = host; this.offlineLead = this.shared.selectedSessionId;
    this.searchMessage = row.messageId; this.searchMessageSession = row.sessionId;
    this.nav.push(AppRoute.SearchConversation);
    if (this.shared.selectedSessionId !== row.sessionId) { this.voiceControllers.get(host)?.setActive(false); const command = new Command('select'); command.sessionId = row.sessionId; this.action(command); }
  }
  private search(query: string): void {
    if (!this.shared.connected && query.trim() && this.nav.current === AppRoute.Home) { this.openOfflineSearch(query); return; }
    this.query = query; if (this.runtime) { this.runtime.query = query; }
    clearTimeout(this.searchTimer); const runtime = this.runtime;
    this.searchTimer = setTimeout(() => {
      if (!runtime || this.activeId !== runtime.id) { return; }
      const command = new Command('search'); command.query = query; this.action(command);
    }, 250);
  }
  private async editSchedule(task: ScheduledTaskData): Promise<void> {
    const scope = this.activeId; const previous = this.scheduleData.editor?.token;
    const command = new Command('schedule-edit-open'); command.sessionId = task.sessionId; command.itemId = task.id;
    await this.action(command);
    if (this.activeId === scope && this.scheduleData.editor?.token !== previous && this.scheduleData.editor?.task.id === task.id && this.scheduleData.editor?.task.sessionId === task.sessionId) { this.nav.push(AppRoute.ScheduleEdit); }
  }
  private deleteSchedule(task: ScheduledTaskData): void {
    const scope = this.activeId;
    this.confirm('删除定时任务？', task.title, '删除', () => { if (this.activeId !== scope) { return; } const command = new Command('schedule-delete'); command.sessionId = task.sessionId; command.itemId = task.id; this.action(command); });
  }
  private pluginToggle(command: Command, critical: boolean): void {
    if (!critical) { this.action(command); return; }
    const scope = this.activeId;
    this.confirm('关闭移动网关？', '关闭后本设备会断开连接，需要在主机上重新启用网关。', '关闭', () => {
      if (this.activeId === scope) { command.outcome = 'confirmed'; this.action(command); }
    });
  }
  private editedDraft(value: string): void {
    this.draft = value; const runtime = this.runtime; if (!runtime) { return; }
    const session = runtime.state.selectedSessionId; const changed = runtime.drafts.changed(session, value); runtime.draft = value; this.voiceControllers.get(this.activeId)?.draftChanged();
    if (!changed || this.shared.childConversation) { return; }
    clearTimeout(this.slashTimer);
    this.slashTimer = setTimeout(() => {
      if (!this.active || this.activeId !== runtime.id || runtime.client.selectedSessionId !== session) { return; }
      const command = new Command('slash-input'); command.sessionId = session; command.text = runtime.draft;
      command.draftRevision = runtime.drafts.version(session); command.locale = this.a.english ? 'en' : 'zh-CN';
      runtime.client.action(command).catch(() => {});
    }, 80);
  }
  private async action(command: Command): Promise<void> {
    const runtime = this.runtime; if (!runtime) { this.errorText = '请先选择并连接主机'; return; }
    command.locale = this.a.english ? 'en' : 'zh-CN';
    if (command.op.startsWith('slash-') || command.op === 'send') { command.text = this.draft; command.draftRevision = runtime.drafts.version(this.shared.selectedSessionId); }
    if (command.op === 'send') { command.timeZone = i18n.getTimeZone().getID(); }
    if (command.op === 'create') { command.workspaceId = this.workspaceId && this.workspaceId !== '__all__' ? this.workspaceId : undefined; runtime.navigateCreated = true; }
    if (['send', 'cancel', 'question-answer', 'question-cancel', 'approval-decision'].includes(command.op)) { runtime.scrollY = -1; this.tailRevision++; }
    if (command.op === 'send') { command.text = this.draft; runtime.draft = this.draft;
      if (this.shared.selectedSessionId) { runtime.drafts.sent(this.shared.selectedSessionId, this.draft); } }
    try { await runtime.client.action(command); } catch (error) {
      runtime.navigateCreated = false; if (runtime.id === this.activeId) { this.errorText = (error as Error).message; }
    }
  }
  private consumeNotificationTarget(): void {
    if (!this.active || this.startupPhase !== 'ready') { return; }
    const raw = AppStorage.get<string>('whaleness.notification-target'); AppStorage.delete('whaleness.notification-target');
    const target = raw ? parseNotificationTarget(raw) : undefined;
    if (!target || !this.profiles.some(profile => profile.localId === target.host)) { return; }
    this.notificationTarget = target; this.notificationDeadline = Date.now() + 30000;
    this.activate(target.host).then(() => this.settleNotificationTarget());
  }
  private settleNotificationTarget(): void {
    const target = this.notificationTarget; if (!target || target.host !== this.activeId) { return; }
    if (this.shared.sessions.some(session => session.id === target.session)) {
      this.notificationTarget = undefined; this.selectSession(target.session);
    } else if (Date.now() > this.notificationDeadline || (this.shared.connected && !this.shared.catalogLoading)) {
      this.notificationTarget = undefined; this.getUIContext().getPromptAction().showToast({ message: this.a.t('通知对应会话暂不可用，请从会话列表重新选择') });
    }
  }
  private selectSession(id: string): void {
    this.drawerOpen = false; this.nav.conversation();
    // 返回后重入同一会话只恢复页面，避免再次订阅清空已加载的分页历史。
    if (this.shared.selectedSessionId === id) { return; }
    this.voiceControllers.get(this.activeId)?.setActive(false);
    const command = new Command('select'); command.sessionId = id; this.action(command);
  }
  private settleFilePreview(): void {
    const file = this.pendingFile; if (!file || this.nav.transitioning) { return; } this.pendingFile = undefined;
    if (file.hostId === this.activeId && file.sessionId === this.runtime?.client.selectedSessionId && this.nav.current === AppRoute.Files) { this.readyFile = file; this.nav.push(AppRoute.FilePreview); }
    else { this.workspaceControllers.get(file.hostId)?.release(file.transferId); }
  }
  private showFiles(): void { this.filesHost = this.activeId; this.filesSession = this.shared.selectedSessionId ?? ''; this.nav.push(AppRoute.Files); }
  private openWorkspaceFile(file: PresentedFileData): void {
    if (!file.path || !this.shared.selectedSessionId) { return; }
    this.showFiles(); const c = new Command('files-resolve'); c.sessionId = this.shared.selectedSessionId; c.path = file.path; this.action(c);
  }
  private previewReceivedPhoto(image: GatewayImageData, images: GatewayImageData[]): void {
    const cache = this.imageCaches.get(this.activeId); const selected = this.imageAssets['asset:' + image.attachmentId]?.path;
    if (!cache || !selected) { cache?.require(image, true); return; }
    this.photoSources = cache.pin(images.map(value => value.attachmentId)); this.photoIndex = Math.max(0, this.photoSources.indexOf(selected)); this.nav.push(AppRoute.Photos);
  }
  private previewPhoto(id: string): void {
    const path = this.fileControllers.get(this.activeId)?.path(id); if (!path) { return; }
    this.photoSources = this.uploads.photos.map(photo => this.fileControllers.get(this.activeId)?.path(photo.id) ?? '').filter(value => !!value);
    this.photoIndex = Math.max(0, this.photoSources.indexOf(path)); this.nav.push(AppRoute.Photos);
  }
  private async openTeamMember(id: string): Promise<void> {
    const scope = this.activeId; const lead = this.shared.selectedSessionId; const runtime = this.runtime;
    if (!lead || !runtime || !this.teamData.lead.members.some(member => member.id === id && member.canOpen)) { return; }
    this.childHost = scope; this.childLead = lead; this.nav.push(AppRoute.ChildConversation);
    const c = new Command('team-child-open'); c.sessionId = id; await this.action(c);
    if (scope === this.activeId && runtime.client.selectedSessionId !== id && this.nav.current === AppRoute.ChildConversation) { this.back(); }
  }
  private openSidebar(): void { this.drawerOpen = true; }
  private openRename(id: string): void { this.renameId = id; this.renameTitle = this.shared.sessions.find(row => row.id === id)?.title ?? ''; this.renameDialog.open(); }
  private rename(title: string): void { const command = new Command('rename'); command.sessionId = this.renameId; command.title = title; this.action(command); }
  private archive(id: string): void {
    const scope = this.activeId;
    this.confirm('确认归档此会话？', '此操作会在主机上归档会话', '归档', () => {
      if (this.activeId !== scope) { return; }
      const command = new Command('archive'); command.sessionId = id; this.action(command);
    });
  }
  private browse(path?: string): void { this.nav.push(AppRoute.Directory); const command = new Command('directories'); command.path = path; this.action(command); }
  private createWorkspace(path: string): void { const command = new Command('workspace-create'); command.path = path; this.action(command); }
  private retry(): void {
    if (this.startupPhase !== 'ready') { this.initialize(); return; }
    this.errorText = '';
    if (this.nav.current === AppRoute.HostAdd) { return; }
    if (this.nav.current === AppRoute.Directory) { this.browse(this.shared.directory.path || undefined); }
    else if (this.activeId && !this.shared.connected) { this.activate(this.activeId); }
    else { this.action(new Command('refresh')); }
  }
  private async openLink(url: string): Promise<void> {
    if (!this.context) { return; }
    try {
      const target = contentAction('render-link', url);
      if (target.linkKind === 'file') {
        if (!this.shared.selectedSessionId) { throw new Error(this.a.t('请先选择会话')); }
        this.showFiles(); const c = new Command('files-reference'); c.sessionId = this.shared.selectedSessionId; c.path = target.target; this.action(c);
      } else { await openContentLink(this.context, url); }
    }
    catch (error) { this.getUIContext().getPromptAction().showToast({ message: (error as Error).message }); }
  }
  private preview(source: string, table: boolean, heading: string = ''): void { this.previewHeading = heading; this.previewContent = source; this.previewTable = table; this.nav.push(AppRoute.Content); }
  private async exportTable(csv: string): Promise<void> {
    if (!this.context) { return; }
    try { if (await saveCsvContent(this.context, csv)) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('已保存') }); } }
    catch (_) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('文件保存失败') }); }
  }
  @Builder private conversation(child: boolean, search: boolean = false) {
    Row() {
      if (this.wide && !child) {
        SessionSidebar({ a: this.a, scope: this.activeId, workspaceId: this.workspaceId, state: this.shared, sessions: $sessions, query: this.query,
          onBack: () => this.back(), onSearch: value => this.search(value), onSelect: id => this.selectSession(id),
          onRename: id => this.openRename(id), onArchive: id => this.archive(id), onCreate: () => this.action(new Command('create')) }).width(320)
      }
      ConversationPage({ voice: this.voiceData, onVoice: (op: string) => this.voiceControllers.get(this.activeId)?.command(op), onVoiceMode: (enabled: boolean) => this.voiceControllers.get(this.activeId)?.mode(enabled), onVoiceMove: (distance: number) => this.voiceControllers.get(this.activeId)?.move(distance), goalTasks: this.goalTasks, onGoalEdit: () => this.editGoal(), onGoalClear: () => this.clearGoal(), onSearchHandled: () => { this.searchMessage = ''; this.searchMessageSession = ''; }, savedAnchor: this.runtime?.readingAnchors.get(this.shared.selectedSessionId ?? '')?.messageId ?? '', savedInset: this.runtime?.readingAnchors.get(this.shared.selectedSessionId ?? '')?.inset ?? 0,
        searchTarget: this.searchMessageSession === this.shared.selectedSessionId ? this.searchMessage : '',
        onAnchor: (host: string, session: string, message: string, inset: number) => { const runtime = this.runtimes.get(host); if (!runtime) { return; } runtime.readingAnchors.delete(session); if (message) { runtime.readingAnchors.set(session, { messageId: message, inset: inset }); } if (runtime.readingAnchors.size > 64) { const oldest = runtime.readingAnchors.keys().next().value as string; runtime.readingAnchors.delete(oldest); } }, a: this.a, onFiles: () => this.showFiles(), onFile: (file: PresentedFileData) => this.openWorkspaceFile(file), imageAssets: this.imageAssets, onLoadImage: (image: GatewayImageData, retry: boolean) => this.imageCaches.get(this.activeId)?.require(image, retry), onImage: (image: GatewayImageData, images: GatewayImageData[]) => this.previewReceivedPhoto(image, images), uploads: this.uploads, imagePath: id => this.fileControllers.get(this.activeId)?.path(id) ?? '', onDraftPhoto: id => this.previewPhoto(id), preparingFiles: this.preparingFiles, onChooseAttachments: images => { if (this.context) { this.fileControllers.get(this.activeId)?.choose(this.context, images); } }, teamVisible: this.teamData.lead.leadSessionId === this.shared.selectedSessionId && this.teamData.lead.visible, heading: child ? (this.teamData.lead.members.find(member => member.id === this.shared.selectedSessionId)?.name ?? '') : '', features: this.sessionFeatures, slash: this.slashData, onReconnect: () => this.reconnect(), onTeam: () => this.nav.push(AppRoute.Team), onTrajectory: () => this.nav.push(AppRoute.Trajectory), onControls: () => this.nav.push(AppRoute.Controls), controls: this.controlData, trajectory: this.trajectory, state: this.shared, messages: $messages, questions: $questions, approvals: $approvals,
        onLink: url => this.openLink(url), onPreview: (source: string, table: boolean) => this.preview(source, table), onExport: csv => this.exportTable(csv),
        onInbox: () => this.nav.push(AppRoute.Inbox), status: this.status, scope: this.activeId,
        active: this.nav.current === (child ? AppRoute.ChildConversation : search ? AppRoute.SearchConversation : AppRoute.Conversation) && !this.drawerOpen, showSidebarButton: !this.wide && !this.shared.childConversation, draft: this.draft,
        savedScrollY: this.runtime?.scrollY ?? -1, tailRevision: this.tailRevision,
        onOffset: (scope: string, sessionId: string, value: number) => { if (sessionId) { this.runtimes.get(scope)?.scrollPositions.set(sessionId, value); } },
        onBack: () => this.back(), onSidebar: () => this.openSidebar(),
        onDraft: value => this.editedDraft(value),
        onAction: command => this.action(command)
      }).layoutWeight(1)
    }.width('100%').height('100%')
  }
  @Builder private destination(name: string) {
    NavDestination() {
      if (name === AppRoute.Conversation || name === AppRoute.ChildConversation || name === AppRoute.SearchConversation) {
        this.conversation(name === AppRoute.ChildConversation, name === AppRoute.SearchConversation)
      } else if (name === AppRoute.Files) {
        WorkspaceFilesPage({ a: this.a, state: this.shared, data: this.workspaceFiles, onBack: () => this.back(), onAction: command => this.action(command) })
      } else if (name === AppRoute.FilePreview && this.readyFile) {
        FilePreviewPage({ a: this.a, file: this.readyFile!, onBack: () => this.back(), onLink: url => this.openLink(url), onClosed: file => this.workspaceControllers.get(file.hostId)?.release(file.transferId), onAcquire: file => this.workspaceControllers.get(file.hostId)?.acquire(file.transferId) })
      } else if (name === AppRoute.Photos) {
        PhotoPreviewPage({ a: this.a, sources: this.photoSources, initialIndex: this.photoIndex, onBack: () => this.back() })
      } else if (name === AppRoute.Team) {
        TeamPage({ a: this.a, state: this.shared, data: this.teamData, onBack: () => this.back(), onAction: command => this.action(command), onMember: id => this.openTeamMember(id) })
      } else if (name === AppRoute.ScheduleEdit) {
        ScheduleEditorPage({ a: this.a, scope: this.activeId, state: this.shared, data: this.scheduleData, onBack: () => this.back(), onAction: command => this.action(command), onSaved: () => { if (this.nav.current === AppRoute.ScheduleEdit) { this.back(); } } })
      } else if (name === AppRoute.Schedules) {
        SchedulesPage({ a: this.a, state: this.shared, data: this.scheduleData, onBack: () => this.back(), onAction: command => this.action(command), onReconnect: () => this.reconnect(), onEdit: task => this.editSchedule(task), onDelete: task => this.deleteSchedule(task), onSession: id => this.selectSession(id) })
      } else if (name.startsWith('plugins')) {
        PluginsPage({ a: this.a, state: this.shared, data: this.pluginData, section: name === AppRoute.Plugins ? 'catalog' : name.substring('plugins/'.length),
          bundleName: this.pluginBundle, entryId: this.pluginEntry, officialId: this.pluginOfficial, onBack: () => this.back(), onAction: command => this.action(command), onToggle: (command: Command, critical: boolean) => this.pluginToggle(command, critical),
          onBundle: value => { this.pluginBundle = value; this.nav.push(AppRoute.PluginBundle); },
          onEntry: (bundle: string, entry: string) => { this.pluginBundle = bundle; this.pluginEntry = entry; this.nav.push(AppRoute.PluginEntry); },
          onOfficial: value => { this.pluginOfficial = value; this.nav.push(AppRoute.PluginOfficial); } })
      } else if (name === AppRoute.Trajectory) {
        TrajectoryPage({ a: this.a, state: this.shared, data: this.trajectory, active: this.nav.current === AppRoute.Trajectory, standalone: true, onBack: () => this.back(), onAction: command => this.action(command) })
      } else if (name === AppRoute.Controls || name === AppRoute.Defaults) {
        SessionControlPage({ onPresetChange: id => this.defaultAgent(id), a: this.a, features: this.sessionFeatures, state: this.shared, data: this.controlData, isDefault: name === AppRoute.Defaults, onBack: () => this.back(), onAction: command => this.action(command) })
      } else if (name === AppRoute.Content) {
        ContentPreviewPage({ a: this.a, content: this.previewContent, table: this.previewTable, heading: this.previewHeading, onBack: () => this.back(), onLink: url => this.openLink(url), onExport: csv => this.exportTable(csv) })
      } else if (name === AppRoute.Inbox) {
        InteractionPage({ a: this.a, state: this.shared, questions: $questions, approvals: $approvals, onBack: () => this.back(), onAction: command => this.action(command) })
      } else if (name === AppRoute.GoalEdit) {
        GoalEditorPage({ a: this.a, sessionId: this.goalEditSession, goalId: this.goalEditId, revision: this.goalEditRevision, objective: this.goalEditObjective, data: this.goalTasks, connected: this.shared.connected, onBack: () => this.back(), onAction: c => this.action(c), onSaved: () => { if (this.nav.current === AppRoute.GoalEdit) { this.back(); } } })
      } else if (name === AppRoute.OfflineSearch) {
        OfflineSearchPage({ a: this.a, scope: this.activeId, sessionTitle: (id: string) => this.shared.sessions.find(session => session.id === id)?.title || this.a.t('会话'), sessionId: this.offlineScopeSession, initialQuery: this.offlineQuery, onBack: () => this.back(),
          onSearch: (host: string, query: string, session?: string) => this.database ? this.database.search(host, query, session) : Promise.reject(new Error('本地数据库不可用')), onResult: (host: string, row: OfflineSearchRow) => this.offlineResult(host, row) })
      } else if (name === AppRoute.AgentPresets) {
        AgentPresetsPage({ a: this.a, data: this.controlData, connected: this.shared.connected, onBack: () => this.back(), onAction: command => this.action(command), onChoose: id => this.defaultAgent(id) })
      } else if (name === AppRoute.Storage) {
        StoragePage({ a: this.a, hostName: this.hostName(), stats: this.storageStats, loading: this.storageLoading, status: this.storageStatus, onBack: () => this.back(), onRefresh: () => this.refreshStorage(), onClear: drafts => this.clearStorage(drafts), onSearch: () => this.openOfflineSearch() })
      } else if (name === AppRoute.Update) {
        ReleasePage({ a: this.a, version: this.version, active: this.active && this.nav.current === AppRoute.Update, onBack: () => this.back(), onLink: url => this.openLink(url) })
      } else if (name.startsWith('settings')) {
        SettingsPage({ notificationsEnabled: this.notificationsEnabled, notificationStatus: this.notificationStatus, onNotifications: (enabled: boolean) => this.notifications?.setEnabled(enabled), a: this.a, section: name === AppRoute.Settings ? 'root' : name.substring('settings/'.length),
          active: this.active && this.nav.current === name, version: this.version, buildCode: this.buildCode, snapshot: this.settingsData, gatewayVersion: this.shared.connected && this.pluginData.fresh ? this.pluginData.bundles.find(row => row.gateway)?.version || '' : '',
          onLink: url => this.openLink(url), onNotes: () => this.nav.push(AppRoute.ReleaseNotes),
          onAction: command => this.action(command), onConnection: () => { if (this.shared.connected) { this.runtime?.client.disconnect(); } else if (this.activeId) { this.reconnect(); } else { this.showHosts(); } }, onPing: () => this.pingGateway(),
          onAgents: () => this.nav.push(AppRoute.AgentPresets), onSchedules: () => this.nav.push(AppRoute.Schedules), onPlugins: () => this.nav.push(AppRoute.Plugins), onDefaults: () => this.nav.push(AppRoute.Defaults), onBack: () => this.back(), onHosts: () => this.showHosts(),
          onOpenSection: section => this.nav.push(('settings/' + section) as AppRoute),
          onChange: value => this.appearance(value), onForgetAll: () => this.forgetAll() })
      } else if (name.startsWith('hosts')) {
        HostPage({ a: this.a, profiles: this.profiles, selectedId: this.activeId, connected: this.shared.connected, connecting: this.connecting,
          section: name === AppRoute.Hosts ? 'list' : name.substring('hosts/'.length), editing: this.editing,
          onBack: () => this.back(), onSelect: id => this.activate(id), onAdd: () => this.nav.push(AppRoute.HostAdd),
          onEdit: profile => { this.editing = profile; this.nav.push(AppRoute.HostEdit); }, onRemove: id => this.removeHost(id),
          onSave: profile => this.saveHost(profile), onConnect: (endpoint: string, code: string, payload: string) => this.connect(endpoint, code, payload),
          onCancel: () => this.nav.back(), onDisconnect: () => this.runtime?.client.disconnect() })
      } else if (name === AppRoute.Workspaces || name === AppRoute.Directory) {
        WorkspacePicker({ a: this.a, state: this.shared, directoryMode: name === AppRoute.Directory, workspaceId: this.workspaceId,
          onBack: () => this.back(), onSelect: id => { this.workspaceId = id; if (this.runtime) { this.runtime.workspaceId = id; } this.nav.home(); },
          onBrowse: path => this.browse(path), onCreate: path => this.createWorkspace(path) })
      }
    }.hideTitleBar(true).backgroundColor(this.a.bg).expandSafeArea([], [])
      .onShown(() => this.nav.sync()).onBackPressed(() => this.interceptBack())
  }
  build() {
    Column() {
      if (this.errorText) { ErrorBanner({ a: this.a, text: this.errorText, onRetry: () => this.retry() }) }
      if (this.startupPhase !== 'ready') {
        Column({ space: 16 }) {
          if (this.startupPhase === 'loading') { LoadingProgress().width(36).height(36).color(this.a.accent) }
          Text(this.a.t(this.startupPhase === 'loading' ? '加载中' : '错误')).fontColor(this.a.muted).fontSize(this.a.font(15))
        }.width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
      } else {
        Stack() {
          Navigation(this.nav.paths) {
            WorkspacePage({ a: this.a, scope: this.activeId, state: this.shared, sessions: $sessions, workspaceId: this.workspaceId,
              hostName: this.hostName(), status: this.status, wide: this.wide,
              active: this.appForeground && this.nav.current === AppRoute.Home && !this.drawerOpen, query: this.query,
              onInbox: () => this.nav.push(AppRoute.Inbox), onHosts: () => this.showHosts(), onSettings: () => this.nav.push(AppRoute.Settings), onSidebar: () => this.openSidebar(),
              onWorkspace: () => this.nav.push(AppRoute.Workspaces), onCreate: () => this.action(new Command('create')), onSearch: value => this.search(value),
              onSelect: id => this.selectSession(id), onRename: id => this.openRename(id), onArchive: id => this.archive(id), onRefresh: () => this.action(new Command('refresh')) })
          }.mode(NavigationMode.Stack).hideTitleBar(true).hideToolBar(true).navDestination(this.destination)
            .onNavBarStateChange(() => this.nav.sync()).expandSafeArea([], []).width('100%').height('100%')
            .accessibilityLevel(this.drawerOpen ? 'no-hide-descendants' : 'auto')
          if (this.drawerOpen) {
            Row() {
              SessionSidebar({ a: this.a, scope: this.activeId, workspaceId: this.workspaceId, state: this.shared, sessions: $sessions, query: this.query,
                onBack: () => this.drawerOpen = false, onSearch: value => this.search(value), onSelect: id => this.selectSession(id),
                onRename: id => this.openRename(id), onArchive: id => this.archive(id), onCreate: () => { this.drawerOpen = false; this.action(new Command('create')); } })
                .width(Math.min(320, this.viewportWidth * 0.85)).height('100%')
              Column().layoutWeight(1).height('100%').id('sidebar-dismiss').accessibilityText(this.a.t('关闭会话侧栏')).onClick(() => this.drawerOpen = false)
            }.width('100%').height('100%').backgroundColor('#66000000')
          }
        }.width('100%').layoutWeight(1)
      }
    }.width('100%').height('100%').backgroundColor(this.a.bg)
      .onAreaChange((_before: Area, area: Area) => { this.viewportWidth = Number(area.width); this.viewportHeight = Number(area.height); this.resize(); })
  }
}
