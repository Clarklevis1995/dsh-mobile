export interface VoiceData {
  sessionId: string; phase: string; voiceMode: boolean; connected: boolean; supported: boolean; ready: boolean;
  canPrepare: boolean; preparing: boolean; progress: number; level: number; waveform: number[];
  alert?: string; error?: string; pendingTranscript?: string; seconds: number; timeLabel: string; status: string;
  blocked: boolean; overlay: boolean; cancelArmed: boolean; draft?: string;
}
export interface VoiceEffect { action: string; recordingId: string; discard: boolean; message: string; delayMilliseconds: number; }
export interface VoiceFrameHeader { kind?: string; requestType?: string; }
export function emptyVoice(): VoiceData { return { sessionId:'', phase:'idle', voiceMode:false, connected:false, supported:false,
  ready:false, canPrepare:false, preparing:false, progress:0, level:0, waveform:[], seconds:0, timeLabel:'', status:'', blocked:false, overlay:false, cancelArmed:false }; }
