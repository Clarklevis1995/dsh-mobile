import { http } from '@kit.NetworkKit';
import { harmonyRelease, HarmonyReleaseResult, RELEASE_API } from './ReleaseInfo';

@Observed
export class ReleaseCheckState {
  busy: boolean = false;
  status: string = 'idle';
  version: string = '';
  url: string = '';
}

/** 页面生命周期内的只读请求。离开/重入后的旧完成不能写回新状态。 */
export class ReleaseChecker {
  private generation: number = 0;
  private request?: http.HttpRequest;
  private state: ReleaseCheckState;
  constructor(state: ReleaseCheckState) { this.state = state; }
  cancel(): void {
    this.generation++; const request = this.request; this.request = undefined;
    if (this.state.busy) { this.state.status = 'idle'; }
    this.state.busy = false;
    try { request?.destroy(); } catch (_) { /* 请求可能已由网络栈关闭。 */ }
  }
  async check(currentVersion: string): Promise<void> {
    if (this.state.busy) { return; }
    const generation = ++this.generation;
    let request: http.HttpRequest;
    try { request = http.createHttp(); } catch (_) { this.state.status = 'error'; return; }
    this.request = request;
    this.state.busy = true; this.state.status = 'loading'; this.state.url = ''; this.state.version = '';
    try {
      const response = await request.request(RELEASE_API, { method: http.RequestMethod.GET,
        header: { 'Accept': 'application/vnd.github+json', 'User-Agent': 'Whaleness-HarmonyOS' },
        expectDataType: http.HttpDataType.STRING, connectTimeout: 8000, readTimeout: 10000, maxLimit: 262144, usingCache: false });
      if (generation !== this.generation) { return; }
      if (response.responseCode === 429) { this.state.status = 'rate-limited'; return; }
      if (response.responseCode === 404) { this.state.status = 'not-published'; return; }
      if (response.responseCode !== 200 || typeof response.result !== 'string') { throw new Error('Release request failed'); }
      const result: HarmonyReleaseResult = harmonyRelease(response.result, currentVersion);
      this.state.status = result.status; this.state.version = result.version; this.state.url = result.url;
    } catch (_) { if (generation === this.generation) { this.state.status = 'error'; } }
    finally {
      if (generation === this.generation) { this.state.busy = false; this.request = undefined; }
      try { request.destroy(); } catch (_) { /* cancel 可以先释放同一请求。 */ }
    }
  }
}
