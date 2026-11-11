// 独立真实 Host 的受控上游；不向外部模型服务发送任何请求。
import { pathToFileURL } from 'node:url';
import path from 'node:path';
if (!process.env.HARMONY_DSH_NODE_MODULES) throw new Error('需要 HARMONY_DSH_NODE_MODULES');
const { LlmAdapter } = await import(pathToFileURL(path.join(process.env.HARMONY_DSH_NODE_MODULES, '@deepseek-ai/dsh-llm/lib/index.js')).href);
class ControlledAdapter extends LlmAdapter {
  async listModels(provider) { return [{ provider, id: 'harmony-controlled', name: 'Harmony controlled upstream' }]; }
  async resolveModel(provider, model) { return { provider, id: model, name: 'Harmony controlled upstream', context: { contextWindow: 65536 } }; }
  async *stream(options) {
    const prompt = options.messages.filter(message => message.role === 'user').map(message => typeof message.content === 'string' ? message.content : message.content.filter(block => block.type === 'text').map(block => block.text).join('\n')).join('\n');
    const marker = prompt.match(/HARMONY_[A-Z0-9_]+/g)?.at(-1) || 'HARMONY_READY';
    // 仅独立 Host 的固定验收标记可触发此工具；由真实 Agent／UserQuestions 服务处理。
    if (marker.startsWith('HARMONY_QUESTION_')) {
      const lastUser = options.messages.findLastIndex(message => message.role === 'user' && JSON.stringify(message.content).includes(marker));
      const resolved = options.messages.slice(lastUser + 1).some(message => message.role === 'tool');
      if (!resolved) {
        if (!options.tools?.some(tool => tool.name === 'ask_user_question')) throw new Error('验收 Host 未提供 ask_user_question');
        const id = 'harmony-question-' + Date.now();
        const questions = [
          { id: 'single', question: 'HARMONY 单选验收', options: [{ label: 'A' }, { label: 'B' }] },
          { id: 'multi', question: 'HARMONY 多选验收', multi_select: true, options: [{ label: 'A' }, { label: 'B' }, { label: 'C' }] },
          { id: 'custom', question: 'HARMONY 自定义验收' }
        ];
        const argumentsText = JSON.stringify({ questions });
        yield { type: 'block-start', index: 0, blockType: 'tool-call' };
        yield { type: 'tool-call-delta', index: 0, id, name: 'ask_user_question', argumentsDelta: argumentsText };
        yield { type: 'block-end', index: 0, block: { type: 'tool-call', id, name: 'ask_user_question', arguments: argumentsText } };
        yield { type: 'finish', reason: { kind: 'tool-calls' } };
        return;
      }
    }
    const slow = marker.includes('SLOW'); let full = '';
    yield { type: 'block-start', index: 0, blockType: 'text' };
    for (let index = 0; index < (slow ? 200 : 1); index++) {
      if (options.signal?.aborted) throw options.signal.reason || new Error('aborted');
      if (slow) await new Promise(resolve => setTimeout(resolve, 200));
      const text = marker + (slow ? ':' + index + '\n' : ''); full += text;
      yield { type: 'text-delta', index: 0, text };
    }
    yield { type: 'block-end', index: 0, block: { type: 'text', text: full } };
    yield { type: 'finish', reason: { kind: 'stop' } };
  }
}
export const name = 'harmony-controlled-upstream';
export const inject = ['llm'];
export default { name, inject, apply(ctx) { ctx.llm.registerAdapter(['harmony-controlled'], new ControlledAdapter()); } };
