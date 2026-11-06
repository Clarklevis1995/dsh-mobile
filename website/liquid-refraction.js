import { createAmbientScene } from './ambient-scene.js?v=20261106-ocean-photo-v1';
import { createRefraction } from './vendor/glasswork/refract.js?v=20261106-ocean-photo-v1';
import { RENDER_BUDGETS, initialBudget, createFrameMonitor } from './render-budget.js?v=20261106-ocean-photo-v1';

export function createGlassRefraction() {
  const root = document.documentElement;
  const background = document.querySelector('.ambient-background');
  const candidates = [...document.querySelectorAll('[data-glass-lens]')];
  const motion = matchMedia('(prefers-reduced-motion: reduce)');
  const transparency = matchMedia('(prefers-reduced-transparency: reduce)');
  const colors = matchMedia('(forced-colors: active)');
  const compact = matchMedia('(max-width: 767px)');
  const coarse = matchMedia('(pointer: coarse)');
  const connection = navigator.connection;
  const hints = () => ({ cores: navigator.hardwareConcurrency, memory: navigator.deviceMemory,
    saveData: connection?.saveData, compact: compact.matches, coarse: coarse.matches });
  let level = initialBudget(hints());
  let budget = RENDER_BUDGETS[level];
  const source = document.createElement('canvas');
  const output = document.createElement('canvas');
  source.className = 'ambient-canvas';
  output.className = 'refraction-canvas';
  source.setAttribute('aria-hidden', 'true');
  output.setAttribute('aria-hidden', 'true');
  background.append(source, output);
  let scene;
  let renderer = null;
  let gpuUnavailable = false;
  let frame = 0;
  let timer = 0;
  let previous = -Infinity;
  let clock = 0;
  let lastTime = 0;
  let suspended = true;
  let disposed = false;
  let geometryDirty = true;
  let resizeDirty = true;
  let sceneDirty = true;
  let resizing = false;
  let resizeTimer = 0;
  let overlayOpen = false;
  let active = [];
  const monitor = createFrameMonitor();

  const frozen = () => motion.matches || budget.fps === 0 || overlayOpen;
  const allowed = () => !transparency.matches && !colors.matches;
  const stop = () => {
    cancelAnimationFrame(frame);
    clearTimeout(timer);
    frame = timer = 0;
  };
  const markSurfaces = enabled => candidates.forEach(element => {
    const refracted = enabled && active.some(surface => surface.element === element);
    if (element.hasAttribute('data-webgl-glass') !== refracted) element.toggleAttribute('data-webgl-glass', refracted);
  });
  const releaseGPU = () => {
    renderer?.dispose();
    renderer = null;
    markSurfaces(false);
    output.hidden = true;
    if (root.dataset.refraction === 'webgl') root.dataset.refraction = 'canvas';
  };
  const setLevel = next => {
    level = Math.min(level, next);
    budget = RENDER_BUDGETS[level];
    if (root.dataset.renderQuality !== budget.name) root.dataset.renderQuality = budget.name;
    if (!budget.webgl) releaseGPU();
    else renderer?.configure({ pixelRatio: budget.pixelRatio, pixels: budget.outputPixels, edge: 2048 });
    geometryDirty = resizeDirty = sceneDirty = true;
    if (budget.fps === 0) {
      suspended = true;
      stop();
      scene?.dispose();
      scene = null;
      source.width = source.height = 1;
      root.removeAttribute('data-refraction');
    }
    monitor.reset();
  };
  const loseGPU = () => {
    if (disposed) return;
    gpuUnavailable = true;
    setLevel(1);
    root.dataset.refraction = 'canvas';
  };
  const failSafe = () => {
    if (disposed) return;
    disposed = true;
    stop();
    clearTimeout(resizeTimer);
    releaseGPU();
    scene?.dispose();
    scene = null;
    source.width = source.height = 1;
    source.remove(); output.remove();
    root.removeAttribute('data-refraction');
    root.dataset.renderQuality = 'static';
  };
  const readSurfaces = () => {
    active = candidates.flatMap(element => {
      const rect = element.getBoundingClientRect();
      if (rect.width < 32 || rect.height < 32 || rect.bottom <= 0 || rect.top >= innerHeight || rect.right <= 0 || rect.left >= innerWidth) return [];
      const style = getComputedStyle(element);
      for (let parent = element; parent && parent !== document.body; parent = parent.parentElement) {
        const current = parent === element ? style : getComputedStyle(parent);
        if (Number(current.opacity) < 1 || current.transform !== 'none' || current.willChange !== 'auto') return [];
      }
      const number = (name, value) => Number.parseFloat(style.getPropertyValue(name)) || value;
      return [{ element, x: rect.left, y: rect.top, w: rect.width, h: rect.height,
        radius: Number.parseFloat(style.borderTopLeftRadius) || 0,
        rim: Math.min(52, Math.min(rect.width, rect.height) / 3.5), edge: 1.25, power: 2.6,
        dispersion: budget.dispersion, blur: .45, saturate: number('--m-sat', 1.15),
        brightness: number('--m-bri', .98), contrast: 1, keep: 1 }];
    });
    geometryDirty = false;
  };
  const ensureGPU = () => {
    if (renderer || gpuUnavailable || resizing || !budget.webgl || !active.length) return;
    try {
      renderer = createRefraction(output, {
        source: () => ({ el: source, moving: false, external: true }),
        surfaces: () => active,
        maxPixelRatio: budget.pixelRatio,
        maxRenderPixels: budget.outputPixels,
        maxRenderEdge: 2048,
        externallyDriven: true,
      });
    } catch { renderer = null; }
    if (!renderer) loseGPU();
  };
  const paint = () => {
    if (!scene) {
      try { scene = createAmbientScene(source, budget, () => { sceneDirty = true; schedule(); }); } catch { scene = null; }
      if (!scene) {
        failSafe();
        return;
      }
    }
    if (!scene.ready) return false;
    // Geometry/style reads happen before canvas/attribute writes, and only
    // after layout or scroll changes, never on every background frame.
    if (geometryDirty) {
      if (budget.webgl && !gpuUnavailable) readSurfaces();
      else { active = []; geometryDirty = false; }
    }
    if (resizeDirty) {
      scene.refresh(budget);
      resizeDirty = false;
      sceneDirty = true;
    }
    ensureGPU();
    if (resizeDirty) { scene.refresh(budget); resizeDirty = false; }
    const fresh = sceneDirty || !frozen();
    if (fresh) scene.draw(motion.matches ? 0 : clock);
    sceneDirty = false;
    const mode = renderer ? 'webgl' : 'canvas';
    if (root.dataset.refraction !== mode) root.dataset.refraction = mode;
    const refracting = renderer && active.length && !resizing;
    if (refracting) {
      renderer.setEnabled(true);
      renderer.frame({ fresh });
    } else renderer?.setEnabled(false);
    markSurfaces(Boolean(renderer && active.length && !resizing));
  };
  const schedule = () => {
    if (frame || timer || suspended || disposed || document.hidden) return;
    // Scroll/resize invalidation uses the same deadline as ambient animation.
    const interval = 1000 / (budget.fps || 12);
    timer = setTimeout(() => {
      timer = 0;
      frame = requestAnimationFrame(tick);
    }, Math.max(0, previous + interval - performance.now()));
  };
  const tick = now => {
    frame = 0;
    if (suspended || disposed || document.hidden) return;
    const interval = 1000 / (budget.fps || 12);
    const gap = Number.isFinite(previous) ? now - previous : interval;
    if (gap < interval) { schedule(); return; }
    if (lastTime && !frozen()) clock += Math.min(150, now - lastTime);
    previous = lastTime = now;
    const start = performance.now();
    try { if (paint() === false) return; } catch { failSafe(); return; }
    if (!frozen() && level > 0 && monitor.record({ cost: performance.now() - start, gap, interval })) {
      setLevel(level - 1);
    }
    if (!frozen() || resizeDirty || sceneDirty) schedule();
  };
  const invalidate = () => { geometryDirty = true; schedule(); };
  const refresh = () => {
    if (disposed) return;
    setLevel(initialBudget(hints()));
    suspended = !allowed() || budget.fps === 0;
    stop();
    if (suspended) {
      releaseGPU();
      scene?.dispose();
      scene = null;
      source.width = source.height = 1;
      root.removeAttribute('data-refraction');
    }
    lastTime = 0;
    monitor.reset();
    schedule();
  };
  output.addEventListener('webglcontextlost', loseGPU);
  output.addEventListener('refractionfailed', loseGPU);
  source.addEventListener('contextlost', failSafe);
  document.addEventListener('scroll', invalidate, { passive: true });
  document.addEventListener('visibilitychange', () => {
    stop();
    lastTime = 0;
    previous = -Infinity;
    monitor.reset();
    if (!document.hidden) { geometryDirty = sceneDirty = true; schedule(); }
  });
  const resize = () => {
    if (disposed) return;
    resizing = true;
    renderer?.setEnabled(false);
    markSurfaces(false);
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { resizing = false; refresh(); }, 180);
  };
  window.addEventListener('resize', resize, { passive: true });
  window.visualViewport?.addEventListener('resize', resize, { passive: true });
  document.addEventListener('toggle', event => {
    if (event.target.tagName !== 'DIALOG') return;
    overlayOpen = Boolean(document.querySelector('dialog[open]'));
    lastTime = 0;
    monitor.reset();
    schedule();
  }, true);
  document.addEventListener('transitionend', event => {
    if (event.propertyName === 'transform' || event.propertyName === 'opacity') invalidate();
  });
  const observer = new ResizeObserver(invalidate);
  observer.observe(document.querySelector('main'));
  [motion, transparency, colors, compact, coarse].forEach(query => query.addEventListener('change', refresh));
  connection?.addEventListener?.('change', refresh);
  window.addEventListener('pagehide', () => { stop(); releaseGPU(); clearTimeout(resizeTimer); resizing = false; lastTime = 0; });
  window.addEventListener('pageshow', refresh);
  refresh();
  return { refresh };
}
