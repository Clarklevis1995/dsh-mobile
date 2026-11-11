export interface MarkdownParseRequest { id: number; source: string; plain: boolean; code: boolean; language: string; }
export interface MarkdownParseResponse { id: number; result: string; failed: boolean; }
