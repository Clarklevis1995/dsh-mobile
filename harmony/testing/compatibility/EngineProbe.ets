import { Engine } from 'libwhaleness_bridge.so';

interface Reply { ok: boolean; }
@Entry
@Component
struct Index {
  @State private lines: string[] = [];
  aboutToAppear(): void {
    try {
      const surviving = new Engine();
      for (let i = 0; i < 32; i++) {
        const instance = new Engine(); const reply = JSON.parse(instance.dispatch('{"op":"voice-sync"}')) as Reply;
        if (!reply.ok) { throw new Error('create/dispatch'); }
        instance.close(); instance.close(); let rejected = false;
        try { instance.dispatch('{"op":"voice-sync"}'); } catch (_) { rejected = true; }
        if (!rejected) { throw new Error('closed engine accepted dispatch'); }
      }
      const reply = JSON.parse(surviving.dispatch('{"op":"voice-sync"}')) as Reply; surviving.close();
      if (!reply.ok) { throw new Error('other owner corrupted'); }
      this.lines = ['PASS 32 actual Native create / close / double-close / rejected-use cycles','PASS unrelated Native engine remains valid','DONE ENGINE probe'];
    } catch (error) { this.lines = ['FAIL ' + (error instanceof Error ? error.message : 'engine lifecycle')]; }
  }
  build() {
    Column({ space: 20 }) { ForEach(this.lines, (line: string) => { Text(line).fontSize(14).fontColor(line.startsWith('FAIL') ? '#DD2222' : '#111111') }) }
      .padding(20).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
