export interface CodeSpan { start: number; end: number; light: string; dark: string; }
export interface ContentResult { language: string; spans: CodeSpan[]; linkKind: string; target: string; plainText: string; csv: string; }
export interface ContentCommand { op: string; text: string; title: string; rows: string[][]; }
export interface ContentResponse { ok: boolean; presentation?: ContentResult; error?: string; }
