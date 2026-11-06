import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../website/render-budget.js', import.meta.url), 'utf8');
const { RENDER_BUDGETS, initialBudget, boundedSize, createFrameMonitor } = await import(
  `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`,
);

test('low resource hints and data saver avoid WebGL from the start', () => {
  assert.equal(initialBudget({ cores: 8, memory: 2 }), 0);
  assert.equal(initialBudget({ cores: 2, memory: 8 }), 0);
  assert.equal(initialBudget({ cores: 8, memory: 4 }), 1);
  assert.equal(initialBudget({ cores: 4, memory: 8 }), 1);
  assert.equal(initialBudget({ cores: 16, memory: 16, saveData: true }), 0);
  for (const level of [0, 1]) assert.equal(RENDER_BUDGETS[level].webgl, false);
});

test('mobile and unknown hardware start conservatively', () => {
  assert.equal(initialBudget({ cores: 8, compact: true }), 2);
  assert.equal(initialBudget({ cores: 8, coarse: true }), 2);
  assert.equal(initialBudget({}), 2);
  assert.equal(initialBudget({ cores: 8, memory: 8 }), 3);
});

test('portrait, 4K, 8K and high-DPI drawing buffers stay inside hard limits', () => {
  for (const budget of RENDER_BUDGETS) {
    for (const [width, height] of [[390, 844], [1920, 1080], [3840, 2160], [7680, 4320], [1440, 9000]]) {
      const source = boundedSize(width, height, { pixels: budget.sourcePixels, edge: budget.sourceEdge });
      assert.ok(source.width * source.height <= budget.sourcePixels);
      assert.ok(Math.max(source.width, source.height) <= budget.sourceEdge);
      if (budget.webgl) {
        const output = boundedSize(width, height, { pixels: budget.outputPixels, edge: 2048, ratio: Math.min(3, budget.pixelRatio) });
        assert.ok(output.width * output.height <= budget.outputPixels);
        assert.ok(Math.max(output.width, output.height) <= 2048);
        assert.ok(output.scale <= budget.pixelRatio);
      }
    }
  }
});

test('one long frame and refresh-rate quantization do not lower quality', () => {
  const monitor = createFrameMonitor();
  for (let i = 0; i < 100; i += 1) {
    assert.equal(monitor.record({ cost: i === 5 ? 90 : 3, gap: i === 5 ? 200 : 50, interval: 1000 / 24 }), false);
  }
});

test('sustained CPU or asynchronous frame pressure triggers degradation', () => {
  for (const sample of [{ cost: 25, gap: 50 }, { cost: 2, gap: 90 }]) {
    const monitor = createFrameMonitor();
    let lowered = false;
    for (let i = 0; i < 60; i += 1) lowered ||= monitor.record({ ...sample, interval: 1000 / 24 });
    assert.equal(lowered, true);
  }
});

test('visibility reset forgets old slow samples', () => {
  const monitor = createFrameMonitor();
  for (let i = 0; i < 23; i += 1) monitor.record({ cost: 30, gap: 90, interval: 50 });
  monitor.reset();
  assert.equal(monitor.record({ cost: 2, gap: 50, interval: 50 }), false);
});
