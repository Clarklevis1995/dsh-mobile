/*
 * The rim lens of Lumno's liquid glass, as a standalone module.
 *
 *   import { liquidGlass } from './lens.js';
 *   const lens = liquidGlass(element, { rim: 40, edge: 1.1, power: 2.6, dispersion: 0.15 });
 *   lens.update({ rim: 32 });   // change any option later
 *   lens.destroy();
 *
 * The wallpaper behind a surface's rim bends inward, the way the edge of a
 * thick glass slab refracts, and splits slightly into its colors there. An
 * SVG displacement map drives the bend through `backdrop-filter: url(#…)`,
 * which only Chromium draws; everywhere else nothing happens and the
 * CSS-only glass stays. The element must put `var(--glass-lens,)` last in
 * its backdrop-filter:
 *
 *   backdrop-filter: blur(2px) saturate(180%) var(--glass-lens,);
 *
 * Ported from lumno-extension (src/domain/liquid-glass-lens.ts and
 * src/newtab/dom/liquid-glass.ts). The map is a nine-slice lens: only the
 * rim bends, and a rim depends on the corner's radius and shape and the
 * rim's width alone, so a lens is four corner tiles and four one-pixel edge
 * strips (stretched along the sides) over a flat middle. The tiles are built
 * and decoded once per rim and shared by every surface; a surface that
 * resizes only moves its tiles.
 */

export const LENS_DEFAULTS = {
  // The widest the rim gets, in CSS pixels. Small surfaces narrow it to a
  // third and a half of their short side, so they keep a flat middle.
  rim: 40,
  // How far inward the very edge samples the scene, in rim widths.
  edge: 1.1,
  // How fast the bend falls off across the rim: (1 - d / rim) ^ power.
  power: 2.6,
  // Red bends this much further and blue this much less; 0 for one pass.
  dispersion: 0.15,
  // The corner's radius in pixels and its superellipse exponent (2 is a
  // circle, 2^k is CSS superellipse(k)); read from the element when null.
  radius: null,
  corner: null,
  // Turns the lens off without destroying it.
  enabled: true,
  // The custom property the filter goes in.
  property: '--glass-lens',
};

// A circular corner's exponent (CSS corner-shape: round).
export const ROUND_CORNER = 2;

// Too small a surface has no flat middle to look through.
const LENS_MIN_PX = 32;

// While the rim keeps changing (a slider being dragged, a window being
// resized), the lens already there bends to the new width and the new
// tiles are built once it has been still this long.
const SETTLE_MS = 140;

const SVG = 'http://www.w3.org/2000/svg';

// Only Chromium draws url() in backdrop-filter. Firefox parses it and draws
// nothing; Safari (and every browser on iOS) draws none of it either.
export function isLensSupported() {
  if (typeof CSS === 'undefined' || !CSS.supports?.('backdrop-filter', 'url(#a)')) return false;
  const ua = navigator.userAgent;
  return /Chrome\//.test(ua) && !/Firefox\//.test(ua);
}

// The superellipse exponent of a computed CSS corner-shape. round is a
// circle and squircle is superellipse(2); anything else reads as round.
export function cornerExponent(shape) {
  const value = String(shape || '').trim();
  if (value === 'squircle') return 4;
  const match = /^superellipse\(\s*([^)\s]+)\s*\)$/.exec(value);
  const k = match ? Number(match[1]) : Number.NaN;
  return Number.isFinite(k) && k >= 0 ? 2 ** k : ROUND_CORNER;
}

// ---------- The map ----------

// How much of the rim's bend a point keeps, `depth` of the way across it.
function rimStrength(depth, power) {
  return depth >= 1 ? 0 : (1 - Math.max(0, depth)) ** power;
}

// The rim's contours run from the outline (depth 0) to the rim's inner edge
// (depth 1), each the outline inset by that share of the rim. A corner
// tighter than the rim widens its inner contours toward one as wide as the
// rim, so the bend turns the corner smoothly instead of snapping along the
// diagonal (a seam the color split would light).
function contourRadius(depth, radius, bezel) {
  if (radius >= bezel) return radius - depth * bezel;
  return (1 - depth) * (radius + (bezel - radius) * depth * (2 - depth));
}

function isWithinContour(x, y, depth, radius, bezel, corner) {
  const inset = depth * bezel;
  if (x < inset || y < inset) return false;
  const curve = contourRadius(depth, radius, bezel);
  const center = inset + curve;
  if (x >= center || y >= center) return true;
  return ((center - x) / curve) ** corner + ((center - y) / curve) ** corner <= 1;
}

// The inward bend at a point `x` and `y` from a corner's two edges.
function bendNearCorner(x, y, radius, bezel, corner, power) {
  if (isWithinContour(x, y, 1, radius, bezel, corner)) return [0, 0];
  let depth = 0;
  if (isWithinContour(x, y, 0, radius, bezel, corner)) {
    // The contours are nested, so the depth a point lies at is found by halving.
    let low = 0;
    let high = 1;
    // 14 halvings place a point within 1/16000 of the rim: finer than a pixel.
    for (let step = 0; step < 14; step += 1) {
      const middle = (low + high) / 2;
      if (isWithinContour(x, y, middle, radius, bezel, corner)) low = middle;
      else high = middle;
    }
    depth = low;
  }
  const strength = rimStrength(depth, power);
  const curve = contourRadius(depth, radius, bezel);
  const center = depth * bezel + curve;
  if (x < center && y < center && curve > 0) {
    // Across the corner's curve: along its normal.
    const nx = Math.max(0, (center - x) / curve) ** (corner - 1);
    const ny = Math.max(0, (center - y) / curve) ** (corner - 1);
    const length = Math.hypot(nx, ny) || 1;
    return [(nx / length) * strength, (ny / length) * strength];
  }
  return x < y ? [strength, 0] : [0, strength];
}

// Inward offset at a point: unit direction times strength (1 at the rim's
// outer edge, 0 in the flat middle).
export function rimDisplacement(x, y, width, height, { bezel, radius, corner = ROUND_CORNER, power = LENS_DEFAULTS.power }) {
  const rim = Math.min(bezel, height / 2, width / 2);
  const r = Math.max(0, Math.min(radius, width / 2, height / 2));
  const [inwardX, inwardY] = bendNearCorner(Math.min(x, width - x), Math.min(y, height - y), r, rim, corner, power);
  if (inwardX === 0 && inwardY === 0) return [0, 0];
  return [x < width / 2 ? inwardX : -inwardX, y < height / 2 ? inwardY : -inwardY];
}

// Where each tile sits on a surface of this size: corners (top left, top
// right, bottom left, bottom right) then edges (top, bottom, left, right).
function lensLayout(width, height, size) {
  const across = Math.max(1, width - size * 2);
  const down = Math.max(1, height - size * 2);
  return [
    [0, 0, size, size],
    [width - size, 0, size, size],
    [0, height - size, size, size],
    [width - size, height - size, size, size],
    [size, 0, across, size],
    [size, height - size, across, size],
    [0, size, size, down],
    [width - size, size, size, down],
  ];
}

// The tiles' pixels, read off a probe surface one pixel wider than two
// tiles. Red is the sideways bend and green the vertical one, 128 none.
function tilePixels(radius, bezel, corner, power) {
  const size = Math.max(1, Math.ceil(Math.max(radius, bezel)));
  const probe = size * 2 + 1;
  const geometry = { bezel, radius, corner, power };
  return {
    size,
    tiles: lensLayout(probe, probe, size).map(([left, top, w, h]) => {
      const data = new Uint8ClampedArray(w * h * 4);
      for (let y = 0; y < h; y += 1) {
        for (let x = 0; x < w; x += 1) {
          const [dx, dy] = rimDisplacement(left + x + 0.5, top + y + 0.5, probe, probe, geometry);
          const i = (y * w + x) * 4;
          data[i] = Math.round(128 + dx * 127);
          data[i + 1] = Math.round(128 + dy * 127);
          data[i + 2] = 128;
          data[i + 3] = 255;
        }
      }
      return { width: w, height: h, data };
    }),
  };
}

// Tiles for one rim, as decoded PNG data URLs: a filter whose images are
// still decoding draws without its blur, so no surface points at one early.
const tileCache = new Map();
const TILE_CACHE_MAX = 48;

function lensTiles(radius, bezel, corner, power) {
  const key = `${radius}:${bezel}:${corner.toFixed(3)}:${power.toFixed(3)}`;
  let entry = tileCache.get(key);
  if (entry) {
    // Most recently used last.
    tileCache.delete(key);
    tileCache.set(key, entry);
    return entry;
  }
  const { size, tiles } = tilePixels(radius, bezel, corner, power);
  // Encoded with toBlob, which compresses off the main thread, and kept as
  // object URLs for as long as the page lives (a rim's tiles are small).
  entry = Promise.all(tiles.map((tile) => {
    const canvas = document.createElement('canvas');
    canvas.width = tile.width;
    canvas.height = tile.height;
    const context = canvas.getContext('2d');
    const image = context.createImageData(tile.width, tile.height);
    image.data.set(tile.data);
    context.putImageData(image, 0, 0);
    return new Promise((resolve) => canvas.toBlob(resolve, 'image/png')).then(async (blob) => {
      const url = blob ? URL.createObjectURL(blob) : canvas.toDataURL('image/png');
      const decoded = new Image();
      decoded.src = url;
      await decoded.decode().catch(() => {});
      return url;
    });
  })).then((urls) => ({ size, urls }));
  tileCache.set(key, entry);
  while (tileCache.size > TILE_CACHE_MAX) tileCache.delete(tileCache.keys().next().value);
  return entry;
}

// ---------- The filter ----------

function node(tag, attrs, parent) {
  const el = document.createElementNS(SVG, tag);
  for (const [name, value] of Object.entries(attrs)) el.setAttribute(name, value);
  parent?.append(el);
  return el;
}

let filterCount = 0;

// The lens tiles over a flat flood make the map; the backdrop is displaced
// by it once, or once per color channel by its own share of the bend, the
// three added back together (screen on single channels is a sum). Each
// filter gets an <svg> of its own: adding one to an <svg> that holds others
// can make Chrome reload all their images, and a backdrop naming a filter
// whose images are reloading drops its blur.
function buildFilter(width, height, tiles, split) {
  const id = `liquid-glass-lens-${++filterCount}`;
  const host = node('svg', {
    'aria-hidden': 'true', width: 0, height: 0,
    style: 'position:absolute;width:0;height:0;overflow:hidden;pointer-events:none',
  });
  const filter = node('filter', {
    id, filterUnits: 'userSpaceOnUse', 'color-interpolation-filters': 'sRGB', x: 0, y: 0, width, height,
  }, host);
  node('feFlood', { 'flood-color': 'rgb(128,128,128)', result: 'flat' }, filter);
  const rects = lensLayout(width, height, tiles.size);
  const pieces = rects.map(([x, y, w, h], index) => node('feImage', {
    href: tiles.urls[index], preserveAspectRatio: 'none', x, y, width: w, height: h, result: `t${index}`,
  }, filter));
  const merge = node('feMerge', { result: 'map' }, filter);
  node('feMergeNode', { in: 'flat' }, merge);
  rects.forEach((_, index) => node('feMergeNode', { in: `t${index}` }, merge));
  const passes = [];
  const pass = () => {
    passes.push({
      el: node('feDisplacementMap', { in: 'SourceGraphic', in2: 'map', xChannelSelector: 'R', yChannelSelector: 'G', scale: 0 }, filter),
    });
  };
  if (split) {
    ['r', 'g', 'b'].forEach((name, channel) => {
      pass();
      // Keeps this channel and alpha: a 1 on the 4x5 matrix's diagonal.
      const keep = Array.from({ length: 20 }, (_, cell) => Number(cell === channel * 6 || cell === 18)).join(' ');
      node('feColorMatrix', { type: 'matrix', values: keep, result: name }, filter);
    });
    node('feBlend', { in: 'r', in2: 'g', mode: 'screen' }, filter);
    node('feBlend', { in2: 'b', mode: 'screen' }, filter);
  } else {
    pass();
  }
  document.body.append(host);
  return { id, host, filter, pieces, passes, tiles };
}

function placeFilter(lens, width, height) {
  lens.filter.setAttribute('width', width);
  lens.filter.setAttribute('height', height);
  lensLayout(width, height, lens.tiles.size).forEach(([x, y, w, h], index) => {
    const piece = lens.pieces[index];
    piece.setAttribute('x', x);
    piece.setAttribute('y', y);
    piece.setAttribute('width', w);
    piece.setAttribute('height', h);
  });
}

// ---------- A surface ----------

/*
 * Gives `el` a rim lens in its --glass-lens property and keeps it fitted as
 * the element resizes. Returns { supported, bezel, update(options),
 * refresh(), destroy() }.
 */
export function liquidGlass(el, options = {}) {
  let opts = { ...LENS_DEFAULTS, ...options };
  const supported = isLensSupported();
  let current = null; // { key, lens, width, height }
  let token = 0;
  let frame = 0;
  let lastChange = 0;
  let settle = 0;
  let observer = null;
  const api = {
    supported,
    bezel: 0,
    update(next = {}) {
      opts = { ...opts, ...next };
      schedule();
    },
    refresh: () => schedule(),
    destroy,
  };
  if (!supported) {
    // Nothing to draw: every call is a no-op.
    api.update = () => {};
    api.refresh = () => {};
    api.destroy = () => {};
    return api;
  }

  function clear() {
    token += 1;
    el.style.removeProperty(opts.property);
    current?.lens.host.remove();
    current = null;
  }

  function setScale(lens, bezel) {
    const scale = 2 * opts.edge * bezel;
    const split = lens.passes.length > 1;
    lens.passes.forEach((p, channel) => {
      const share = split ? 1 + (1 - channel) * opts.dispersion : 1;
      p.el.setAttribute('scale', (scale * share).toFixed(1));
    });
  }

  async function fit() {
    frame = 0;
    const width = Math.round(el.offsetWidth);
    const height = Math.round(el.offsetHeight);
    if (!opts.enabled || width < LENS_MIN_PX || height < LENS_MIN_PX) {
      clear();
      return;
    }
    const style = getComputedStyle(el);
    const cssRadius = Number.parseFloat(style.borderTopLeftRadius) || 0;
    const radius = Math.round(Math.min(opts.radius ?? cssRadius, width / 2, height / 2));
    const corner = opts.corner ?? cornerExponent(style.getPropertyValue('corner-top-left-shape'));
    const bezel = Math.round(Math.max(4, Math.min(opts.rim, Math.min(width, height) / 3.5)));
    const split = opts.dispersion > 0;
    const key = `${radius}:${bezel}:${corner.toFixed(3)}:${opts.power.toFixed(3)}:${split}`;
    api.bezel = bezel;
    if (current?.key === key) {
      if (current.width !== width || current.height !== height) {
        placeFilter(current.lens, width, height);
        current.width = width;
        current.height = height;
      }
      setScale(current.lens, bezel);
      return;
    }
    const now = performance.now();
    const settling = current && now - lastChange < SETTLE_MS;
    lastChange = now;
    if (settling) {
      if (current.width !== width || current.height !== height) {
        placeFilter(current.lens, width, height);
        current.width = width;
        current.height = height;
      }
      setScale(current.lens, bezel);
      clearTimeout(settle);
      settle = setTimeout(schedule, SETTLE_MS);
      return;
    }
    const mine = ++token;
    const tiles = await lensTiles(radius, bezel, corner, opts.power);
    if (mine !== token) return;
    const lens = buildFilter(width, height, tiles, split);
    setScale(lens, bezel);
    const previous = current;
    current = { key, lens, width, height };
    el.style.setProperty(opts.property, `url(#${lens.id})`);
    // Let the new filter draw before the old one goes.
    if (previous) requestAnimationFrame(() => requestAnimationFrame(() => previous.lens.host.remove()));
  }

  function schedule() {
    if (!frame) frame = requestAnimationFrame(fit);
  }

  observer = new ResizeObserver(schedule);
  observer.observe(el);

  function destroy() {
    observer.disconnect();
    cancelAnimationFrame(frame);
    clearTimeout(settle);
    clear();
  }

  schedule();
  return api;
}
