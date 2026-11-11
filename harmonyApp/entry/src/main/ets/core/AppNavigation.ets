export enum AppRoute {
  Home = 'home',
  Conversation = 'conversation',
  Inbox = 'inbox',
  Team = 'team',
  ChildConversation = 'team/conversation',
  Schedules = 'schedules',
  ScheduleEdit = 'schedules/edit',
  Plugins = 'plugins',
  PluginBundle = 'plugins/bundle',
  PluginEntry = 'plugins/entry',
  PluginOfficial = 'plugins/official',
  Content = 'content',
  Photos = 'photos',
  Files = 'workspace-files',
  FilePreview = 'file-preview',
  GoalEdit = 'goal/edit',
  Controls = 'session-controls',
  Trajectory = 'conversation/trajectory',
  Defaults = 'default-controls',
  AgentPresets = 'settings/agent-presets',
  Storage = 'settings/storage',
  OfflineSearch = 'offline-search',
  SearchConversation = 'offline-search/conversation',
  Settings = 'settings',
  Font = 'settings/font',
  Privacy = 'settings/privacy',
  About = 'settings/about',
  Update = 'settings/updates',
  ReleaseNotes = 'settings/notes',
  Hosts = 'hosts',
  HostAdd = 'hosts/add',
  HostEdit = 'hosts/edit',
  Workspaces = 'workspaces',
  Directory = 'directory'
}

/** 只保存导航状态；会话、草稿和凭据仍归属 HostRuntime／HostRepository。 */
@Observed
export class AppNavigation {
  readonly paths: NavPathStack = new NavPathStack();
  current: string = AppRoute.Home;
  transitioning: boolean = false;
  private previous: string[] = [];
  onRemoved: (route: string) => void = () => {};
  onChanged: () => void = () => {};

  constructor() {
    // 系统侧滑真正完成后同步；取消侧滑不会移除页面，也不会取消在途认证。
    this.paths.setInterception({ willShow: () => { this.transitioning = true; }, didShow: () => { this.transitioning = false; this.sync(); } });
  }
  sync(): void {
    const names = this.paths.getAllPathName();
    const removed = this.previous.filter(name => !names.includes(name));
    this.previous = names.slice();
    this.current = names.length ? names[names.length - 1] : AppRoute.Home;
    removed.forEach(name => this.onRemoved(name));
    this.onChanged();
  }
  push(route: AppRoute): void {
    if (this.current === route) { return; }
    this.paths.pushPath({ name: route }); this.sync();
  }
  back(): boolean {
    if (!this.paths.size()) { return false; }
    this.paths.pop(); this.sync(); return true;
  }
  home(animated: boolean = true): void {
    if (this.paths.size()) { this.paths.clear(animated); }
    this.sync();
  }
  conversation(): void {
    if (this.current === AppRoute.Conversation) { return; }
    if (this.paths.getAllPathName().includes(AppRoute.Conversation)) {
      this.paths.popToName(AppRoute.Conversation);
    } else { this.paths.pushPath({ name: AppRoute.Conversation }); }
    this.sync();
  }
}
