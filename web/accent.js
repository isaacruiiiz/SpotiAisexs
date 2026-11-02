// Acento "color de portada": saca el tono dominante de la portada que suena y
// lo aplica a la página (--accent, --accent-ink, --tint). Si la imagen no se
// puede leer (sin CORS), el tono sale del identificador de la canción, así
// cada canción tiene igualmente su color.

const cache = new Map();
const FALLBACK = { h: 16, s: 80 };

function hashHue(text) {
  let h = 0;
  for (const c of String(text || "")) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return h % 360;
}

function rgbToHsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return { h: 0, s: 0, l };
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h;
  if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
  else if (max === g) h = (b - r) / d + 2;
  else h = (r - g) / d + 4;
  return { h: h * 60, s, l };
}

/** Tono dominante "con color" (ignora grises, negros y blancos). */
function dominantHue(img) {
  const size = 24;
  const canvas = document.createElement("canvas");
  canvas.width = size; canvas.height = size;
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  ctx.drawImage(img, 0, 0, size, size);
  const data = ctx.getImageData(0, 0, size, size).data; // lanza si la imagen no tiene CORS
  const buckets = new Array(36).fill(0);
  const sat = new Array(36).fill(0);
  for (let i = 0; i < data.length; i += 4) {
    const { h, s, l } = rgbToHsl(data[i], data[i + 1], data[i + 2]);
    if (s < 0.22 || l < 0.12 || l > 0.92) continue;
    const k = Math.floor(h / 10) % 36;
    const w = s * (1 - Math.abs(l - 0.5));
    buckets[k] += w;
    sat[k] += s * w;
  }
  let best = -1, bestW = 0;
  buckets.forEach((w, k) => { if (w > bestW) { bestW = w; best = k; } });
  if (best < 0) return null;
  return { h: best * 10 + 5, s: Math.round(Math.min(90, Math.max(45, (sat[best] / bestW) * 100))) };
}

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = url;
  });
}

export async function accentFor(url, key) {
  const id = url || key || "";
  if (cache.has(id)) return cache.get(id);
  let tone = null;
  if (url) {
    try { tone = dominantHue(await loadImage(url)); } catch { tone = null; }
  }
  if (!tone) tone = key ? { h: hashHue(key), s: 70 } : FALLBACK;
  cache.set(id, tone);
  return tone;
}

/** Aplica el tono a las variables CSS de :root. */
export function applyAccent(tone, root = document.documentElement) {
  const { h, s } = tone || FALLBACK;
  root.style.setProperty("--accent", `hsl(${h} ${s}% 80%)`);
  root.style.setProperty("--accent-strong", `hsl(${h} ${Math.min(95, s + 5)}% 68%)`);
  root.style.setProperty("--accent-ink", `hsl(${h} 70% 12%)`);
  root.style.setProperty("--tint", `hsl(${h} ${Math.round(s * 0.7)}% 24% / 0.30)`);
  root.style.setProperty("--tint-edge", `hsl(${h} ${s}% 80% / 0.20)`);
  root.style.setProperty("--glow", `hsl(${h} ${s}% 60% / 0.35)`);
}
