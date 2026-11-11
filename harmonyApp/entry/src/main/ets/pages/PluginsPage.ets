import { Appearance } from '../core/Appearance';
import { Command, SharedState, PluginData, PluginBundleData, PluginEntryData, OfficialPluginData, emptyPlugins } from '../core/Models';
import { PageHeader } from '../components/DesignSystem';
import { RemoteSwitch } from '../components/RemoteSwitch';

@Component
export struct PluginsPage {
  @ObjectLink a: Appearance;
  @Prop state: SharedState;
  @Prop data: PluginData = emptyPlugins();
  @Prop section: string = 'catalog';
  @Prop bundleName: string = '';
  @Prop entryId: string = '';
  @Prop officialId: string = '';
  onBack: () => void = () => {};
  onAction: (command: Command) => void = () => {};
  onToggle: (command: Command, critical: boolean) => void = () => {};
  onBundle: (name: string) => void = () => {};
  onEntry: (bundle: string, entry: string) => void = () => {};
  onOfficial: (id: string) => void = () => {};
  aboutToAppear(): void { if (this.section === 'catalog' || !this.data.loaded) { this.refresh(); } }
  private refresh(): void { this.onAction(new Command('plugins-open')); }
  private canChange(): boolean { return this.state.connected && this.data.fresh && this.data.managementAvailable && !this.data.loading && !this.data.pending; }
  private bundle(): PluginBundleData | undefined { return this.data.bundles.find(bundle => bundle.name === this.bundleName); }
  private entry(): PluginEntryData | undefined { return this.bundle()?.entries.find(entry => entry.id === this.entryId || entry.rowId === this.entryId); }
  private phase(entry: PluginEntryData): string { return this.a.t(!entry.enabled ? '已停用' : entry.phase === 'active' ? '运行中' : entry.phase === 'failed' ? '运行失败' : entry.phase === 'loading' ? '加载中' : entry.phase === 'unloading' ? '卸载中' : entry.phase === 'pending' ? '待启动' : '空闲'); }
  private toggle(target: string, id: string, enabled: boolean): void {
    const command = new Command('plugins-toggle'); command.target = target; command.itemId = id; command.value = enabled.toString();
    const critical = this.data.bundles.some(bundle => bundle.gateway && (target === 'bundle' ? bundle.name === id : bundle.entries.some(entry => entry.id === id)));
    this.onToggle(command, critical && !enabled);
  }
  @Builder private field(label: string, value: string) {
    Column({ space: 4 }) { Text(this.a.t(label)).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%'); Text(value).fontSize(this.a.font(14)).fontColor(this.a.fg).copyOption(CopyOptions.LocalDevice).width('100%') }.width('100%')
  }
  @Builder private bundleCard(bundle: PluginBundleData) {
    Row({ space: 12 }) {
      Text('◈').fontSize(28).fontColor(this.a.accent).width(44).height(44).textAlign(TextAlign.Center)
      Column({ space: 5 }) {
        Text(bundle.title).fontSize(this.a.font(16)).fontWeight(FontWeight.Medium).fontColor(this.a.fg).width('100%')
        Text(bundle.description || bundle.name).fontSize(this.a.font(12)).fontColor(this.a.muted).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
        Text(this.a.t(bundle.enabled ? '已开启' : '已停用')).id('plugin-state-' + bundle.name).fontSize(this.a.font(11)).fontColor(this.a.muted).width('100%')
      }.layoutWeight(1).id('plugin-open-' + bundle.name).onClick(() => this.onBundle(bundle.name))
      RemoteSwitch({ a: this.a, checked: bundle.enabled, available: this.canChange() && !bundle.readOnlyReason,
        label: bundle.title, controlId: 'plugin-toggle-' + bundle.name, onChoose: value => this.toggle('bundle', bundle.name, value) })
    }.padding(16).width('100%').borderRadius(22).backgroundColor(this.a.surface)
  }
  @Builder private bundleDetail(bundle: PluginBundleData) {
    Column({ space: 16 }) {
      Row() { Text(bundle.title).fontSize(this.a.font(24)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).layoutWeight(1)
        RemoteSwitch({ a: this.a, checked: bundle.enabled, available: this.canChange() && !bundle.readOnlyReason, label: bundle.title, controlId: 'plugin-detail-toggle', onChoose: value => this.toggle('bundle', bundle.name, value) }) }.width('100%')
      this.field('名称', bundle.name)
      if (bundle.version) { this.field('版本', bundle.version) }
      if (bundle.description) { this.field('说明', bundle.description) }
      if (bundle.readOnlyReason) { Text(bundle.readOnlyReason).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%') }
      if (bundle.error) { Text(bundle.error).fontSize(this.a.font(13)).fontColor('#D65D64').width('100%') }
      Text(this.a.t('包含的组件') + ' · ' + bundle.entries.length).fontSize(this.a.font(17)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).width('100%')
      ForEach(bundle.entries, (entry: PluginEntryData) => {
        Row({ space: 8 }) {
          Column({ space: 5 }) { Text(entry.name).fontSize(this.a.font(15)).fontColor(this.a.fg).width('100%'); Text('● ' + this.phase(entry)).fontSize(this.a.font(12)).fontColor(entry.enabled && entry.phase === 'active' ? '#34C759' : this.a.muted).width('100%') }
            .layoutWeight(1).id('plugin-entry-open-' + entry.rowId).onClick(() => this.onEntry(bundle.name, entry.id || entry.rowId))
          RemoteSwitch({ a: this.a, checked: entry.enabled, available: this.canChange() && !!entry.id && !entry.readOnlyReason,
            label: entry.name, controlId: 'plugin-entry-toggle-' + entry.rowId, onChoose: value => this.toggle('entry', entry.id, value) })
        }.padding(14).width('100%').borderRadius(18).backgroundColor(this.a.surface)
      }, (entry: PluginEntryData) => JSON.stringify(entry))
    }.width('100%')
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t(this.section === 'catalog' ? '插件' : this.section === 'entry' ? '组件详情' : '插件详情'), onBack: this.onBack })
      Scroll() {
        Column({ space: 18 }) {
          if (!this.state.connected) { Text(this.a.t('网关已断开，状态待确认')).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%') }
          if (this.data.error) { Text(this.data.error).id('plugins-error').fontSize(this.a.font(14)).fontColor('#D65D64').width('100%') }
          if (this.data.notice) { Text(this.data.notice).id('plugins-notice').fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%') }
          if (this.data.loading || this.data.pending) { LoadingProgress().id('plugins-loading').width(26).height(26).color(this.a.accent) }
          if (this.section === 'catalog') {
            Text(this.a.t('已安装')).fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%')
            ForEach(this.data.bundles.filter(bundle => !bundle.official), (bundle: PluginBundleData) => { this.bundleCard(bundle) }, (bundle: PluginBundleData) => JSON.stringify(bundle))
            if (this.data.loaded && !this.data.bundles.length) { Text(this.a.t('暂无插件')).fontSize(this.a.font(14)).fontColor(this.a.muted) }
            if (this.data.managementAvailable) {
              Text(this.a.t('官方')).fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%')
              ForEach(this.data.bundles.filter(bundle => bundle.official), (bundle: PluginBundleData) => { this.bundleCard(bundle) }, (bundle: PluginBundleData) => JSON.stringify(bundle))
              ForEach(this.data.officialItems, (item: OfficialPluginData) => {
                Button() { Column({ space: 5 }) { Text(this.a.t(item.title)).fontSize(this.a.font(16)).fontColor(this.a.fg).width('100%'); Text(this.a.t(item.description)).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%') }.padding(16).width('100%') }
                  .id('plugin-official-' + item.id).backgroundColor(this.a.surface).borderRadius(22).width('100%').onClick(() => this.onOfficial(item.id))
              }, (item: OfficialPluginData) => JSON.stringify(item))
            }
          } else if (this.section === 'bundle') {
            ForEach(this.data.bundles.filter(bundle => bundle.name === this.bundleName), (bundle: PluginBundleData) => { this.bundleDetail(bundle) }, (bundle: PluginBundleData) => JSON.stringify(bundle))
          } else if (this.section === 'entry' && this.entry()) {
            ForEach([this.entry()!], (entry: PluginEntryData) => {
              Text(entry.name).fontSize(this.a.font(24)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).width('100%')
              this.field('标识', entry.id || entry.rowId); this.field('模块', entry.module); this.field('状态', this.phase(entry)); this.field('说明', entry.description)
              if (entry.readOnlyReason) { Text(entry.readOnlyReason).fontSize(this.a.font(14)).fontColor(this.a.muted) }
            }, (entry: PluginEntryData) => JSON.stringify(entry))
          } else if (this.section === 'official') {
            ForEach(this.data.officialItems.filter(item => item.id === this.officialId), (item: OfficialPluginData) => {
              Text(this.a.t(item.title)).fontSize(this.a.font(24)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).width('100%')
              Text(this.a.t(item.description)).fontSize(this.a.font(15)).fontColor(this.a.muted).width('100%')
              Text(this.a.t('配置项')).fontSize(this.a.font(17)).fontColor(this.a.fg).width('100%')
              ForEach(item.namespaces, (namespace: string) => { Text(namespace).fontFamily('monospace').fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%').copyOption(CopyOptions.LocalDevice) }, (namespace: string) => namespace)
            }, (item: OfficialPluginData) => JSON.stringify(item))
          }
          Button(this.a.t('刷新')).id('plugins-refresh').height(48).width('100%').enabled(this.state.connected && !this.data.loading).onClick(() => this.refresh())
        }.padding(20).width('100%').constraintSize({ maxWidth: 760 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
