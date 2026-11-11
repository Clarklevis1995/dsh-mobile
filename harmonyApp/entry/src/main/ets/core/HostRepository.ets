import { util } from '@kit.ArkTS';
import { Engine } from 'libwhaleness_bridge.so';
import { AppearanceData, Command, GatewayConnectionInfo, GatewayProfileRecord, SharedResult } from './Models';
import { Credential, CredentialStore } from './CredentialStore';
import { ClientPreferences, defaultPreferences, PreferenceStore } from './PreferenceStore';

/** 每次先持久化候选文档，再发布快照，失败时保留原资料供重试。 */
export class HostRepository {
  private engine: Engine = new Engine();
  private document: ClientPreferences = defaultPreferences();
  private writes: Promise<void> = Promise.resolve();
  private storage: PreferenceStore;
  constructor(storage: PreferenceStore) { this.storage = storage; }
  dispose(): void { this.engine.close(); }
  get profiles(): GatewayProfileRecord[] { return this.document.profiles.slice(); }
  get selectedId(): string { return this.document.selectedId; }
  get appearance(): AppearanceData { return this.document.appearance; }

  private validate(profile: GatewayProfileRecord): GatewayProfileRecord {
    const command = new Command('validate-profile'); command.profile = profile;
    const result = JSON.parse(this.engine.dispatch(JSON.stringify(command))) as SharedResult;
    if (!result.ok || !result.profile) { throw new Error(result.error ?? '主机资料格式无效'); }
    const value = result.profile;
    // 显式取字段，避免旧格式中的凭据或设备 ID 混入普通偏好。
    const clean = new GatewayProfileRecord();
    clean.localId = value.localId; clean.gatewayId = value.gatewayId; clean.gatewayName = value.gatewayName;
    clean.alias = value.alias; clean.endpoints = value.endpoints; clean.preferredEndpoint = value.preferredEndpoint;
    clean.lastConnectedAt = value.lastConnectedAt; clean.server = value.server;
    return clean;
  }

  async load(): Promise<void> {
    const data = await this.storage.read();
    data.profiles = data.profiles.map(profile => this.validate(profile));
    if (!data.profiles.some(profile => profile.localId === data.selectedId)) { data.selectedId = data.profiles[0]?.localId ?? ''; }
    const settings = data.appearance ?? new AppearanceData();
    settings.style = ['system', 'light', 'dark'].includes(settings.style) ? settings.style : 'system';
    settings.language = ['system', 'zh', 'en'].includes(settings.language) ? settings.language : 'system';
    settings.fontScale = Number.isFinite(settings.fontScale) ? Math.max(0.8, Math.min(1.5, settings.fontScale)) : 1;
    data.appearance = settings;
    this.document = data;
    if (data.legacyMigrated) { return; }
    const legacy = await new CredentialStore().load();
    if (legacy?.token && legacy.deviceId && legacy.endpoint) {
      const profile = new GatewayProfileRecord();
      profile.localId = 'legacy-host'; profile.gatewayId = legacy.gatewayId;
      profile.gatewayName = legacy.gatewayName || '已保存的主机'; profile.endpoints = legacy.endpoints?.length ? legacy.endpoints : [legacy.endpoint];
      profile.preferredEndpoint = legacy.endpoint;
      const normalized = this.validate(profile);
      // 固定迁移 ID 使中断重试幂等；新 Asset 与偏好都写好后才清理旧 Asset。
      await new CredentialStore(normalized.localId).save(legacy);
      const migrated = this.copy();
      migrated.profiles = migrated.profiles.filter(row => row.localId !== normalized.localId).concat([normalized]);
      migrated.selectedId = migrated.selectedId || normalized.localId;
      migrated.legacyMigrated = true;
      await this.storage.write(migrated); this.document = migrated;
      await new CredentialStore().clear();
    } else {
      const migrated = this.copy(); migrated.legacyMigrated = true;
      await this.storage.write(migrated); this.document = migrated;
    }
  }

  private copy(): ClientPreferences { return JSON.parse(JSON.stringify(this.document)) as ClientPreferences; }
  private change(action: (draft: ClientPreferences) => void): Promise<void> {
    const work = this.writes.then(async (): Promise<void> => {
      const next = this.copy(); action(next);
      await this.storage.write(next); this.document = next;
    });
    this.writes = work.catch(() => {}); return work;
  }

  async connected(id: string, info: GatewayConnectionInfo): Promise<void> {
    await this.change(next => {
      const previous = next.profiles.find(row => row.localId === id);
      const profile = new GatewayProfileRecord(); profile.localId = id;
      profile.gatewayId = info.gatewayId; profile.gatewayName = info.gatewayName;
      profile.alias = previous?.alias; profile.server = previous?.server ?? false;
      profile.endpoints = info.endpoints; profile.preferredEndpoint = info.endpoint; profile.lastConnectedAt = Date.now();
      const validated = this.validate(profile);
      next.profiles = next.profiles.filter(row => row.localId !== id).concat([validated]);
      if (!next.selectedId) { next.selectedId = id; }
    });
  }

  async select(id: string): Promise<void> {
    await this.change(next => { if (!next.profiles.some(row => row.localId === id)) { throw new Error('主机不存在'); } next.selectedId = id; });
  }
  async edit(profile: GatewayProfileRecord): Promise<void> {
    const normalized = this.validate(profile);
    await this.change(next => {
      const previous = next.profiles.find(row => row.localId === normalized.localId);
      if (!previous || previous.gatewayId !== normalized.gatewayId) { throw new Error('编辑不能替换主机身份'); }
      next.profiles = next.profiles.map(row => row.localId === normalized.localId ? normalized : row);
    });
  }
  async remove(id: string): Promise<void> {
    // 先去掉元信息；即使随后 Asset 删除失败，也不会意外重新显示或自动连接旧主机。
    await this.change(next => { next.profiles = next.profiles.filter(row => row.localId !== id);
      if (next.selectedId === id) { next.selectedId = next.profiles[0]?.localId ?? ''; } });
    await new CredentialStore(id).clear();
  }
  async forgetAll(): Promise<void> {
    const ids = this.profiles.map(row => row.localId);
    await this.change(next => { next.profiles = []; next.selectedId = ''; next.legacyMigrated = true; });
    for (const id of ids) { await new CredentialStore(id).clear(); }
    await new CredentialStore().clear();
  }
  async saveAppearance(value: AppearanceData): Promise<void> { await this.change(next => { next.appearance = value; }); }
  newId(): string { return util.generateRandomUUID(); }
}
