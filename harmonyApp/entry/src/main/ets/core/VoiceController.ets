import { UiText } from './UiMessages';
import { audio } from '@kit.AudioKit';
import { abilityAccessCtrl, common } from '@kit.AbilityKit';
import { taskpool } from '@kit.ArkTS';
import { GatewayClient } from './GatewayClient';
import { Command, SharedState } from './Models';
import { VoiceData, VoiceEffect, emptyVoice } from './VoiceTypes';
import { encodeVoicePcm } from './VoiceEncodeWorker';

/** 平台只执行录音／权限 effect；业务状态、超时、取消与草稿合并均来自 Native 共享状态机。 */
export class VoiceController {
  onToast: (message: UiText) => void = () => {};
  onChange: (value: VoiceData) => void = () => {};
  onDraft: (session: string, value: string) => void = () => {};
  private context: common.UIAbilityContext;
  private client: GatewayClient;
  private state: VoiceData = emptyVoice();
  private active: boolean = false;
  private alive: boolean = true;
  private scope: string = '';
  private wantedId: string = '';
  private capturer?: audio.AudioCapturer;
  private chunks: ArrayBuffer[] = [];
  private bytes: number = 0;
  private lastLevel: number = 0;
  private finishing: boolean = false;
  private ticker: number = -1;
  private stopTimer: number = -1;
  constructor(context: common.UIAbilityContext, client: GatewayClient) {
    this.context = context; this.client = client;
    client.onVoice = value => {
      if (!this.alive) { return; } this.state = value;
      if (value.draft !== undefined && this.active && value.sessionId === client.selectedSessionId) { this.onDraft(value.sessionId, value.draft); }
      this.onChange(value);
    };
    client.onVoiceEffects = effects => effects.forEach(effect => this.effect(effect));
  }
  stateChanged(state: SharedState): void {
    const key = this.client.connectionGeneration + ':' + (state.selectedSessionId ?? '') + ':' + state.connected + ':' + state.childConversation + ':' + state.capabilities.includes('speech-input-v1');
    if (key === this.scope) { return; } this.scope = key; this.intent('voice-sync');
  }
  setActive(active: boolean): void { if (this.active && !active) { this.intent('voice-suspend'); this.releaseCapture(); } this.active = active; }
  command(op: string, value: string = ''): void { if (this.alive && this.active) { this.intent(op, value); } }
  move(distance: number): void { if (this.active) { const command = new Command('voice-move'); command.byteSize = Math.round(distance); this.send(command); } }
  private intent(op: string, value: string = '', mode: string = ''): void { const command = new Command(op); command.value = value; command.mode = mode; this.send(command); }
  private send(command: Command): void { if (this.alive) { this.client.action(command).catch(() => { if (this.alive) { this.onChange(this.state); } }); } }
  draftChanged(): void { if (this.active && (this.state.voiceMode || this.state.phase !== 'idle')) { this.intent('voice-draft'); } }
  mode(enabled: boolean): void { if (this.active) { this.intent('voice-mode', '', enabled ? 'on' : 'off'); } }
  private effect(effect: VoiceEffect): void {
    if (!this.alive) { return; }
    if (effect.action === 'showToast') { if (effect.message) { this.onToast(effect.message); } }
    else if (effect.action === 'permission') { this.permission(effect.recordingId); }
    else if (effect.action === 'start') { this.start(effect.recordingId); }
    else if (effect.action === 'stop' && effect.discard) { this.releaseCapture(); }
    else if (effect.action === 'stop') { clearTimeout(this.stopTimer); this.stopTimer = setTimeout(() => this.stop(effect.recordingId, effect.discard), effect.delayMilliseconds); }
    else if (effect.action === 'releaseCapture') { this.releaseCapture(); }
    else if (effect.action === 'startTicker') { clearInterval(this.ticker); this.ticker = setInterval(() => this.intent('voice-tick'), 100); }
    else if (effect.action === 'stopTicker') { clearInterval(this.ticker); this.ticker = -1; }
    // warmCapture 是可选预热；首次只在用户实际录音后创建麦克风，避免空闲占用。
  }
  private async permission(id: string): Promise<void> {
    this.wantedId = id;
    try {
      const result = await abilityAccessCtrl.createAtManager().requestPermissionsFromUser(this.context, ['ohos.permission.MICROPHONE']);
      if (this.alive && this.active && this.wantedId === id) { this.intent('voice-permission', id, result.authResults[0] === 0 ? 'granted' : 'denied'); }
    } catch (_) { if (this.wantedId === id) { this.intent('voice-permission', id, 'denied'); } }
  }
  private async start(id: string): Promise<void> {
    this.wantedId = id; this.chunks = []; this.bytes = 0; this.finishing = false;
    try {
      const streamInfo: audio.AudioStreamInfo = { samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000, channels: audio.AudioChannel.CHANNEL_1,
        sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE, encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW };
      const capturerInfo: audio.AudioCapturerInfo = { source: audio.SourceType.SOURCE_TYPE_MIC, capturerFlags: 0 };
      const options: audio.AudioCapturerOptions = { streamInfo: streamInfo, capturerInfo: capturerInfo };
      const capture = await audio.createAudioCapturer(options);
      if (!this.alive || !this.active || this.wantedId !== id) { await capture.release(); return; }
      const actual = await capture.getStreamInfo();
      if (actual.samplingRate !== audio.AudioSamplingRate.SAMPLE_RATE_16000 || actual.channels !== audio.AudioChannel.CHANNEL_1 ||
        actual.sampleFormat !== audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE) { await capture.release(); throw new Error('不支持所需录音格式'); }
      if (!this.alive || !this.active || this.wantedId !== id) { await capture.release(); return; }
      this.capturer = capture;
      capture.on('readData', (buffer: ArrayBuffer) => {
        if (this.wantedId !== id || !this.active) { return; }
        const count = Math.min(buffer.byteLength, 3840000 - this.bytes); const even = count - count % 2;
        if (even > 0) { this.chunks.push(buffer.slice(0, even)); this.bytes += even; }
        const now = Date.now();
        if (now - this.lastLevel >= 100 && even > 0) {
          this.lastLevel = now; const samples = new DataView(buffer); let sum = 0;
          for (let offset = 0; offset < even; offset += 2) { const amplitude = samples.getInt16(offset, true) / 32768; sum += amplitude * amplitude; }
          const command = new Command('voice-level'); command.value = id; command.byteCount = Math.round(Math.sqrt(sum / (even / 2)) * 10000); this.send(command);
        }
        if (this.bytes >= 3840000) { this.intent('voice-release'); }
      });
      capture.on('audioInterrupt', () => { if (this.wantedId === id) { this.intent('voice-failed', id); this.releaseCapture(); } });
      await capture.start();
      if (this.wantedId !== id || !this.active) { await this.close(capture); return; }
      this.intent('voice-started', id);
    } catch (_) { if (this.wantedId === id) { this.intent('voice-failed', id); this.releaseCapture(); } }
  }
  private async stop(id: string, discard: boolean): Promise<void> {
    if (this.wantedId !== id || this.finishing) { return; }
    const capture = this.capturer; if (!capture) { return; }
    this.finishing = true;
    try {
      await capture.stop(); capture.off('readData'); capture.off('audioInterrupt'); await capture.release(); if (this.capturer === capture) { this.capturer = undefined; }
      if (discard || !this.active || this.wantedId !== id) { this.releaseCapture(); return; }
      const chunks = this.chunks; this.chunks = []; this.bytes = 0;
      const encoded = await taskpool.execute(encodeVoicePcm, chunks) as string;
      if (this.wantedId !== id || !this.active || !this.alive) { return; }
      const command = new Command('voice-finished'); command.value = id; command.input = encoded; this.send(command);
    } catch (_) { if (this.wantedId === id) { this.intent('voice-failed', id); this.releaseCapture(); } }
  }
  private async close(capture: audio.AudioCapturer): Promise<void> {
    try { capture.off('readData'); capture.off('audioInterrupt'); await capture.stop(); } catch (_) {}
    try { await capture.release(); } catch (_) {}
  }
  private releaseCapture(): void {
    clearTimeout(this.stopTimer); this.wantedId = ''; this.chunks = []; this.bytes = 0; this.finishing = false;
    const capture = this.capturer; this.capturer = undefined; if (capture) { this.close(capture); }
  }
  dispose(): void {
    if (!this.alive) { return; } this.intent('voice-leave'); this.alive = false; this.active = false;
    clearInterval(this.ticker); this.releaseCapture(); this.client.onVoice = () => {}; this.client.onVoiceEffects = () => {}; this.client.voiceDraft = () => ''; this.onToast = () => {}; this.onChange = () => {}; this.onDraft = () => {};
  }
}
