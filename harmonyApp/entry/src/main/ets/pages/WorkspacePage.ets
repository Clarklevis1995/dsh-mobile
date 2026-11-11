import { Appearance, iconTint } from '../core/Appearance';
import { SharedState } from '../core/Models';
import { SessionView } from '../components/ViewModels';
import { SessionTile } from '../components/SessionTile';
import { BrandBackground } from '../components/BrandBackground';
import { CircleIcon } from '../components/DesignSystem';

@Component
export struct WorkspacePage {
  @ObjectLink a: Appearance;
  @Prop state: SharedState;
  @Prop scope: string = '';
  @Link sessions: SessionView[];
  @Prop workspaceId: string = '';
  @Prop hostName: string = '';
  @Prop status: string = '';
  @Prop wide: boolean = false;
  @Prop active: boolean = true;
  @Prop query: string = '';
  onInbox: () => void = () => {};
  onHosts: () => void = () => {};
  onSettings: () => void = () => {};
  onSidebar: () => void = () => {};
  onWorkspace: () => void = () => {};
  onCreate: () => void = () => {};
  onSearch: (query: string) => void = () => {};
  onSelect: (id: string) => void = () => {};
  onRename: (id: string) => void = () => {};
  onArchive: (id: string) => void = () => {};
  onRefresh: () => void = () => {};

  private visible(): SessionView[] {
    const workspace = this.state.workspaces.find(row => row.workspaceId === this.workspaceId);
    return this.sessions.filter(row => row.hasConversation && (this.workspaceId === '__all__' || (workspace ? workspace.sessionIds.includes(row.id) : !this.state.workspaces.some(group => group.sessionIds.includes(row.id)))) &&
      (!this.query.trim() || row.title.toLowerCase().includes(this.query.trim().toLowerCase()) || this.state.searchSessionIds.includes(row.id)));
  }
  @Builder private launcher() {
    Column({ space: 0 }) {
      Column({ space: 12 }) {
        Text(this.a.t('探索未至之境')).fontSize(this.a.font(32)).lineHeight(this.a.font(44)).fontWeight(FontWeight.Bold).fontColor('#F1F4FA').width('100%')
        Text(this.a.t('Deepseek Harness 预览版')).fontSize(this.a.font(14)).lineHeight(this.a.font(18)).fontColor('#A6FFFFFF').width('100%')
      }.padding({ top: 67, bottom: 16 }).margin({ bottom: 4 }).width('100%')
      Row({ space: 12 }) {
        Image($rawfile('icons/folder_outline.svg')).width(22).height(22).colorFilter(iconTint('#168BFF'))
        Column({ space: 0 }) {
          Text(this.workspaceId === '__all__' ? this.a.t('全部会话') : this.state.workspaces.find(row => row.workspaceId === this.workspaceId)?.title ?? this.a.t('未分组'))
            .fontSize(this.a.font(14)).lineHeight(this.a.font(20)).fontColor('#FFFFFF').fontWeight(600).maxLines(2).width('100%')
          Text(this.workspaceId === '__all__' ? this.visible().length + (this.a.english ? ' sessions (all workspaces)' : ' 个会话（跨全部工作区）') : this.state.workspaces.find(row => row.workspaceId === this.workspaceId)?.path ?? this.visible().length + (this.a.english ? ' unassigned sessions' : ' 个未归属会话')).fontSize(this.a.font(12)).lineHeight(this.a.font(18)).fontColor('#8CFFFFFF').width('100%')
        }.layoutWeight(1)
        Circle().width(8).height(8).fill(this.state.connected ? '#2EB85C' : '#FF0000')
        Image($rawfile('icons/question_chevron_down.svg')).width(16).height(16).colorFilter(iconTint('#91A6C1'))
      }.padding({ left: 14, right: 14, top: 15, bottom: 15 }).width('100%').borderRadius(15)
        .backgroundColor('#651A365D').backgroundBlurStyle(BlurStyle.COMPONENT_THIN, { colorMode: ThemeColorMode.DARK }).border({ width: 0.8, color: '#4059779C', radius: 15 })
        .id('workspace-picker').onClick(this.onWorkspace).margin({ bottom: 19 })
      Row({ space: 8 }) {
        Stack() { Image($rawfile('icons/add.svg')).width(18).height(18).colorFilter(iconTint('#FFFFFF')) }
          .width(26).height(26).borderRadius(13).backgroundColor('#1AFFFFFF')
        Text(this.a.t('新建会话')).fontSize(this.a.font(17)).fontWeight(600).fontColor('#F1F4FA')
      }.justifyContent(FlexAlign.Center).id('session-create').height(52).width('100%').borderRadius(18)
        .backgroundColor('#604D6381').backgroundBlurStyle(BlurStyle.COMPONENT_THIN, { colorMode: ThemeColorMode.DARK }).border({ width: 0.8, color: '#5563758D', radius: 18 })
        .onClick(() => { if (this.state.connected) { this.onCreate(); } else { this.onHosts(); } })
    }.width('100%')
  }
  @Builder private recent() {
    Column({ space: 8 }) {
      Row() {
        Text(this.a.t('最近会话')).fontSize(this.a.font(17)).fontWeight(FontWeight.Bold).fontColor('#EAF1FC').layoutWeight(1)
        if (this.state.searching || this.state.catalogLoading) { LoadingProgress().width(16).height(16).color('#BFD6FF') }
        Text(this.a.t(this.status === '尚未连接' ? '未连接' : this.status)).id('session-refresh').fontSize(this.a.font(12)).fontColor(this.state.connected ? '#2EB85C' : '#D9FF0000')
          .height(44).constraintSize({ minWidth: 44 }).accessibilityText(this.a.t('刷新')+' · '+this.a.t(this.status)).onClick(this.onRefresh)
      }.width('100%').height(44)
      Row({ space: 8 }) {
        Image($rawfile('icons/search.svg')).width(20).height(20).colorFilter(iconTint('#ADFFFFFF'))
        TextInput({ placeholder: this.a.t('搜索会话内容'), text: this.query }).id('session-search').height('100%').layoutWeight(1).padding(0)
          .fontSize(this.a.font(16)).backgroundColor(Color.Transparent).fontColor('#EBFFFFFF').placeholderColor('#94FFFFFF').onChange(this.onSearch)
      }.height(42).width('100%').padding({ left: 12, right: 12 }).borderRadius(13).backgroundColor('#2EFFFFFF')
        .backgroundBlurStyle(BlurStyle.COMPONENT_THIN, { colorMode: ThemeColorMode.DARK }).border({ width: 0.8, color: '#26FFFFFF', radius: 13 })
      if (this.state.catalogLoading && !this.visible().length) {
        LoadingProgress().width(32).height(32).color('#BFD6FF').margin(24)
      } else if (!this.visible().length) {
        Text(this.query.trim() ? this.a.t('搜索结果为空') : (this.a.english ?
          'No known sessions yet. Connect to a server and create your first task.' : '暂无已知会话。连接服务后创建第一个任务。'))
          .fontSize(this.a.font(14)).fontColor('#8FA2BD').lineHeight(this.a.font(23)).padding(18).width('100%')
          .backgroundColor('#1AFFFFFF').backgroundBlurStyle(BlurStyle.COMPONENT_THIN, { colorMode: ThemeColorMode.DARK }).borderRadius(15).border({ width: 0.8, color: '#1AFFFFFF', radius: 15 })
      }
      ForEach(this.visible(), (row: SessionView) => {
        SessionTile({ a: this.a, row: row, glass: true, connected: this.state.connected, selected: row.id === this.state.selectedSessionId,
          onOpen: this.onSelect, onRename: this.onRename, onArchive: this.onArchive })
      }, (row: SessionView) => this.scope + ':' + row.id)
    }.width('100%')
  }
  build() {
    Stack() {
      BrandBackground({ active: this.active, reduceMotion: this.a.data.reduceMotion })
      Column() {
        Row({ space: 12 }) {
          Image($rawfile('branding/wordmark.svg')).width(this.state.pendingQuestionCount + this.state.pendingApprovalCount > 0 ? 165 : 205).height(44).layoutWeight(this.wide ? 0 : 1).objectFit(ImageFit.Contain)
            .accessibilityText(this.a.t('会话')).id('open-sidebar').onClick(this.onSidebar)
          if (this.wide) { Blank() }
          if (this.state.pendingQuestionCount + this.state.pendingApprovalCount > 0) {
            Button((this.state.pendingQuestionCount + this.state.pendingApprovalCount).toString()).id('interaction-inbox')
              .accessibilityText(this.a.t('待处理')).width(40).height(40).fontColor('#FFAD1F').backgroundColor('#3E3934').onClick(this.onInbox)
          }
          CircleIcon({ icon: 'gateway_auth', diameter: 52, glass: true, fill: '#4D1A365D', controlId: 'home-auth', label: this.a.t('认证'), onPress: this.onHosts })
          CircleIcon({ icon: 'settings', diameter: 52, glass: true, fill: '#4D1A365D', controlId: 'home-settings', label: this.a.t('设置'), onPress: this.onSettings })
        }.width('100%').height(88).padding({ left: 22, right: 22, top: 26 }).constraintSize({ maxWidth: 1440 })
        Scroll() {
          Column({ space: 0 }) {
            Row({ space: 8 }) {
              Image($rawfile('icons/gateway_pc.svg')).width(18).height(18).colorFilter(iconTint('#B8CAE3'))
              Text(this.hostName || this.a.t('选择主机')).fontSize(this.a.font(12)).fontColor('#B8CAE3').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              Circle().width(8).height(8).fill(this.state.connected ? '#2EB85C' : '#FF0000')
              Image($rawfile('icons/question_chevron_down.svg')).width(14).height(14).colorFilter(iconTint('#B8CAE3'))
            }.padding({ left: 14, right: 14 }).height(36).borderRadius(18).backgroundColor('#1AFFFFFF').backgroundBlurStyle(BlurStyle.COMPONENT_THIN, { colorMode: ThemeColorMode.DARK }).id('home-host-picker').onClick(this.onHosts).margin({ bottom: 14 })
            if (this.wide) {
              Row({ space: 32 }) {
                Column() { this.launcher() }.width('38%').constraintSize({ minWidth: 440 })
                Column() { this.recent() }.layoutWeight(1)
              }.alignItems(VerticalAlign.Top).width('100%')
            } else {
              Column() { this.launcher() }.width('100%').margin({ bottom: 8 })
              this.recent()
            }
          }.alignItems(HorizontalAlign.Start).padding({ left: 22, right: 22, bottom: 30 }).width('100%').constraintSize({ maxWidth: 1440 })
        }.layoutWeight(1).width('100%').align(Alignment.Top)
      }.width('100%').height('100%')
    }.width('100%').height('100%').backgroundColor('#06172B')
  }
}
