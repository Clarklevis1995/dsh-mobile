import { Appearance } from '../core/Appearance';
import { OfflineSearchRow } from '../core/LocalDatabase';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct OfflineSearchPage {
  sessionTitle: (id: string) => string = id => id;
  @ObjectLink a: Appearance;
  @Prop @Watch('reset') scope: string = '';
  @Prop sessionId: string = '';
  @Prop initialQuery: string = '';
  @State private query: string = '';
  @State private currentOnly: boolean = false;
  @State private rows: OfflineSearchRow[] = [];
  @State private loading: boolean = false;
  @State private failure: string = '';
  private timer: number = -1;
  private request: number = 0;
  private alive: boolean = false;
  onSearch: (host: string, query: string, session?: string) => Promise<OfflineSearchRow[]> = async () => [];
  onResult: (host: string, row: OfflineSearchRow) => void = () => {};
  onBack: () => void = () => {};
  aboutToAppear(): void { this.alive = true; this.query = this.initialQuery; this.changed(); }
  aboutToDisappear(): void { this.alive = false; this.request++; clearTimeout(this.timer); }
  private reset(): void { this.request++; this.rows = []; this.failure = ''; this.changed(); }
  private changed(): void {
    clearTimeout(this.timer); const request = ++this.request; this.rows = []; this.failure = ''; this.loading = !!this.query.trim();
    if (!this.query.trim()) { return; }
    const owner = this.scope; const query = this.query; const session = this.currentOnly ? this.sessionId : undefined;
    this.timer = setTimeout(async () => {
      try { const rows = await this.onSearch(owner, query, session); if (this.alive && owner === this.scope && request === this.request) { this.rows = rows; } }
      catch (_) { if (this.alive && request === this.request) { this.failure = this.a.t('离线索引读取失败，请检查本地存储'); } }
      finally { if (this.alive && request === this.request) { this.loading = false; } }
    }, 220);
  }
  build() {
    Column({ space: 12 }) {
      PageHeader({ a: this.a, title: this.a.t('离线搜索'), onBack: this.onBack })
      TextInput({ text: this.query, placeholder: this.a.t('搜索已缓存的内容') }).id('offline-search-input').maxLength(256).fontSize(this.a.font(16)).fontColor(this.a.fg).backgroundColor(this.a.surface).height(48).margin({ left: 16, right: 16 }).onChange(value => { if (value !== this.query) { this.query = value; this.changed(); } })
      Row({ space: 12 }) {
        Button(this.a.t('当前主机')).id('offline-search-host').height(44).fontSize(this.a.font(14)).fontColor(!this.currentOnly ? this.a.accent : this.a.muted).backgroundColor(this.a.surface).onClick(() => { this.currentOnly = false; this.changed(); })
        Button(this.a.t('当前会话')).id('offline-search-session').height(44).enabled(!!this.sessionId).fontSize(this.a.font(14)).fontColor(this.currentOnly ? this.a.accent : this.a.muted).backgroundColor(this.a.surface).onClick(() => { this.currentOnly = true; this.changed(); })
      }.padding({ left: 16, right: 16 }).width('100%')
      Text(this.a.t('仅搜索此主机已缓存的稳定内容，最多显示 80 条结果。')).fontSize(this.a.font(12)).fontColor(this.a.muted).padding({ left: 16, right: 16 }).width('100%')
      if (this.loading) { LoadingProgress().width(28).height(28).color(this.a.accent) }
      if (this.failure) { Text(this.failure).fontSize(this.a.font(14)).fontColor(this.a.muted).padding(16) }
      if (!this.loading && !this.failure && this.query.trim() && !this.rows.length) { Text(this.a.t('缓存中没有匹配内容')).id('offline-search-empty').fontSize(this.a.font(15)).fontColor(this.a.muted).padding(20) }
      List({ space: 12 }) {
        ForEach(this.rows, (row: OfflineSearchRow) => {
          ListItem() {
            Button() {
              Column({ space: 8 }) {
                Text(this.sessionTitle(row.sessionId)).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%')
                Text(row.text).fontSize(this.a.font(15)).fontColor(this.a.fg).maxLines(5).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
              }.padding(16).width('100%')
            }.id('offline-result-' + row.messageId).width('100%').type(ButtonType.Normal).backgroundColor(this.a.surface).borderRadius(18).onClick(() => this.onResult(this.scope, row))
          }
        }, (row: OfflineSearchRow) => row.sessionId + ':' + row.messageId)
      }.padding({ left: 16, right: 16, bottom: 16 }).width('100%').layoutWeight(1)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
