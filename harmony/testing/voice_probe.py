"""仅临时 F 包替换麦克风为固定 PCM；不把此 F 冒充真实 AudioCapturer／D。"""
def instrument(app):
    path = app / 'entry/src/main/ets/core/VoiceController.ets'; text = path.read_text()
    begin = text.index('  private async permission('); end = text.index('  private async close(', begin)
    text = text[:begin] + '''  private async permission(id: string): Promise<void> { this.wantedId = id; this.intent('voice-permission', id, 'granted'); }
  private async start(id: string): Promise<void> {
    this.wantedId = id; this.finishing = false;
    const pcm = new ArrayBuffer(32000); const samples = new DataView(pcm);
    for (let i = 0; i < 16000; i++) { samples.setInt16(i * 2, Math.round(Math.sin(i * .15) * 9000), true); }
    this.chunks = [pcm]; this.intent('voice-started', id);
  }
  private async stop(id: string, discard: boolean): Promise<void> {
    if (this.wantedId !== id || this.finishing) { return; } this.finishing = true;
    if (discard) { this.releaseCapture(); return; }
    const encoded = await taskpool.execute(encodeVoicePcm, this.chunks) as string;
    if (this.wantedId !== id || !this.active) { return; }
    const command = new Command('voice-finished'); command.value = id; command.input = encoded; this.send(command);
  }
''' + text[end:]; path.write_text(text)
