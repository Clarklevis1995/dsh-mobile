import { abilityAccessCtrl, common } from '@kit.AbilityKit';
import { audio } from '@kit.AudioKit';

@Entry
@Component
struct Index {
  @State private lines: string[] = [];
  @State private busy: boolean = false;
  private async run(): Promise<void> {
    if (this.busy) { return; } this.busy = true;
    let capture: audio.AudioCapturer | undefined = undefined;
    try {
      const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const permission = await abilityAccessCtrl.createAtManager().requestPermissionsFromUser(context, ['ohos.permission.MICROPHONE']);
      if (permission.authResults[0] !== 0) { throw new Error('microphone not granted'); }
      const streamInfo: audio.AudioStreamInfo = { samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000, channels: audio.AudioChannel.CHANNEL_1,
        sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE, encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW };
      const capturerInfo: audio.AudioCapturerInfo = { source: audio.SourceType.SOURCE_TYPE_MIC, capturerFlags: 0 };
      const options: audio.AudioCapturerOptions = { streamInfo: streamInfo, capturerInfo: capturerInfo };
      capture = await audio.createAudioCapturer(options); const actual = await capture.getStreamInfo();
      if (actual.samplingRate !== 16000 || actual.channels !== 1 || actual.sampleFormat !== audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE) { throw new Error('wrong actual stream format'); }
      // 不调用 start／read，不采集任何环境音；录音内容与识别效果保留 D 验收。
      await capture.release(); capture = undefined;
      this.lines = ['PASS actual microphone permission','PASS actual AudioCapturer 16000Hz / mono / S16LE','PASS release before capture; no audio collected','DONE AUDIO probe'];
    } catch (error) { this.lines = ['FAIL ' + (error instanceof Error ? error.message : 'audio sdk')]; }
    finally { if (capture) { try { await capture.release(); } catch (_) {} } this.busy = false; }
  }
  build() {
    Column({ space: 16 }) {
      Text('录音 SDK 格式专项，不启动采集').fontSize(17)
      Button('申请并验证录音格式').id('probe-audio-start').enabled(!this.busy).onClick(() => this.run())
      ForEach(this.lines, (line: string) => { Text(line).fontSize(14).fontColor(line.startsWith('FAIL') ? '#DD2222' : '#111111') })
    }.padding(20).width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
