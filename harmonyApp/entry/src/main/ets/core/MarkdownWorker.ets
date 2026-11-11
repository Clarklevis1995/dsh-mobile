import { parseMarkdown, markdownText } from './MarkdownModel';

@Concurrent
export function parseMarkdownInWorker(source: string): string {
  return JSON.stringify(parseMarkdown(source));
}

@Concurrent
export function markdownTextInWorker(source: string): string { return markdownText(source); }
