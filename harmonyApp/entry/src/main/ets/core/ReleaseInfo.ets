/** 鸿蒙预览版自己的版本说明；部署协议最低版本与 AppRelease/release-info.json 同步。 */
export const MINIMUM_GATEWAY_VERSION: string = '0.12.0';
export const MOBILE_RELEASE_ROOT: string = 'https://github.com/Clarklevis1995/dsh-mobile';
export const GATEWAY_RELEASE_ROOT: string = 'https://github.com/Clarklevis1995/dsh-plugin-mobile-gateway';
export const RELEASE_API: string = 'https://api.github.com/repos/Clarklevis1995/dsh-mobile/releases/latest';
interface ReleaseAsset { name?: string; browser_download_url?: string; state?: string; size?: number; }
interface PublicRelease { tag_name?: string; html_url?: string; draft?: boolean; prerelease?: boolean; assets?: ReleaseAsset[]; }
export interface HarmonyReleaseResult { status: string; version: string; url: string; }

function versionParts(value: string): number[] {
  const match = /^v?(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.exec(value);
  if (!match) { throw new Error('Invalid version'); }
  const parts = [Number(match[1]), Number(match[2]), Number(match[3])];
  if (parts.some(part => !Number.isSafeInteger(part))) { throw new Error('Invalid version'); }
  return parts;
}
export function newerStableRelease(latest: string, current: string): boolean {
  const left = versionParts(latest); const right = versionParts(current);
  for (let index = 0; index < 3; index++) { if (left[index] !== right[index]) { return left[index] > right[index]; } }
  return current.split('+')[0].includes('-');
}

/** 只接受上游正式发布的 HAP 元数据；APK、IPA、源码和其他域名永远不是鸿蒙更新。 */
export function harmonyRelease(raw: string, current: string): HarmonyReleaseResult {
  if (raw.length > 262144) { throw new Error('Release response too large'); }
  const release = JSON.parse(raw) as PublicRelease;
  if (!release || typeof release.tag_name !== 'string' || !/^v?\d+\.\d+\.\d+$/.test(release.tag_name) ||
    release.draft !== false || release.prerelease !== false || !Array.isArray(release.assets) ||
    release.html_url !== MOBILE_RELEASE_ROOT + '/releases/tag/' + encodeURIComponent(release.tag_name)) { throw new Error('Invalid public release'); }
  const tag = release.tag_name;
  const hap = release.assets.some((asset: ReleaseAsset) => asset && typeof asset.name === 'string' &&
    /\.hap$/i.test(asset.name) && asset.state === 'uploaded' && typeof asset.size === 'number' && asset.size > 0 &&
    asset.browser_download_url === MOBILE_RELEASE_ROOT + '/releases/download/' + encodeURIComponent(tag) + '/' + encodeURIComponent(asset.name));
  return { status: !hap ? 'unsupported' : newerStableRelease(tag, current) ? 'available' : 'current', version: tag, url: release.html_url };
}

export function harmonyReleaseNotes(english: boolean, version: string): string {
  return english ? '# Whaleness Harness for HarmonyOS ' + version + '\n\n' +
    '- Native ArkUI navigation with system back gestures.\n- Host pairing, streaming chat, questions and one-time approvals.\n' +
    '- Markdown, code, tables, attachments and text drafts.\n- Encrypted local history, offline reading and bilingual search.\n\n' +
    'This is a simulator preview. Camera, microphone, lock-screen behavior and performance still require device testing.' :
    '# Whaleness Harness 鸿蒙版 ' + version + '\n\n' +
    '- ArkUI 原生页面与系统手势返回。\n- 主机配对、流式聊天、问题回答和单次审批。\n' +
    '- Markdown、代码、表格、附件与文字草稿。\n- 本地加密历史、离线阅读与中英文搜索。\n\n' +
    '当前为模拟器预览版。相机、麦克风、锁屏行为与性能仍需真机验证。';
}
