import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';

export class Credential {
  endpoint: string = '';
  gatewayId?: string;
  token: string = '';
  deviceId: string = '';
  endpoints: string[] = [];
  gatewayName: string = '';
}

/** Token 和设备 ID 一同保存在系统 Asset 中，一次性配对码永不落盘。 */
export class CredentialStore {
  private encoder: util.TextEncoder = new util.TextEncoder();
  private alias: Uint8Array;

  constructor(profileId?: string) {
    if (profileId !== undefined && !/^[A-Za-z0-9_-]{1,64}$/.test(profileId)) { throw new Error('主机标识无效'); }
    this.alias = this.encoder.encodeInto(profileId ? 'whaleness.gateway.v2.' + profileId : 'whaleness.gateway.v1');
  }

  async load(): Promise<Credential | undefined> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, this.alias);
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    try {
      const rows = await asset.query(query);
      if (rows.length === 0) { return undefined; }
      const secret = rows[0].get(asset.Tag.SECRET) as Uint8Array;
      return JSON.parse(util.TextDecoder.create('utf-8').decodeToString(secret)) as Credential;
    } catch (error) {
      if ((error as BusinessError).code === asset.ErrorCode.NOT_FOUND) { return undefined; }
      throw new Error('系统安全存储读取失败，请解锁设备后重试');
    }
  }

  async save(value: Credential): Promise<void> {
    const attributes: asset.AssetMap = new Map();
    attributes.set(asset.Tag.ALIAS, this.alias);
    attributes.set(asset.Tag.SECRET, this.encoder.encodeInto(JSON.stringify(value)));
    attributes.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_FIRST_UNLOCKED);
    attributes.set(asset.Tag.CONFLICT_RESOLUTION, asset.ConflictResolution.OVERWRITE);
    await asset.add(attributes);
  }

  async clear(): Promise<void> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.ALIAS, this.alias);
    try { await asset.remove(query); } catch (error) {
      if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) {
        throw new Error('系统安全存储清理失败');
      }
    }
  }
}
