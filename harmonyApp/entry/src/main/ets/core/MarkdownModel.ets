import { lexer } from '@lidary/markdown/src/main/ets/core/marked';
import { Token, Tokens } from '@lidary/markdown/src/main/ets/core/Tokens';

export interface MarkdownRun {
  text: string;
  bold: boolean;
  italic: boolean;
  strike: boolean;
  code: boolean;
  href: string;
  light: string;
  dark: string;
}
export interface MarkdownCell { runs: MarkdownRun[]; text: string; align: string; }
export interface MarkdownRow { cells: MarkdownCell[]; }
export interface MarkdownData {
  id: string; kind: string; raw: string; text: string; runs: MarkdownRun[];
  depth: number; quote: boolean; prefix: string; language: string;
  header: MarkdownCell[]; rows: MarkdownRow[];
}
export function textRun(text: string): MarkdownRun {
  return { text, bold: false, italic: false, strike: false, code: false, href: '', light: '', dark: '' };
}
export function decodeEntities(value: string): string {
  return value.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, (_match: string, entity: string): string => {
    const names: Map<string, string> = new Map([['amp','&'],['lt','<'],['gt','>'],['quot','"'],['apos',"'"],['nbsp',' ']]);
    const named = names.get(entity.toLowerCase()); if (named !== undefined) { return named; }
    const number = entity.startsWith('#x') || entity.startsWith('#X') ? parseInt(entity.substring(2),16) : parseInt(entity.substring(1),10);
    return number > 0 && number <= 0x10ffff && !(number >= 0xd800 && number <= 0xdfff) ? String.fromCodePoint(number) : '\uFFFD';
  });
}
function inline(tokens: Token[], style: MarkdownRun = textRun('')): MarkdownRun[] {
  let result: MarkdownRun[] = [];
  tokens.forEach(token => {
    const next: MarkdownRun = { text: '', bold: style.bold, italic: style.italic, strike: style.strike, code: style.code, href: style.href, light: '', dark: '' };
    if (['strong','em','del','link'].includes(token.type)) {
      const node = token as Tokens.Link;
      next.bold = next.bold || token.type === 'strong'; next.italic = next.italic || token.type === 'em'; next.strike = next.strike || token.type === 'del';
      if (token.type === 'link') { next.href = node.href; }
      result = result.concat(inline(node.tokens, next));
    } else if (token.type === 'image') {
      const image = token as Tokens.Image; next.text = image.text || '图片'; next.href = image.href; result.push(next);
    } else if (token.type === 'br') { next.text = '\n'; result.push(next); }
    else {
      const node = token as Tokens.Text;
      if (node.tokens?.length) { result = result.concat(inline(node.tokens, next)); }
      else {
        next.code = next.code || token.type === 'codespan';
        next.text = decodeEntities(node.text ?? token.raw ?? '').replace(token.type === 'html' ? /<[^>]*>/g : /$^/g, '');
        result.push(next);
      }
    }
  });
  return result;
}
function cell(value: Tokens.TableCell, columnAlign?: string | null): MarkdownCell {
  const runs = inline(value.tokens); return { runs, text: runs.map(run => run.text).join(''), align: columnAlign ?? value.align ?? 'left' };
}
function blocks(tokens: Token[], depth: number = 0, quote: boolean = false, prefix: string = ''): MarkdownData[] {
  let result: MarkdownData[] = [];
  tokens.forEach(token => {
    if (token.type === 'space' || token.type === 'def') { return; }
    if (token.type === 'blockquote') { result = result.concat(blocks((token as Tokens.Blockquote).tokens, depth, true, prefix)); return; }
    if (token.type === 'list') {
      const list = token as Tokens.List;
      list.items.forEach((item: Tokens.ListItem, index: number) => {
        const label = item.task ? (item.checked ? '☑ ' : '☐ ') : list.ordered ? (Number(list.start) + index).toString() + '. ' : '• ';
        result = result.concat(blocks(item.tokens, depth + 1, quote, label));
      });
      return;
    }
    const block: MarkdownData = { id: '', kind: token.type, raw: token.raw, text: '', runs: [], depth, quote, prefix,
      language: '', header: [], rows: [] };
    if (token.type === 'code') {
      const code = token as Tokens.Code; block.text = code.text; block.language = code.lang ?? '';
    } else if (token.type === 'table') {
      const table = token as Tokens.Table; block.header = table.header.map((value: Tokens.TableCell, index: number) => cell(value, table.align[index])); block.rows = table.rows.map(row => { const value: MarkdownRow = { cells: row.map((value: Tokens.TableCell, index: number) => cell(value, table.align[index])) }; return value; });
      block.text = [block.header].concat(block.rows.map(row => row.cells)).map(row => row.map(value => value.text).join('\t')).join('\n');
    } else if (token.type !== 'hr') {
      const text = token as Tokens.Paragraph;
      block.runs = text.tokens?.length ? inline(text.tokens) : [textRun(decodeEntities(text.text ?? token.raw).replace(/<[^>]*>/g, ''))];
      block.text = block.runs.map(run => run.text).join('');
      if (token.type === 'heading') { block.depth = (token as Tokens.Heading).depth; }
    }
    result.push(block);
  });
  return result;
}
interface ProtectedCode { source: string; tab: string; carriage: string; }
/** marked 在词法阶段展开 TAB；仅保护围栏代码的内容，渲染与复制时恢复原字符。 */
export function protectCodeWhitespace(source: string): ProtectedCode {
  let tab = '\uE000'; while (source.includes(tab)) { tab += '\uE000'; }
  let carriage = '\uE001'; while (source.includes(carriage)) { carriage += '\uE001'; }
  let fence = ''; let quoteCount = 0;
  const lines = source.split('\n').map(line => {
    if (!fence) {
      const match = line.match(/^( {0,3}(?:> ?)*)(`{3,}|~{3,})([^\r]*)\r?$/);
      if (match && (match[2][0] !== '`' || !match[3].includes('`'))) { fence = match[2]; quoteCount = (match[1].match(/>/g) ?? []).length; }
      return line;
    }
    let candidate = line;
    for (let index = 0; index < quoteCount; index++) { candidate = candidate.replace(/^ {0,3}> ?/, ''); }
    const end = new RegExp('^ {0,3}' + fence[0] + '{' + fence.length.toString() + ',}[ \t]*\r?$');
    if (end.test(candidate)) { fence = ''; return line; }
    return line.replace(/\t/g, tab).replace(/\r/g, carriage);
  });
  return { source: lines.join('\n'), tab, carriage };
}
export function parseMarkdown(source: string): MarkdownData[] {
  const protectedCode = protectCodeWhitespace(source);
  const parsed = blocks(lexer(protectedCode.source, { gfm: true, breaks: true }));
  const result = JSON.parse(JSON.stringify(parsed).split(protectedCode.tab).join('\\t').split(protectedCode.carriage).join('\\r')) as MarkdownData[];
  result.forEach((value, index) => value.id = index.toString() + ':' + value.kind);
  return result;
}
export function markdownText(source: string): string {
  return parseMarkdown(source).map(block => block.prefix + block.text).join('\n\n');
}
