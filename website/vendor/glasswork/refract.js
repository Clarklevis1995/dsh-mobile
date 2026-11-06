import { boundedSize } from '../../render-budget.js?v=20261106-ocean-photo-v1';

/*
 * The glass's backdrop drawn in WebGL, for browsers that can't put an SVG
 * filter in backdrop-filter (Safari and every browser on iOS, Firefox).
 *
 * One canvas lies over the wallpaper and under the glass. It draws the
 * wallpaper again inside each glass surface only, as the material would
 * show it: the rim bending the scene inward (the same curve as lens.js:
 * the edge looks edge x rim x (1 - d / rim) ^ power inward), red bent a
 * little further than blue, then blur, saturation, contrast and
 * brightness. Elsewhere the canvas is clear and the wallpaper shows as it
 * is. The surfaces then draw their tint, rim light and ink over it without
 * a backdrop filter of their own.
 *
 *   const refraction = createRefraction(canvas, { source, surfaces });
 *   refraction.wake();   // something changed: draw for a moment
 *
 * source() returns the wallpaper element to draw (a video, an image or a
 * canvas), whether it moves, and whether something else says when it has
 * a new frame (frame(), for a canvas drawn elsewhere); surfaces() returns,
 * for each glass surface, its box in the canvas's CSS pixels and how to
 * draw it.
 *
 * It draws when the wallpaper has a new frame, so the glass never lags the
 * wallpaper around it: a video's own frames (requestVideoFrameCallback),
 * or frame() for a canvas. For a moment after wake() it draws every
 * display frame, so a dragged surface follows the pointer.
 */

const MAX = 8;

const VERT = `#version 300 es
in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }`;

const FRAG = `#version 300 es
precision highp float;
uniform sampler2D uTex;
uniform vec2 uRes;
uniform float uScale;
uniform vec4 uCover;
uniform int uCount;
uniform vec4 uRect[${MAX}];
uniform vec4 uShape[${MAX}];
uniform vec4 uTone[${MAX}];
uniform vec2 uMore[${MAX}];
out vec4 outColor;

// Signed distance to a rounded box (negative inside).
float box(vec2 p, vec2 hb, float r) {
  vec2 q = abs(p) - hb + r;
  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// The wallpaper at a point in CSS pixels, laid out as object-fit: cover.
vec3 scene(vec2 p) {
  return texture(uTex, clamp(p * uCover.xy + uCover.zw, 0.0, 1.0)).rgb;
}

// A small Gaussian blur of the given radius (CSS blur's standard
// deviation), from 12 samples on a spiral.
vec3 blurred(vec2 p, float radius) {
  if (radius < 0.6) return scene(p);
  vec3 sum = scene(p);
  float total = 1.0;
  for (int i = 0; i < 12; i++) {
    float fi = float(i);
    float d = radius * 2.2 * sqrt((fi + 0.5) / 12.0);
    float a = fi * 2.39996;
    float w = exp(-(d * d) / (2.0 * radius * radius));
    sum += scene(p + vec2(cos(a), sin(a)) * d) * w;
    total += w;
  }
  return sum / total;
}

vec3 saturateBy(vec3 c, float s) {
  return vec3(
    (0.213 + 0.787 * s) * c.r + (0.715 - 0.715 * s) * c.g + (0.072 - 0.072 * s) * c.b,
    (0.213 - 0.213 * s) * c.r + (0.715 + 0.285 * s) * c.g + (0.072 - 0.072 * s) * c.b,
    (0.213 - 0.213 * s) * c.r + (0.715 - 0.715 * s) * c.g + (0.072 + 0.928 * s) * c.b);
}

void main() {
  vec2 p = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y) / uScale;
  for (int i = 0; i < ${MAX}; i++) {
    if (i >= uCount) break;
    vec4 rect = uRect[i];
    vec2 hb = rect.zw * 0.5;
    vec2 local = p - rect.xy - hb;
    float radius = min(uShape[i].x, min(hb.x, hb.y));
    float d = box(local, hb, radius);
    if (d > 0.75) continue;
    // Inward, across the rim.
    float e = 0.5;
    vec2 grad = vec2(
      box(local + vec2(e, 0.0), hb, radius) - box(local - vec2(e, 0.0), hb, radius),
      box(local + vec2(0.0, e), hb, radius) - box(local - vec2(0.0, e), hb, radius));
    vec2 inward = -grad / max(length(grad), 1e-5);
    float rim = uShape[i].y;
    float depth = clamp(-d / rim, 0.0, 1.0);
    float amount = uShape[i].z * rim * pow(1.0 - depth, uShape[i].w);
    float split = uMore[i].y;
    vec4 tone = uTone[i];
    vec3 c;
    if (split > 0.0 && amount > 0.01) {
      c.r = blurred(p + inward * amount * (1.0 + split), tone.x).r;
      c.g = blurred(p + inward * amount, tone.x).g;
      c.b = blurred(p + inward * amount * (1.0 - split), tone.x).b;
    } else {
      c = blurred(p + inward * amount, tone.x);
    }
    c = saturateBy(c, tone.y);
    c = (c - 0.5) * tone.z + 0.5;
    c *= tone.w;
    c = saturateBy(c, uMore[i].x);
    float a = clamp(0.5 - d * uScale, 0.0, 1.0);
    outColor = vec4(clamp(c, 0.0, 1.0) * a, a);
    return;
  }
  outColor = vec4(0.0);
}`;

// How long a wake keeps drawing every display frame (a glide, a drag).
const AWAKE_MS = 800;

export function createRefraction(canvas, { source, surfaces, maxPixelRatio = 1, maxRenderPixels = 1100000, maxRenderEdge = 2048, externallyDriven = false }) {
  const gl = canvas.getContext('webgl2', { alpha: true, premultipliedAlpha: true, antialias: false, depth: false, stencil: false, powerPreference: 'low-power', failIfMajorPerformanceCaveat: true });
  if (!gl) return null;

  const compile = (type, text) => {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, text);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const message = gl.getShaderInfoLog(shader);
      gl.deleteShader(shader);
      throw new Error(message);
    }
    return shader;
  };
  const program = gl.createProgram();
  let vertex;
  let fragment;
  try {
    vertex = compile(gl.VERTEX_SHADER, VERT);
    fragment = compile(gl.FRAGMENT_SHADER, FRAG);
    gl.attachShader(program, vertex);
    gl.attachShader(program, fragment);
    gl.bindAttribLocation(program, 0, 'aPos');
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program));
  } catch (error) {
    if (vertex) gl.deleteShader(vertex);
    if (fragment) gl.deleteShader(fragment);
    gl.deleteProgram(program);
    throw error;
  }
  gl.useProgram(program);
  const at = (name) => gl.getUniformLocation(program, name);
  const u = {
    res: at('uRes'), scale: at('uScale'), cover: at('uCover'), count: at('uCount'),
    rect: at('uRect'), shape: at('uShape'), tone: at('uTone'), more: at('uMore'),
  };

  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

  const texture = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, texture);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

  const rect = new Float32Array(MAX * 4);
  const shape = new Float32Array(MAX * 4);
  const tone = new Float32Array(MAX * 4);
  const more = new Float32Array(MAX * 2);

  let textureWidth = 0;
  let textureHeight = 0;
  let disposed = false;
  let enabled = false;
  let frame = 0;
  let awakeUntil = 0;
  let hooked = null; // the video whose frames draw the glass
  let uploaded = null; // the still source last uploaded
  let failed = false;

  // A frame of the wallpaper into the texture: every frame for one that
  // moves, once for a still one. A wallpaper from another site without
  // CORS can't be read, and the page falls back to the CSS glass.
  function upload(el, moving) {
    if (!moving && uploaded === el) return true;
    try {
      if (textureWidth !== el.width || textureHeight !== el.height) {
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, el);
        if (gl.getError() !== gl.NO_ERROR) return false;
        textureWidth = el.width;
        textureHeight = el.height;
      } else {
        gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, gl.RGBA, gl.UNSIGNED_BYTE, el);
      }
      uploaded = el;
      return true;
    } catch {
      return false;
    }
  }

  // fresh: the wallpaper has a new frame, so upload it even if it isn't
  // playing (a paused video that was seeked).
  function draw(fresh = false) {
    const { el, moving } = source() || {};
    const width = el && (el.videoWidth || el.naturalWidth || el.width);
    const height = el && (el.videoHeight || el.naturalHeight || el.height);
    const ready = width && height && !(el.readyState !== undefined && el.readyState < 2);
    if (!ready) return;
    if (!upload(el, moving || fresh)) {
      failed = true;
      canvas.dispatchEvent(new Event('refractionfailed'));
      return;
    }
    const cssWidth = canvas.clientWidth;
    const cssHeight = canvas.clientHeight;
    const { width: w, height: h, scale } = boundedSize(cssWidth, cssHeight, {
      pixels: maxRenderPixels, edge: maxRenderEdge, ratio: Math.min(devicePixelRatio || 1, maxPixelRatio),
    });
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    // object-fit: cover, centred.
    const cover = Math.max(cssWidth / width, cssHeight / height);
    const dw = width * cover;
    const dh = height * cover;
    const list = surfaces().slice(0, MAX);
    list.forEach((s, i) => {
      rect.set([s.x, s.y, s.w, s.h], i * 4);
      shape.set([s.radius, s.rim, s.edge, s.power], i * 4);
      tone.set([s.blur, s.saturate, s.contrast, s.brightness], i * 4);
      more.set([s.keep, s.dispersion], i * 2);
    });
    gl.viewport(0, 0, w, h);
    gl.uniform2f(u.res, w, h);
    gl.uniform1f(u.scale, scale);
    gl.uniform4f(u.cover, 1 / dw, 1 / dh, -(cssWidth - dw) / 2 / dw, -(cssHeight - dh) / 2 / dh);
    gl.uniform1i(u.count, list.length);
    gl.uniform4fv(u.rect, rect);
    gl.uniform4fv(u.shape, shape);
    gl.uniform4fv(u.tone, tone);
    gl.uniform2fv(u.more, more);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  }

  // Each new frame of a playing video draws the glass, in step with it.
  function hookVideo(video) {
    if (hooked === video || !video.requestVideoFrameCallback) return;
    hooked = video;
    const onFrame = () => {
      if (hooked !== video || !enabled) return;
      draw(true);
      video.requestVideoFrameCallback(onFrame);
    };
    video.requestVideoFrameCallback(onFrame);
  }

  function loop(now) {
    frame = 0;
    if (!enabled || document.hidden) return;
    const src = source();
    const video = src?.el?.tagName === 'VIDEO' && src.el.requestVideoFrameCallback ? src.el : null;
    if (video) hookVideo(video);
    else hooked = null;
    // Moving wallpapers that say when they have a frame draw then; the rest
    // (a video without frame callbacks) draw on every display frame.
    const timed = Boolean(video) || src?.external;
    const awake = now < awakeUntil;
    if (awake || (src?.moving && !timed)) draw();
    if (awake || (src?.moving && !timed)) frame = requestAnimationFrame(loop);
  }

  function wake() {
    // Whaleness owns a capped scene clock; avoid a second full-rate loop.
    if (externallyDriven) {
      if (enabled && !document.hidden) draw(true);
      return;
    }
    awakeUntil = performance.now() + AWAKE_MS;
    if (!frame && enabled) frame = requestAnimationFrame(loop);
  }

  if (!externallyDriven) document.addEventListener('visibilitychange', wake);

  return {
    get failed() {
      return failed;
    },
    setEnabled(on) {
      if (enabled === on) return;
      enabled = on;
      canvas.hidden = !on;
      if (on) {
        failed = false;
        if (!externallyDriven) wake();
      }
    },
    // The wallpaper (a canvas drawn elsewhere) has a new frame.
    frame({ fresh = true } = {}) {
      if (enabled && !disposed && !document.hidden) draw(fresh);
    },
    configure({ pixelRatio, pixels, edge }) {
      maxPixelRatio = pixelRatio;
      maxRenderPixels = pixels;
      maxRenderEdge = edge;
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      enabled = false;
      cancelAnimationFrame(frame);
      document.removeEventListener('visibilitychange', wake);
      gl.useProgram(null);
      gl.bindTexture(gl.TEXTURE_2D, null);
      gl.bindBuffer(gl.ARRAY_BUFFER, null);
      gl.deleteTexture(texture);
      gl.deleteBuffer(buffer);
      gl.deleteProgram(program);
      gl.deleteShader(vertex);
      gl.deleteShader(fragment);
      canvas.width = canvas.height = 1;
      canvas.hidden = true;
    },
    // A new wallpaper: upload it afresh.
    reset() {
      uploaded = null;
      failed = false;
      wake();
    },
    wake,
  };
}
