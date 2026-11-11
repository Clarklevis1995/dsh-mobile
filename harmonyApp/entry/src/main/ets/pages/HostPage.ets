import { scanBarcode, scanCore } from '@kit.ScanKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { Engine } from 'libwhaleness_bridge.so';
import { Appearance, iconTint } from '../core/Appearance';
import { GatewayProfileRecord, Command, SharedResult } from '../core/Models';
import { CircleIcon, PageHeader } from '../components/DesignSystem';

@Component
export struct HostPage {
  @ObjectLink a: Appearance;
  @Prop profiles: GatewayProfileRecord[] = [];
  @Prop selectedId: string = '';
  @Prop connected: boolean = false;
  @Prop connecting: boolean = false;
  @Prop @Watch('syncEditor') editing: GatewayProfileRecord = new GatewayProfileRecord();
  @Prop section: string = 'list';
  onAdd: () => void = () => {};
  @State private scanning: boolean = false;
  private scanTicket: number = 0;
  private alive: boolean = false;
  @State private endpoint: string = '';
  @State private payload: string = '';
  @State private code: string = '';
  @State private alias: string = '';
  @State private addresses: string = '';
  @State private server: boolean = false;
  onBack: () => void = () => {};
  onSelect: (id: string) => void = () => {};
  onEdit: (profile: GatewayProfileRecord) => void = () => {};
  onRemove: (id: string) => void = () => {};
  onSave: (profile: GatewayProfileRecord) => void = () => {};
  onConnect: (endpoint: string, code: string, payload: string) => void = () => {};
  onCancel: () => void = () => {};
  onDisconnect: () => void = () => {};
  aboutToAppear(): void { this.alive = true; this.syncEditor(); }
  aboutToDisappear(): void { this.alive = false; this.scanTicket++; }
  private async scan(): Promise<void> {
    if (this.scanning || this.connecting) { return; }
    if (!canIUse('SystemCapability.Multimedia.Scan.ScanBarcode')) { this.getUIContext().getPromptAction().showToast({ message: this.a.t('系统扫码不可用，请粘贴配对内容') }); return; }
    const ticket = ++this.scanTicket; this.scanning = true;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const result = await scanBarcode.startScanForResult(context, { scanTypes: [scanCore.ScanType.QR_CODE], enableMultiMode: false });
      if (!this.alive || ticket !== this.scanTicket) { return; }
      if (!result.originalValue || result.originalValue.length > 16000) { throw new Error(this.a.t('二维码内容无效')); }
      // 新建无网络传输的验证器，复用配对格式、过期和身份规则；校验后才交给当前认证流程。
      const command = new Command('prepare'); command.input = result.originalValue;
      const validator = new Engine(); let validated: SharedResult;
      try { validated = JSON.parse(validator.dispatch(JSON.stringify(command))) as SharedResult; } finally { validator.close(); }
      if (!validated.ok || !validated.pairing) { throw new Error(validated.error ?? this.a.t('二维码内容无效')); }
      this.code = ''; this.payload = ''; this.onConnect('', '', result.originalValue);
    } catch (error) {
      if (this.alive && ticket === this.scanTicket && (error as BusinessError).code !== 1000500002) {
        const message = error instanceof Error && !(error as BusinessError).code ? error.message : this.a.t('系统扫码不可用，请粘贴配对内容');
        this.getUIContext().getPromptAction().showToast({ message });
      }
    } finally { if (ticket === this.scanTicket) { this.scanning = false; } }
  }
  private syncEditor(): void {
    this.alias = this.editing?.alias ?? ''; this.addresses = this.editing?.endpoints.join('\n') ?? '';
    this.server = this.editing?.server ?? false;
  }
  private sorted(): GatewayProfileRecord[] {
    return this.profiles.slice().sort((a: GatewayProfileRecord, b: GatewayProfileRecord) => a.localId === this.selectedId ? -1 : b.localId === this.selectedId ? 1 : (b.lastConnectedAt ?? 0) - (a.lastConnectedAt ?? 0));
  }
  private save(): void {
    if (!this.editing) { return; }
    const value = new GatewayProfileRecord(); value.localId = this.editing.localId; value.gatewayId = this.editing.gatewayId;
    value.gatewayName = this.editing.gatewayName; value.alias = this.alias; value.server = this.server;
    value.lastConnectedAt = this.editing.lastConnectedAt;
    value.endpoints = this.addresses.split('\n').map(item => item.trim()).filter(item => item.length > 0);
    value.preferredEndpoint = value.endpoints.includes(this.editing.preferredEndpoint ?? '') ? this.editing.preferredEndpoint : value.endpoints[0];
    this.onSave(value);
  }
  build() {
    Column() {
      PageHeader({ a: this.a, title: this.a.t(this.section === 'add' ? '授权配对' : this.section === 'edit' ? '编辑' : '主机管理'), onBack: this.onBack })
      Scroll() {
        Column({ space: 16 }) {
          if (this.section === 'list') {
            Text(this.a.t('已保存主机')).fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')
            if (!this.profiles.length) { Text(this.a.t('无已保存主机')).fontSize(this.a.font(16)).fontColor(this.a.muted).padding(32) }
            ForEach(this.sorted(), (profile: GatewayProfileRecord) => {
              Column({ space: 12 }) {
                Row() {
                  Column({ space: 6 }) {
                    Text(profile.alias || profile.gatewayName).fontSize(this.a.font(17)).fontColor(this.a.fg).fontWeight(FontWeight.Medium).width('100%')
                    Text(profile.preferredEndpoint || profile.endpoints[0]).fontSize(this.a.font(12)).fontColor(this.a.muted).maxLines(2).width('100%')
                    Text(this.a.t(profile.localId === this.selectedId ? (this.connected ? '已连接' : '当前主机') : '已保存主机'))
                      .fontSize(this.a.font(12)).fontColor(profile.localId === this.selectedId && this.connected ? '#2EB85C' : this.a.muted).width('100%')
                  }.layoutWeight(1).id('host-select-' + profile.localId).onClick(() => this.onSelect(profile.localId))
                  CircleIcon({ icon: 'session_rename', controlId: 'host-edit-' + profile.localId, label: this.a.t('编辑'), ink: this.a.accent, fill: this.a.bg, onPress: () => this.onEdit(profile) })
                }.width('100%')
                Row({ space: 16 }) {
                  Button(this.a.t('连接')).fontSize(this.a.font(16)).id('host-connect-' + profile.localId).fontColor(this.a.accent).backgroundColor(this.a.bg).onClick(() => this.onSelect(profile.localId))
                  Button(this.a.t('移除')).fontSize(this.a.font(16)).id('host-remove-' + profile.localId).fontColor('#D34C53').backgroundColor(Color.Transparent).onClick(() => this.onRemove(profile.localId))
                }.width('100%')
              }.padding(18).borderRadius(24).backgroundColor(this.a.surface).width('100%')
            }, (profile: GatewayProfileRecord) => profile.localId + ':' + (profile.lastConnectedAt ?? 0) + ':' + (profile.alias ?? '') + ':' + (profile.preferredEndpoint ?? ''))
            Button(this.a.t('添加主机')).fontSize(this.a.font(16)).id('host-add').height(48).width('100%').onClick(this.onAdd)
            if (this.connected) { Button(this.a.t('断开连接')).fontSize(this.a.font(16)).fontColor('#D34C53').backgroundColor(this.a.surface).height(48).width('100%').onClick(this.onDisconnect) }
          } else if (this.section === 'edit') {
            Text(this.editing?.gatewayName ?? '').fontSize(this.a.font(18)).fontColor(this.a.fg).width('100%')
            TextInput({ placeholder: this.a.t('别名'), text: this.alias }).id('host-alias').maxLength(128).height(50).backgroundColor(this.a.surface).fontColor(this.a.fg).onChange(value => this.alias = value)
            TextArea({ placeholder: this.a.t('候选地址（每行一个）'), text: this.addresses }).id('host-addresses').height(150).maxLength(16384).backgroundColor(this.a.surface).fontColor(this.a.fg).onChange(value => this.addresses = value)
            Row() {
              Text(this.a.t('服务器')).fontSize(this.a.font(16)).fontColor(this.a.fg).layoutWeight(1)
              Toggle({ type: ToggleType.Switch, isOn: this.server }).onChange(value => this.server = value)
            }.padding(16).backgroundColor(this.a.surface).borderRadius(20).width('100%')
            Button(this.a.t('保存')).fontSize(this.a.font(16)).id('host-save').width('100%').height(48).onClick(() => this.save())
          } else {
            Text(this.a.t('粘贴配对内容，或填写地址及配对码')).fontSize(this.a.font(14)).fontColor(this.a.muted).width('100%')
            Button(this.a.t(this.scanning ? '正在扫码' : '扫码配对')).id('gateway-scan').height(48).width('100%').enabled(!this.connecting && !this.scanning).onClick(() => this.scan())
            TextArea({ placeholder: this.a.t('配对内容'), text: this.payload }).id('gateway-payload').height(118).maxLength(16000)
              .backgroundColor(this.a.surface).fontColor(this.a.fg).onChange(value => this.payload = value)
            TextInput({ placeholder: 'ws://192.168.1.10:3080/ws/mobile', text: this.endpoint }).id('gateway-endpoint').height(52)
              .backgroundColor(this.a.surface).fontColor(this.a.fg).onChange(value => this.endpoint = value)
            TextInput({ placeholder: this.a.t('一次性配对码'), text: this.code }).id('gateway-pairing-code').type(InputType.Password).height(52)
              .backgroundColor(this.a.surface).fontColor(this.a.fg).onChange(value => this.code = value)
            Button(this.a.t(this.connecting ? '正在连接主机' : '连接')).fontSize(this.a.font(16)).id('gateway-connect').width('100%').height(48).enabled(!this.connecting)
              .onClick(() => { this.onConnect(this.endpoint, this.code, this.payload); this.code = ''; this.payload = ''; })
            Button(this.a.t('取消')).fontSize(this.a.font(16)).id('gateway-cancel').width('100%').height(48).backgroundColor(this.a.surface).fontColor(this.a.fg).onClick(this.onCancel)
          }
        }.padding(20).width('100%').constraintSize({ maxWidth: 720 })
      }.width('100%').layoutWeight(1).align(Alignment.Top)
    }.width('100%').height('100%').backgroundColor(this.a.bg)
  }
}
