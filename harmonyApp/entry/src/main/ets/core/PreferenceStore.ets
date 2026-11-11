import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { AppearanceData, GatewayProfileRecord } from './Models';

export interface ClientPreferences {
  version: number;
  profiles: GatewayProfileRecord[];
  selectedId: string;
  appearance: AppearanceData;
  legacyMigrated: boolean;
}

export function defaultPreferences(): ClientPreferences {
  return { version: 1, profiles: [], selectedId: '', appearance: new AppearanceData(), legacyMigrated: false };
}

/** 普通偏好只保存主机元信息和外观；Token、设备 ID 仅允许出现在 Asset。 */
export class PreferenceStore {
  private handle?: preferences.Preferences;
  async open(context: common.Context): Promise<void> {
    this.handle = await preferences.getPreferences(context, { name: 'whaleness.client.v1' });
  }
  async read(): Promise<ClientPreferences> {
    if (!this.handle) { throw new Error('偏好存储尚未初始化'); }
    const value = await this.handle.get('document', '');
    if (typeof value !== 'string' || !value) { return defaultPreferences(); }
    try {
      const document = JSON.parse(value) as ClientPreferences;
      if (document.version !== 1 || !Array.isArray(document.profiles)) { throw new Error('schema'); }
      return document;
    } catch (_) { throw new Error('本地设置格式异常，已保留原数据，请从主机管理重新检查'); }
  }
  async write(document: ClientPreferences): Promise<void> {
    if (!this.handle) { throw new Error('偏好存储尚未初始化'); }
    await this.handle.put('document', JSON.stringify(document));
    await this.handle.flush();
  }
}
