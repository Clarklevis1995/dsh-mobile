import { Appearance } from '../core/Appearance';
import { VoiceData } from '../core/VoiceTypes';

@Component
export struct VoiceStateBar {
  @ObjectLink a: Appearance;
  @Prop data: VoiceData;
  onCommand: (op: string) => void = () => {};
  private feedback(): string { const raw = this.data.alert || this.data.error || ''; return raw === 'Enable the official voice input bundle in DSH plugin management' ? this.a.t('请先在桌面端启用语音输入') : raw; }
  build() {
    if (this.data.overlay || this.data.phase === 'stopping' || this.data.phase === 'transcribing' || this.data.error || this.data.alert || this.data.pendingTranscript || (this.data.voiceMode && !this.data.ready)) {
      Column({ space: 8 }) {
        if (this.data.overlay) {
          Text(this.a.t(this.data.status)).fontSize(this.a.font(14)).fontColor(this.data.cancelArmed ? '#D92D20' : this.a.fg)
          Text(this.data.timeLabel).fontSize(this.a.font(12)).fontColor(this.a.muted)
          Row({ space: 2 }) {
            ForEach(this.data.waveform, (level: number) => { Rect().width(2).height(Math.max(2, level * 24)).fill(this.data.cancelArmed ? '#D92D20' : this.a.accent) })
          }.height(24).justifyContent(FlexAlign.Center)
        } else if (this.data.phase === 'transcribing' || this.data.phase === 'stopping') {
          Row({ space: 8 }) {
            LoadingProgress().width(20).height(20).color(this.a.accent)
            Text(this.a.t(this.data.phase === 'transcribing' ? '正在转写，期间可以继续编辑草稿' : '正在结束录音')).fontSize(this.a.font(13)).fontColor(this.a.muted).layoutWeight(1)
            Button(this.a.t('取消')).id('voice-cancel').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(() => this.onCommand('voice-cancel'))
          }.width('100%')
        }
        if (this.data.pendingTranscript) {
          Text(this.a.t('草稿已修改，识别文字保留在这里')).fontSize(this.a.font(12)).fontColor(this.a.muted).width('100%')
          Text(this.data.pendingTranscript).id('voice-pending-transcript').fontSize(this.a.font(14)).fontColor(this.a.fg).copyOption(CopyOptions.LocalDevice).width('100%')
          Row({ space: 12 }) {
            Button(this.a.t('插入草稿')).id('voice-insert').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(() => this.onCommand('voice-insert'))
            Button(this.a.t('丢弃')).id('voice-dismiss').height(44).fontColor(this.a.muted).backgroundColor(this.a.surface).onClick(() => this.onCommand('voice-dismiss'))
          }
        }
        if (this.data.error || this.data.alert || (this.data.voiceMode && !this.data.ready && !this.data.overlay)) {
          Text(this.feedback() || this.a.t(this.data.preparing ? '桌面端正在准备语音模型' : '语音服务尚未就绪，请在桌面端启用语音输入插件')).id('voice-feedback').fontSize(this.a.font(13)).fontColor(this.a.muted).width('100%')
          if (this.data.canPrepare && !this.data.preparing) {
            Button(this.a.t('准备语音模型')).id('voice-prepare').height(44).fontColor(this.a.accent).backgroundColor(this.a.surface).onClick(() => this.onCommand('voice-prepare'))
          }
          if (this.data.error || this.data.alert) { Button(this.a.t('关闭')).height(44).fontColor(this.a.muted).backgroundColor(this.a.surface).onClick(() => this.onCommand('voice-dismiss')) }
        }
      }.padding(12).width('100%').backgroundColor(this.a.surface).borderRadius(14).id('voice-state')
    }
  }
}

@Component
export struct VoiceHoldButton {
  @ObjectLink a: Appearance;
  @Prop data: VoiceData;
  onCommand: (op: string) => void = () => {};
  onMove: (distance: number) => void = () => {};
  private pressTimer: number = -1;
  private downY: number = 0;
  private held: boolean = false;
  aboutToDisappear(): void { clearTimeout(this.pressTimer); if (this.held) { this.onCommand('voice-cancel'); } }
  build() {
    Button(this.a.t(this.data.cancelArmed ? '松手取消' : this.data.overlay ? '松手转文字' : '按住说话'))
      .id('voice-hold').height(48).width('100%').fontSize(this.a.font(15)).fontColor(this.a.fg).backgroundColor(this.a.surface)
      .enabled(this.data.connected && !['stopping', 'transcribing'].includes(this.data.phase)).accessibilityText(this.a.t('按住说话，松手转文字，上滑取消'))
      .onTouch((event: TouchEvent) => {
        if (event.type === TouchType.Down) { this.downY = event.touches[0].screenY; this.held = false; this.pressTimer = setTimeout(() => { this.held = true; this.onCommand('voice-press'); }, 300); }
        else if (event.type === TouchType.Move && this.held && event.touches.length) { this.onMove(event.touches[0].screenY - this.downY); }
        else if (event.type === TouchType.Up || event.type === TouchType.Cancel) { clearTimeout(this.pressTimer); if (this.held) { this.onCommand(event.type === TouchType.Up ? 'voice-release' : 'voice-cancel'); } this.held = false; }
      })
  }
}
