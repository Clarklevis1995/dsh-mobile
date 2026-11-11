import { Engine } from 'libwhaleness_bridge.so';
import { pasteboard } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { fileIo, picker } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';

import { ContentCommand, ContentResponse, ContentResult } from './ContentTypes';
let renderer: Engine | undefined;
export function contentAction(op: string, text: string = '', title: string = '', rows: string[][] = []): ContentResult {
  if (!renderer) { renderer = new Engine(); }
  const command: ContentCommand = { op, text, title, rows };
  const response = JSON.parse(renderer.dispatch(JSON.stringify(command))) as ContentResponse;
  if (!response.ok || !response.presentation) { throw new Error(response.error ?? '内容处理失败'); }
  return response.presentation;
}
export async function copyContent(text: string): Promise<void> {
  await pasteboard.getSystemPasteboard().setData(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
}
export async function openContentLink(context: common.UIAbilityContext, href: string): Promise<void> {
  const link = contentAction('render-link', href);
  if (link.linkKind === 'web') { await context.openLink(link.target, { appLinkingOnly: false }); }
  else if (link.linkKind === 'file') { throw new Error('请从工作区文件入口打开：' + link.target); }
  else { throw new Error('无法打开此链接'); }
}

export async function saveCsvContent(context: common.UIAbilityContext, csv: string): Promise<boolean> {
  const uris = await new picker.DocumentViewPicker(context).save({ newFileNames: ['table.csv'], fileSuffixChoices: ['CSV|.csv'] });
  if (!uris.length) { return false; }
  const file = await fileIo.open(uris[0], fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.TRUNC);
  try {
    const bytes = new util.TextEncoder().encodeInto(csv); let offset = 0;
    while (offset < bytes.byteLength) {
      const count = await fileIo.write(file.fd, bytes.buffer.slice(bytes.byteOffset + offset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer);
      if (count <= 0) { throw new Error('文件写入未完成'); } offset += count;
    }
  } finally { await fileIo.close(file); }
  return true;
}
