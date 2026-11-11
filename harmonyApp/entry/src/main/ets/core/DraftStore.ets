class Submission {
  text: string;
  revision: number;
  constructor(text: string, revision: number) { this.text = text; this.revision = revision; }
}

/** 内存草稿由持久化适配器观察；用编辑版本区分相同文本，避免旧 ACK 清掉再次输入的草稿。 */
export class DraftStore {
  onChanged: (session: string, text: string, revision: number) => void = () => {};
  private drafts: Map<string, string> = new Map();
  private revisions: Map<string, number> = new Map();
  private submitted: Map<string, Submission> = new Map();

  changed(session: string | undefined, text: string): boolean {
    if (!session || this.drafts.get(session) === text || (!this.drafts.has(session) && !text)) { return false; }
    this.drafts.set(session, text);
    this.revisions.set(session, (this.revisions.get(session) ?? 0) + 1);
    this.onChanged(session, text, this.revisions.get(session) ?? 0);
    return true;
  }

  switchSession(previous: string | undefined, next: string | undefined, text: string): string {
    this.changed(previous, text);
    return next ? this.drafts.get(next) ?? '' : '';
  }

  restore(session: string, text: string, revision: number): boolean {
    if (this.drafts.has(session) || this.version(session) > 0 || text.length > 100000 || !Number.isSafeInteger(revision) || revision < 0) { return false; }
    this.drafts.set(session, text); this.revisions.set(session, revision); return true;
  }
  invalidateSubmissions(): void { this.submitted.clear(); }
  failed(session: string): void { this.submitted.delete(session); }
  prune(active: string, count: number, protectedSessions: string[] = []): string[] {
    const removed: string[] = [];
    const retained = new Set(Array.from(this.drafts.keys()).slice(-count).concat([active]).concat(Array.from(this.submitted.keys())).concat(protectedSessions));
    this.drafts.forEach((_text: string, session: string) => { if (!retained.has(session)) { this.drafts.delete(session); this.revisions.delete(session); removed.push(session); } });
    return removed;
  }
  has(session: string): boolean { return this.drafts.has(session); }
  version(session: string | undefined): number { return session ? this.revisions.get(session) ?? 0 : 0; }
  replace(session: string, revision: number, expected: string, replacement: string): string | undefined {
    if (this.version(session) !== revision || (this.drafts.get(session) ?? '') !== expected) { return undefined; }
    this.changed(session, replacement); return replacement;
  }

  append(session: string, text: string): string {
    const old = this.drafts.get(session) ?? ''; const next = old ? old + '\n\n' + text : text;
    this.changed(session, next); return next;
  }

  sent(session: string, text: string): void {
    this.changed(session, text);
    this.submitted.set(session, new Submission(text, this.revisions.get(session) ?? 0));
  }

  acknowledged(session: string, active: string | undefined, text: string): string {
    const submission = this.submitted.get(session); this.submitted.delete(session);
    if (!submission) { return text; }
    if (active === session) { this.changed(session, text); }
    const unchanged = this.revisions.get(session) === submission.revision && this.drafts.get(session) === submission.text;
    if (unchanged) { this.changed(session, ''); }
    return active === session && unchanged ? '' : text;
  }

  clear(): void { this.drafts.clear(); this.revisions.clear(); this.submitted.clear(); }
}
