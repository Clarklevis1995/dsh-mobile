import { Appearance } from '../core/Appearance';
import { StorageStats } from '../core/LocalDatabase';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct StoragePage {
  @ObjectLink a: Appearance;
  @Prop hostName: string = '';
  @Prop stats: StorageStats = { windows: 0, historyBytes: 0, drafts: 0, draftBytes: 0 };
  @Prop loading: boolean = false;
  @Prop status: string = '';
  onBack: () => void = () => {};
  onRefresh: () => void = () => {};
  onClear: (drafts: boolean) => void = () => {};
  onSearch: () => void = () => {};
  aboutToAppear(): void { this.onRefresh(); }
  private bytes(value: number): string { return value < 1048576 ? Math.ceil(value / 1024) + ' KiB' : (value / 1048576).toFixed(1) + ' MiB'; }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t('存储空间'), onBack: this.onBack })
      Scroll() {
        Column({ space: 18 }) {
          Text(this.hostName || this.a.t('尚未选择主机')).fontSize(this.a.font(19)).fontColor(this.a.fg).fontWeight(FontWeight.Bold).width('100%')
          Column({ space: 14 }) {
            Text(this.a.t('已缓存会话') + ' · ' + this.stats.windows).id('storage-window-count').fontSize(this.a.font(16)).fontColor(this.a.fg).width('100%')
            Text(this.a.t('历史与搜索索引') + ' · ' + this.bytes(this.stats.historyBytes * 2)).id('storage-history-bytes').fontSize(this.a.font(15)).fontColor(this.a.muted).width('100%')
            Text(this.a.t('文字草稿') + ' · ' + this.stats.drafts + ' · ' + this.bytes(this.stats.draftBytes)).id('storage-draft-count').fontSize(this.a.font(16)).fontColor(this.a.fg).width('100%')
            Text(this.a.t('历史与索引采用估算的 128 MiB 全局预算，最近使用的会话优先保留。缓存不代表完整历史。')).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')
          }.padding(20).backgroundColor(this.a.surface).borderRadius(22).width('100%')
          Button(this.a.t('离线搜索')).id('storage-search').height(48).width('100%').fontSize(this.a.font(15)).fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(this.onSearch)
          Button(this.a.t('刷新统计')).id('storage-refresh').height(48).width('100%').enabled(!this.loading).fontSize(this.a.font(15)).fontColor(this.a.fg).backgroundColor(this.a.surface).onClick(this.onRefresh)
          Button(this.a.t('清理历史缓存')).id('storage-clear-history').height(48).width('100%').enabled(!this.loading).fontSize(this.a.font(15)).fontColor(this.a.fg).backgroundColor(this.a.surface).onClick(() => this.onClear(false))
          Button(this.a.t('清理历史与文字草稿')).id('storage-clear-drafts').height(48).width('100%').enabled(!this.loading).fontSize(this.a.font(15)).fontColor('#D34C53').backgroundColor(this.a.surface).onClick(() => this.onClear(true))
          Text(this.a.t('清理只影响当前主机的本地历史；正在上传和预览的临时文件继续由各自任务管理。登录信息保留。')).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')
          if (this.status) { Text(this.status).id('storage-status').fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%') }
          if (this.loading) { LoadingProgress().width(28).height(28).color(this.a.accent) }
        }.padding(20).width('100%').constraintSize({ maxWidth: 720 })
      }.width('100%').layoutWeight(1).align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
