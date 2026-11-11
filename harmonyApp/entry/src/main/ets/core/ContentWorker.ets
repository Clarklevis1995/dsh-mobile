import { renderContent } from 'libwhaleness_bridge.so';
import { ContentCommand } from './ContentTypes';

@Concurrent
export function highlightCodeInWorker(source: string, language: string): string {
  const command: ContentCommand = { op: 'render-code', text: source, title: language, rows: [] };
  return renderContent(JSON.stringify(command));
}
