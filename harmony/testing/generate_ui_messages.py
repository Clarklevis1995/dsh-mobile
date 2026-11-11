"""从主干规范字典生成鸿蒙共享语义文案，不改写原始主机内容。"""
from pathlib import Path
import re,json,sys
ROOT = Path(__file__).resolve().parents[2]
src=(ROOT / 'androidApp/src/main/kotlin/com/clarklevis/dsh/android/localization/AndroidStrings.kt').read_text()
def pairs(s):
 out={}
 for key,val in re.findall(r'"((?:\\.|[^"\\])*)"\s+to\s+"((?:\\.|[^"\\])*)"',s):
  if not re.fullmatch(r'[a-z][a-zA-Z0-9]*(?:\.[a-zA-Z0-9]+)+',key):continue
  out[key]=json.loads('"'+re.sub(r'\\+\$', '$',val)+'"')
 return out
zh=pairs(src.split('internal val chinese:',1)[1].split('internal val english:',1)[0]);en=pairs(src.split('internal val english:',1)[1]);assert len(zh)>50 and set(zh)<=set(en)
path=ROOT / 'harmonyApp/entry/src/main/ets/core/UiMessages.ets'
content=('''// 从主干 AndroidStrings 的共享语义文案生成；由平台在渲染时选择语言。
export interface UiMessage { key: string; args?: string[]; }
export type UiText = string | UiMessage;
const ZH: Map<string,string> = new Map<string,string>('''+json.dumps(list(zh.items()),ensure_ascii=False)+''');
const EN: Map<string,string> = new Map<string,string>('''+json.dumps(list(en.items()),ensure_ascii=False)+''');
export function resolveUiMessage(value: UiMessage, english: boolean): string {
  if (value.key === 'raw') { return value.args?.[0] ?? ''; }
  const template = (english ? EN : ZH).get(value.key) ?? ZH.get(value.key) ?? value.key;
  if (!value.args?.length) { return template; }
  return template.replace(/%(\\d+)\\$s/g, (_match: string, number: string) => value.args?.[Number(number)-1] ?? _match);
}
''')

if '--check' in sys.argv:
 if path.read_text() != content:
  raise SystemExit('鸿蒙语义文案与主干不一致，请运行 generate_ui_messages.py 重新生成')
else:
 path.write_text(content)
