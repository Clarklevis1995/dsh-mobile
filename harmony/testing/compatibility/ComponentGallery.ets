import { ConfirmDialog } from '../components/ConfirmDialog';
import { RenameDialog } from '../components/RenameDialog';
import { common, ConfigurationConstant } from '@kit.AbilityKit';
import { Appearance } from '../core/Appearance';
import { AppearanceData } from '../core/Models';
import { SessionView } from '../components/ViewModels';
import { CircleIcon, ErrorBanner, PageHeader } from '../components/DesignSystem';
import { SessionTile } from '../components/SessionTile';
import { PreferenceMenuRow } from '../components/PreferenceMenuRow';

@Entry
@Component
struct ComponentGallery {
  @State private a: Appearance = new Appearance();
  @State private row: SessionView = new SessionView({ id: 'preview-only', title: '组件预览会话', running: true, cwd: '/demo/workspace' });
  private rename: CustomDialogController = new CustomDialogController({
    builder: RenameDialog({ a: this.a, initial: '组件预览会话', onSave: (_value: string) => {} }),
    autoCancel: true, alignment: DialogAlignment.Center
  });
  aboutToDisappear(): void { this.rename.close(); this.confirmation.close(); }
  private confirmation: CustomDialogController = new CustomDialogController({
    builder: ConfirmDialog({ a: this.a, title: '组件预览确认', message: '仅预览，不执行真实归档或移除', confirmLabel: '确认' }),
    autoCancel: true, alignment: DialogAlignment.Center
  });
  private change(dark: boolean, large: boolean): void {
    const next = new AppearanceData(); next.style = dark ? 'dark' : 'light'; next.fontFollowsSystem = false; next.fontScale = large ? 1.5 : 1;
    this.a.data = next;
    const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
    context.getApplicationContext().setColorMode(dark ? ConfigurationConstant.ColorMode.COLOR_MODE_DARK : ConfigurationConstant.ColorMode.COLOR_MODE_LIGHT);
  }
  aboutToAppear(): void { this.change(false, false); }
  build() {
    Column() {
      PageHeader({ a: this.a, title: '组件预览' })
      Row({ space: 8 }) {
        Button('明／暗').fontSize('13vp').layoutWeight(1).id('gallery-theme').onClick(() => this.change(!this.a.dark, this.a.data.fontScale > 1))
        Button('100／150%').fontSize('13vp').layoutWeight(1).id('gallery-font').onClick(() => this.change(this.a.dark, this.a.data.fontScale === 1))
        Button('系统150').fontSize('13vp').layoutWeight(1).id('gallery-system-font').onClick(() => {
          const next = new AppearanceData(); next.style = this.a.dark ? 'dark' : 'light'; next.fontFollowsSystem = true; this.a.data = next;
          const context = this.getUIContext().getHostContext() as common.UIAbilityContext; context.getApplicationContext().setFontSizeScale(1.5);
        })
      }.padding(12)
      Row({ space: 12 }) {
        Button('输入弹层').id('gallery-rename').onClick(() => this.rename.open())
        Button('确认弹层').id('gallery-confirm').onClick(() => this.confirmation.open())
      }.padding(8)
      Scroll() {
        Column({ space: 16 }) {
          Text('仅组件预览 · 无网络和真实操作').fontSize(this.a.font(13)).fontColor(this.a.muted)
          Text('Into the Unknown').fontSize(this.a.font(24)).fontWeight(FontWeight.Bold).fontColor(this.a.fg)
          Row({ space: 12 }) {
            CircleIcon({ icon: 'settings', label: '设置', ink: this.a.fg, fill: this.a.surface })
            CircleIcon({ icon: 'add', label: '禁用', available: false, ink: this.a.fg, fill: this.a.surface })
            LoadingProgress().width(32).height(32).color(this.a.accent)
          }
          SessionTile({ a: this.a, row: this.row, connected: true })
          PreferenceMenuRow({ a: this.a, kind: 'style', onChoose: value => this.change(value === 'dark', this.a.data.fontScale > 1) })
          ErrorBanner({ a: this.a, text: '连接暂不可用，请检查网络后重试' })
          TextInput({ placeholder: '搜索／输入预览' }).height(48).fontSize(this.a.font(16)).fontColor(this.a.fg).backgroundColor(this.a.surface)
          Text('圆角、文字、卡片、菜单、加载、禁用与错误状态').fontSize(this.a.font(14)).fontColor(this.a.muted)
        }.padding(20).width('100%')
      }.layoutWeight(1).width('100%').align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.bg)
  }
}
