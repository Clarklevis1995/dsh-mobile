import { boundedSize, RENDER_BUDGETS } from './render-budget.js?v=20261106-ocean-photo-v1';

// The ocean and the refracted glass share one frame, including every light mote.
export function createAmbientScene(canvas, initial = RENDER_BUDGETS[2], invalidate = () => {}) {
  let budget = initial;
  const context = canvas.getContext('2d', { alpha: false });
  if (!context) return null;
  let width = 1;
  let height = 1;
  let scale = 1;
  let particles = [];
  let photo = null;
  let requested = '';
  let pending = null;
  let failed = false;
  let closed = false;
  const poster = document.createElement('canvas');
  const posterContext = poster.getContext('2d', { alpha: false });
  if (!posterContext) return null;
  const bake = () => {
    if (!photo || closed) return;
    if (poster.width !== canvas.width) poster.width = canvas.width;
    if (poster.height !== canvas.height) poster.height = canvas.height;
    posterContext.setTransform(scale, 0, 0, scale, 0, 0);
    const cover = Math.max(width / photo.naturalWidth, height / photo.naturalHeight) * 1.025;
    const w = photo.naturalWidth * cover;
    const h = photo.naturalHeight * cover;
    posterContext.drawImage(photo, (width - w) * .65, -(h - height) * .02, w, h);
    // Bake the blue optical grade once; never read back or recolor pixels per frame.
    posterContext.globalCompositeOperation = 'multiply';
    posterContext.fillStyle = '#4e9dcd';
    posterContext.fillRect(0, 0, width, height);
    posterContext.globalCompositeOperation = 'source-over';
    posterContext.fillStyle = 'rgba(1,9,22,.5)';
    posterContext.fillRect(0, 0, width, height);
  };
  const load = () => {
    const name = budget.name === 'full' ? 'ocean-surface-v1.webp'
      : budget.name === 'balanced' ? 'ocean-surface-medium-v1.webp' : 'ocean-surface-small-v1.webp';
    const url = new URL(`assets/${name}`, import.meta.url).href;
    if (requested === url) return;
    requested = url;
    const image = new Image();
    pending = image;
    image.decoding = 'async';
    image.onload = async () => {
      if (image.decode) await image.decode().catch(() => {});
      if (closed || requested !== url) return;
      photo = image;
      pending = null;
      bake();
      invalidate();
    };
    image.onerror = () => {
      if (closed || requested !== url) return;
      failed = true;
      pending = null;
      invalidate();
    };
    image.src = url;
  };
  const ray = document.createElement('canvas');
  ray.width = 128;
  ray.height = 512;
  const rayContext = ray.getContext('2d');
  if (!rayContext) return null;
  const pixels = rayContext.createImageData(ray.width, ray.height);
  for (let y = 0; y < ray.height; y += 1) {
    const depth = y / ray.height;
    const spread = .055 + depth * .36;
    const fade = (1 - depth) ** 1.7 * (1 - Math.exp(-depth * 20));
    for (let x = 0; x < ray.width; x += 1) {
      const offset = (x / ray.width - .5) / spread;
      const index = (y * ray.width + x) * 4;
      pixels.data.set([95, 195, 242, Math.round(Math.exp(-offset * offset * 3) * fade * 55)], index);
    }
  }
  rayContext.putImageData(pixels, 0, 0);
  const dust = document.createElement('canvas');
  dust.width = dust.height = 32;
  const dustContext = dust.getContext('2d');
  if (!dustContext) return null;
  const glow = dustContext.createRadialGradient(16, 16, 0, 16, 16, 16);
  glow.addColorStop(0, 'rgba(192,232,255,.7)');
  glow.addColorStop(.12, 'rgba(139,210,248,.35)');
  glow.addColorStop(.4, 'rgba(69,166,219,.09)');
  glow.addColorStop(1, 'rgba(69,166,219,0)');
  dustContext.fillStyle = glow;
  dustContext.fillRect(0, 0, 32, 32);

  const refresh = (next = budget) => {
    if (closed) return;
    const nextWidth = Math.max(1, window.innerWidth);
    const nextHeight = Math.max(1, window.innerHeight);
    if (next === budget && nextWidth === width && nextHeight === height && particles.length === budget.particles) return;
    budget = next;
    width = nextWidth;
    height = nextHeight;
    const size = boundedSize(width, height, { pixels: budget.sourcePixels, edge: budget.sourceEdge });
    scale = size.scale;
    if (canvas.width !== size.width) canvas.width = size.width;
    if (canvas.height !== size.height) canvas.height = size.height;
    // Stable positions across resize; no random flicker between frames.
    let seed = 9127;
    const random = () => {
      seed = (seed * 1664525 + 1013904223) >>> 0;
      return seed / 4294967296;
    };
    particles = Array.from({ length: budget.particles }, () => ({
      x: random(), y: random(), depth: random(), phase: random() * Math.PI * 2,
      speed: .0015 + random() * .003,
    }));
    load();
    bake();
  };
  const draw = milliseconds => {
    const time = milliseconds / 1000;
    context.setTransform(scale, 0, 0, scale, 0, 0);
    const base = context.createLinearGradient(0, 0, width * .12, height);
    base.addColorStop(0, '#061a30');
    base.addColorStop(.48, '#031224');
    base.addColorStop(1, '#010711');
    context.fillStyle = base;
    context.fillRect(0, 0, width, height);
    if (photo) {
      const zoom = 1.014 + Math.sin(time * .06) * .004;
      const x = -width * (zoom - 1) / 2 + Math.sin(time * .045) * width * .003;
      const y = -height * (zoom - 1) / 2 + Math.cos(time * .04) * height * .002;
      context.drawImage(poster, x, y, width * zoom, height * zoom);
      // A faint, offset top reflection gives the detailed water a slow shimmer.
      // It reuses the same capped bitmap; there is no per-pixel water simulation.
      if (budget.webgl) {
        context.save();
        context.beginPath();
        context.rect(0, 0, width, height * .48);
        context.clip();
        context.globalAlpha = .1;
        context.drawImage(poster, x + Math.sin(time * .13) * 4, y + Math.cos(time * .11) * 2, width * zoom, height * zoom);
        context.restore();
      }
    }
    context.save();
    context.globalCompositeOperation = 'screen';
    // A cached feathered shaft widens with depth, like scattered sunlight.
    for (let i = 0; i < budget.rays; i += 1) {
      const flow = time * .035 + i * 1.7;
      const beamWidth = width * (.22 + i * .035);
      context.save();
      context.translate(width * (.46 + i * .09 + .02 * Math.sin(flow)), -height * .18);
      context.rotate(-.24 + .08 * Math.sin(flow));
      context.globalAlpha = .08 + .07 * (.5 + .5 * Math.sin(flow * 1.2));
      context.drawImage(ray, -beamWidth / 2, 0, beamWidth, height * 1.6);
      context.restore();
    }
    particles.forEach(particle => {
      const x = width * (particle.x + Math.sin(time * .035 + particle.phase) * .025);
      const y = height * ((particle.y - time * particle.speed % 1 + 1) % 1);
      const edgeFade = Math.min(1, y / (height * .12), (height - y) / (height * .12));
      const size = 4 + particle.depth * 9;
      context.globalAlpha = (.18 + particle.depth * .3) * edgeFade * (.7 + .3 * Math.sin(time * .22 + particle.phase));
      context.drawImage(dust, x - size / 2, y - size / 2, size, size);
    });
    context.restore();
  };
  refresh();
  return {
    draw, refresh,
    get ready() { return Boolean(photo) || failed; },
    dispose() {
      closed = true;
      if (pending) { pending.onload = null; pending.onerror = null; }
      photo = pending = null;
      poster.width = poster.height = ray.width = ray.height = dust.width = dust.height = 1;
    },
  };
}
