import { Appearance } from '../core/Appearance';
import { ReleaseChecker, ReleaseCheckState } from '../core/ReleaseChecker';
import { PageHeader } from '../components/DesignSystem';

@Component
export struct ReleasePage {
  @ObjectLink a: Appearance;
  @Prop version: string = '';
  @Prop @Watch('activeChanged') active: boolean = true;
  @State private checkState: ReleaseCheckState = new ReleaseCheckState();
  private checker?: ReleaseChecker;
  onBack: () => void = () => {};
  onLink: (url: string) => void = () => {};
  aboutToAppear(): void { this.checker = new ReleaseChecker(this.checkState); }
  aboutToDisappear(): void { this.checker?.cancel(); }
  private activeChanged(): void { if (!this.active) { this.checker?.cancel(); } }
  private status(): string {
    if (this.checkState.status === 'loading') { return this.a.english ? 'Checking for a HarmonyOS release…' : '正在检查鸿蒙版本…'; }
    if (this.checkState.status === 'available') { return this.a.english ? 'A HarmonyOS package is available: ' + this.checkState.version : '发现鸿蒙安装包：' + this.checkState.version; }
    if (this.checkState.status === 'current') { return this.a.english ? 'Your HarmonyOS version is up to date.' : '当前鸿蒙版本已是最新。'; }
    if (this.checkState.status === 'unsupported') { return this.a.english ? 'The latest release (' + this.checkState.version + ') does not include a HarmonyOS package.' : '最新发布（' + this.checkState.version + '）未提供鸿蒙安装包。'; }
    if (this.checkState.status === 'rate-limited') { return this.a.english ? 'Too many requests. Try again later.' : '检查过于频繁，请稍后重试。'; }
    if (this.checkState.status === 'not-published') { return this.a.english ? 'No public release was found.' : '尚未找到公开发布的版本。'; }
    if (this.checkState.status === 'error') { return this.a.english ? 'Could not check for updates. Please try again later.' : '检查更新失败，请稍后重试。'; }
    return this.a.english ? 'Check the official project for a HarmonyOS release.' : '检查官方项目发布的鸿蒙版本。';
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.english ? 'Check for updates' : '检查更新', onBack: this.onBack })
      Scroll() {
        Column({ space: 20 }) {
          Text('Whaleness Harness').fontSize(this.a.font(23)).fontWeight(FontWeight.Bold).fontColor(this.a.fg).width('100%')
          Text(this.a.t('当前版本') + ' ' + this.version).id('release-current-version').fontSize(this.a.font(15)).fontColor(this.a.fg).width('100%')
          Text(this.status()).id('release-check-status').fontSize(this.a.font(15)).fontColor(this.a.fg).lineHeight(this.a.font(24)).width('100%').accessibilityText(this.status())
          if (this.checkState.busy) { LoadingProgress().id('release-check-loading').width(28).height(28).color(this.a.accent) }
          Button(this.checkState.status === 'idle' ? this.a.english ? 'Check now' : '立即检查' : this.a.english ? 'Check again' : '重新检查')
            .id('release-check').fontSize(this.a.font(15)).height(48).width('100%').enabled(this.active && !this.checkState.busy).onClick(() => this.checker?.check(this.version))
          if (this.checkState.status === 'available') {
            Button(this.a.english ? 'View official release' : '查看官方发布').id('release-open').fontSize(this.a.font(15)).height(48).width('100%').onClick(() => this.onLink(this.checkState.url))
          }
        }.padding(20).width('100%').constraintSize({ maxWidth: 720 })
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.settingsBg)
  }
}
