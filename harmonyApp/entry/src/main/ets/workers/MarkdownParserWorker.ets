import { worker, MessageEvents } from '@kit.ArkTS';
import { MarkdownParseRequest, MarkdownParseResponse } from '../core/MarkdownParserTypes';
import { highlightCodeInWorker } from '../core/ContentWorker';
import { parseMarkdown, markdownText } from '../core/MarkdownModel';

const port = worker.workerPort;
port.onmessage = (event: MessageEvents): void => {
  const request = event.data as MarkdownParseRequest;
  const response: MarkdownParseResponse = { id: request.id, result: '', failed: false };
  try { response.result = request.code ? highlightCodeInWorker(request.source, request.language) : request.plain ? markdownText(request.source) : JSON.stringify(parseMarkdown(request.source)); }
  catch (_) { response.failed = true; }
  port.postMessage(response);
};
