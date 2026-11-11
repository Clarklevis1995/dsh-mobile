import { util } from '@kit.ArkTS';

@Concurrent
export function encodeVoicePcm(chunks: ArrayBuffer[]): string {
  const total = chunks.reduce((sum: number, chunk: ArrayBuffer) => sum + chunk.byteLength, 0);
  if (total > 4 * 1024 * 1024 || total % 2) { throw new Error('录音数据超出限制'); }
  const pcm = new Uint8Array(total); let offset = 0;
  chunks.forEach((chunk: ArrayBuffer) => { pcm.set(new Uint8Array(chunk), offset); offset += chunk.byteLength; });
  return new util.Base64Helper().encodeToStringSync(pcm);
}
