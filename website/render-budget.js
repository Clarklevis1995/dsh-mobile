// Conservative hints choose the starting point; sustained frame cost can only
// lower quality during this visit. No GPU fingerprinting or user controls.
export const RENDER_BUDGETS = Object.freeze([
  Object.freeze({ name: 'static', fps: 0, webgl: false, sourcePixels: 160000, sourceEdge: 720, outputPixels: 0, pixelRatio: 0, particles: 0, rays: 2, waves: 3, dispersion: 0 }),
  Object.freeze({ name: 'low', fps: 12, webgl: false, sourcePixels: 220000, sourceEdge: 900, outputPixels: 0, pixelRatio: 0, particles: 16, rays: 2, waves: 3, dispersion: 0 }),
  Object.freeze({ name: 'balanced', fps: 18, webgl: true, sourcePixels: 360000, sourceEdge: 1200, outputPixels: 480000, pixelRatio: .85, particles: 26, rays: 3, waves: 4, dispersion: 0 }),
  Object.freeze({ name: 'full', fps: 24, webgl: true, sourcePixels: 700000, sourceEdge: 1600, outputPixels: 1100000, pixelRatio: 1, particles: 56, rays: 4, waves: 5, dispersion: .025 }),
]);

export function initialBudget({ cores, memory, saveData, compact, coarse }) {
  if (saveData) return 0;
  if (cores > 0 && cores <= 2 || memory > 0 && memory <= 2) return 0;
  if (cores > 0 && cores <= 4 || memory > 0 && memory <= 4) return 1;
  if (compact || coarse || !cores) return 2;
  return 3;
}

export function boundedSize(width, height, { pixels, edge, ratio = 1 }) {
  const w = Math.max(1, width);
  const h = Math.max(1, height);
  const scale = Math.min(ratio, edge / Math.max(w, h), Math.sqrt(pixels / (w * h)));
  return { width: Math.max(1, Math.floor(w * scale)), height: Math.max(1, Math.floor(h * scale)), scale };
}

export function createFrameMonitor() {
  let samples = 0;
  let slow = 0;
  let elapsed = 0;
  return {
    reset() { samples = 0; slow = 0; elapsed = 0; },
    record({ cost, gap, interval }) {
      samples += 1;
      elapsed += Math.min(gap, 250);
      // Frame gaps also catch asynchronous GPU saturation without gl.finish()
      // or pixel readback; a single long frame never triggers a downgrade.
      if (cost > Math.min(12, interval * .2) || gap > interval * 1.6) slow += 1;
      if (samples < 24 || elapsed < 1800) return false;
      const degrade = slow / samples >= .35;
      this.reset();
      return degrade;
    },
  };
}
