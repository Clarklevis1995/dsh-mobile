import { fileIo as fs } from '@kit.CoreFileKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { preparePhoto } from './PhotoWorker';
import { util } from '@kit.ArkTS';

/** 原始选择结果先复制到私有目录；大文件复制、摘要与分块读取都在 TaskPool 执行。 */
@Concurrent
export async function prepareAttachment(uri: string, destination: string): Promise<string> {
  const source = fs.openSync(uri, fs.OpenMode.READ_ONLY);
  try {
    const original = fs.statSync(source.fd);
    if (!original.isFile() || original.size < 0 || original.size > 536870912) { throw new Error('文件不能超过 512 MiB'); }
    fs.copyFileSync(source.fd, destination);
  } finally { fs.closeSync(source); }
  const file = fs.openSync(destination, fs.OpenMode.READ_ONLY);
  try {
    const stat = fs.statSync(file.fd);
    if (!stat.isFile() || stat.size > 536870912) { throw new Error('文件大小无效'); }
    const md = cryptoFramework.createMd('SHA256'); const buffer = new ArrayBuffer(65536); let offset = 0;
    while (offset < stat.size) {
      const count = fs.readSync(file.fd, buffer, { length: Math.min(buffer.byteLength, stat.size - offset) });
      if (count <= 0) { throw new Error('文件副本不完整'); }
      await md.update({ data: new Uint8Array(buffer, 0, count) }); offset += count;
    }
    const digest = await md.digest(); let sha = '';
    digest.data.forEach(value => sha += value.toString(16).padStart(2, '0'));
    return JSON.stringify({ size: stat.size, sha256: sha });
  } finally { fs.closeSync(file); }
}

@Concurrent
export function readAttachment(path: string, offset: number, length: number): string {
  if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(length) || length < 1 || length > 4194304) { throw new Error('文件分块无效'); }
  const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
  try {
    if (offset + length > fs.statSync(file.fd).size) { throw new Error('文件副本已变化'); }
    const buffer = new ArrayBuffer(length); const count = fs.readSync(file.fd, buffer, { offset: offset, length: length });
    if (count !== length) { throw new Error('文件读取不完整'); }
    return new util.Base64Helper().encodeToStringSync(new Uint8Array(buffer));
  } finally { fs.closeSync(file); }
}


@Concurrent
export async function cacheMessageImage(data: string, expectedBytes: number, original: string, thumbnail: string): Promise<void> {
  if (!Number.isSafeInteger(expectedBytes) || expectedBytes < 1 || expectedBytes > 8388608) { throw new Error('图片大小无效'); }
  const decoded = new util.Base64Helper().decodeSync(data);
  if (decoded.byteLength !== expectedBytes) { throw new Error('图片内容不完整'); }
  const file = fs.openSync(original, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
  try { if (fs.writeSync(file.fd, decoded.buffer.slice(decoded.byteOffset, decoded.byteOffset + decoded.byteLength)) !== decoded.byteLength) { throw new Error('图片写入不完整'); } }
  finally { fs.closeSync(file); }
  await preparePhoto(original, thumbnail);
}

@Concurrent
export function writeWorkspaceFile(path: string, operation: string, data: string, offset: number, length: number, size: number): void {
  if (!Number.isSafeInteger(offset) || !Number.isSafeInteger(size) || offset < 0 || size < 0 || size > 536870912 || length < 0 || length > 4194304 || offset + length > size) { throw new Error('下载写入参数无效'); }
  if (operation === 'create') { const created = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.READ_WRITE); fs.closeSync(created); return; }
  const file = fs.openSync(path, fs.OpenMode.READ_WRITE);
  try {
    if (fs.statSync(file.fd).size !== offset) { throw new Error('下载写入偏移无效'); }
    if (operation === 'append') {
      const decoded = new util.Base64Helper().decodeSync(data);
      if (decoded.byteLength !== length) { throw new Error('下载写入长度无效'); }
      if (length && fs.writeSync(file.fd, decoded.buffer.slice(decoded.byteOffset, decoded.byteOffset + length), { offset: offset }) !== length) { throw new Error('下载写入不完整'); }
      if (fs.statSync(file.fd).size !== offset + length) { throw new Error('下载文件大小不一致'); }
    } else if (operation === 'finish') {
      if (offset !== size) { throw new Error('下载文件不完整'); } fs.fsyncSync(file.fd);
    } else { throw new Error('下载写入操作无效'); }
  } finally { fs.closeSync(file); }
}

@Concurrent
export function previewFileText(path: string): string {
  const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
  try {
    const size = fs.statSync(file.fd).size; if (size > 2097152) { throw new Error('文件较大，请使用系统应用打开'); }
    const data = new ArrayBuffer(size); if (size && fs.readSync(file.fd, data) !== size) { throw new Error('文件读取不完整'); }
    const text = util.TextDecoder.create('utf-8').decodeWithStream(new Uint8Array(data));
    if (text.includes('\u0000')) { throw new Error('此文件包含二进制内容，请使用系统应用打开'); } return text;
  } finally { fs.closeSync(file); }
}

@Concurrent
export function exportWorkspaceFile(path: string, uri: string): void {
  const source = fs.openSync(path, fs.OpenMode.READ_ONLY);
  try { const target = fs.openSync(uri, fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC); try { fs.copyFileSync(source.fd, target.fd); fs.fsyncSync(target.fd); } finally { fs.closeSync(target); } }
  finally { fs.closeSync(source); }
}
