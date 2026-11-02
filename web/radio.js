// Radio de la web, igual que la del teléfono (RadioRecommender.kt) pero sin servidor:
//
// · Candidatas: la "Mix" de YouTube de la canción semilla (lista RDAMVM<id>,
//   hasta 100 canciones relacionadas). Se lee con un reproductor de YouTube
//   oculto, que sí puede cargarla desde cualquier web. Los títulos y la
//   duración salen de la API de datos (videos.list: 1 unidad de cuota por
//   cada 50 canciones, casi gratis).
// · Dos modos, como en el teléfono:
//   - "focused": pusiste una canción concreta → la cola se queda en su
//     entorno (su artista y los que YouTube pone justo al lado).
//   - "explore": radio infinita / mezclas → variedad según tus gustos.
// · Puntuación: lo bien que encaja (posición en la Mix y acuerdo entre
//   varias semillas), tus gustos (artistas de tu biblioteca), descanso para
//   lo escuchado hace poco, castigo a lo que saltas y a repetir artista.
//   Se sortea con esos pesos (Gumbel top-k), así dos radios de la misma
//   canción se parecen pero no son idénticas, y se separan los artistas.

import { parseVideo } from "./youtube.js";

const HISTORY_KEY = "spotiaisexs-radio-history";
const HARD_COOLDOWN_MS = 36 * 3600 * 1000;
const DAY_MS = 24 * 3600 * 1000;
const ARTIST_CAP_PER_BATCH = 2;
const ARTIST_SPACING = 4;
const TEMPERATURE = 0.7;
const FOCUSED_MIX_LIMIT = 25;
const NEIGHBOURHOOD_TOP_RANK = 15;
const NEIGHBOURHOOD_MAX = 45;
const BLOCKED_TITLE = /(mash ?-?up|megamix|full album|[aá]lbum completo|1 hora|1 hour|playlist|compilation|recopilaci|en vivo|live at|\blive\b|sped ?up|slowed|nightcore|8d audio|karaoke|instrumental|reaction|reacci[oó]n|tutorial|cover\b)/i;

// ── Historial local (escuchas y saltos) ────────────────────────────────
const storage = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* sin almacenamiento */ } },
};
const historyKey = (t) => `${(t.title || "").trim()}|${(t.artist || "").trim()}`.toLowerCase();
function loadHistory() {
  try { return JSON.parse(storage.get(HISTORY_KEY) || "{}"); } catch { return {}; }
}
function saveHistory(h) {
  // Solo las 600 más recientes: el historial no crece sin fin.
  const entries = Object.entries(h).sort((a, b) => (b[1].last || 0) - (a[1].last || 0)).slice(0, 600);
  storage.set(HISTORY_KEY, JSON.stringify(Object.fromEntries(entries)));
}
export function recordPlay(track) {
  if (!track?.title) return;
  const h = loadHistory(); const k = historyKey(track);
  h[k] = { plays: (h[k]?.plays || 0) + 1, skips: h[k]?.skips || 0, last: Date.now() };
  saveHistory(h);
}
export function recordSkip(track) {
  if (!track?.title) return;
  const h = loadHistory(); const k = historyKey(track);
  h[k] = { plays: h[k]?.plays || 0, skips: (h[k]?.skips || 0) + 1, last: Date.now() };
  saveHistory(h);
}

// ── Artistas ───────────────────────────────────────────────────────────
const ARTIST_SPLIT = /\s*(?:,|&|\+|\/|\bx\b|\bfeat\.?|\bft\.?|\bwith\b|\by\b)\s*/;
export const artistTokens = (artist) => new Set(
  String(artist || "").toLowerCase().split(ARTIST_SPLIT).map((s) => s.trim()).filter((s) => s.length > 1),
);
/** Artista principal ("Beny Jr x Morad" → "beny jr"): para limitar, espaciar y gustos. */
const mainArtist = (t) => [...artistTokens(t.artist)][0] || (t.artist || "").trim().toLowerCase();
const coreTitle = (title) => String(title || "").toLowerCase()
  .replace(/\s*[([].*?[)\]]/g, "").replace(/\s*-\s*.*$/, "").replace(/\b(feat|ft)\.?.*$/, "")
  .normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9 ]/g, "").replace(/\s+/g, " ").trim();

// ── La Mix de YouTube, leída con un reproductor oculto ─────────────────
const mixCache = new Map();
function whenYouTube() {
  return new Promise((resolve, reject) => {
    let waited = 0;
    const wait = () => {
      if (window.YT?.Player) return resolve();
      if ((waited += 200) > 15000) return reject(new Error("YouTube no carga"));
      setTimeout(wait, 200);
    };
    wait();
  });
}

/** Un reproductor oculto y de usar y tirar por Mix: así nunca lee la lista anterior. */
async function readMix(videoId) {
  await whenYouTube();
  const holder = document.createElement("div");
  holder.setAttribute("aria-hidden", "true");
  holder.style.cssText = "position:fixed;left:-400px;top:0;width:200px;height:120px;opacity:0;pointer-events:none";
  const el = document.createElement("div");
  holder.appendChild(el);
  document.body.appendChild(holder);
  try {
    return await new Promise((resolve) => {
      let player;
      const timeout = setTimeout(() => resolve([]), 10_000);
      player = new YT.Player(el, {
        width: 200, height: 120,
        playerVars: { listType: "playlist", list: `RDAMVM${videoId}`, autoplay: 0, mute: 1, controls: 0 },
        events: {
          onReady: () => {
            let tries = 0;
            const poll = setInterval(() => {
              const list = player.getPlaylist?.();
              if (list?.length || ++tries > 30) {
                clearInterval(poll);
                clearTimeout(timeout);
                resolve(list || []);
              }
            }, 250);
          },
          onError: () => { clearTimeout(timeout); resolve([]); },
        },
      });
    });
  } finally {
    holder.remove();
  }
}

let mixQueue = Promise.resolve();
/** Ids de la Mix de [videoId] (en el orden de YouTube). Una a la vez. */
export function mixIds(videoId) {
  if (!videoId) return Promise.resolve([]);
  if (mixCache.has(videoId)) return Promise.resolve(mixCache.get(videoId));
  const job = mixQueue.then(async () => {
    if (mixCache.has(videoId)) return mixCache.get(videoId);
    const ids = (await readMix(videoId)).filter((id) => id && id !== videoId);
    if (ids.length) mixCache.set(videoId, ids);
    return ids;
  });
  mixQueue = job.catch(() => []);
  return job.catch(() => []);
}

// ── Metadatos con la API de YouTube (1 unidad por cada 50) ─────────────
const metaCache = new Map();
async function videoMeta(ids, key) {
  const missing = ids.filter((id) => !metaCache.has(id));
  for (let i = 0; i < missing.length; i += 50) {
    const chunk = missing.slice(i, i + 50);
    const url = new URL("https://www.googleapis.com/youtube/v3/videos");
    url.search = new URLSearchParams({ part: "snippet,contentDetails,status", id: chunk.join(","), key }).toString();
    const res = await fetch(url);
    const data = await res.json();
    if (!res.ok) throw new Error(data?.error?.errors?.[0]?.reason === "quotaExceeded" ? "Se ha agotado la cuota de YouTube por hoy" : "YouTube no responde");
    (data.items || []).forEach((item) => metaCache.set(item.id, parseVideo(item)));
    chunk.forEach((id) => { if (!metaCache.has(id)) metaCache.set(id, null); });
  }
  return ids.map((id) => metaCache.get(id)).filter(Boolean);
}

// ── Selección (port de RadioRecommender.kt) ────────────────────────────
function gumbel() {
  const u = Math.min(1 - 1e-12, Math.max(1e-12, Math.random()));
  return -Math.log(-Math.log(u));
}

function score(c, ctx) {
  const focused = ctx.style === "focused";
  let s = c.sources.size >= 3 ? 2.2 : c.sources.size === 2 ? 1.7 : 1.0;
  s += focused ? 2.0 / (1 + c.rank / 6) : 1.0 / (1 + c.rank / 8);
  const a = mainArtist(c.track);
  if (ctx.tasteArtists.has(a)) s += focused ? 0.2 : 0.6;
  const h = ctx.history[historyKey(c.track)];
  if (!h) s += 0.4;
  else {
    const days = (Date.now() - (h.last || 0)) / DAY_MS;
    s -= days < 3 ? 1.6 : days < 7 ? 0.9 : days < 14 ? 0.4 : 0;
    if (days < 14 && h.plays >= 5) s -= 0.5;
    const total = (h.plays || 0) + (h.skips || 0);
    if (total) s -= (2 * (h.skips || 0)) / total;
  }
  s -= 0.45 * ctx.tailArtists.filter((x) => x === a).length;
  return s;
}

function sample(scored, count, artistCounts) {
  const ranked = scored.map(([c, s]) => [c, s / TEMPERATURE + gumbel()]).sort((x, y) => y[1] - x[1]);
  const out = [];
  for (const [c] of ranked) {
    if (out.length >= count) break;
    const a = mainArtist(c.track);
    if ((artistCounts.get(a) || 0) >= ARTIST_CAP_PER_BATCH) continue;
    artistCounts.set(a, (artistCounts.get(a) || 0) + 1);
    out.push(c);
  }
  return out;
}

function spaceArtists(tracks, tail) {
  const remaining = tracks.slice();
  const windowArtists = tail.slice(-ARTIST_SPACING);
  const out = [];
  while (remaining.length) {
    let idx = remaining.findIndex((t) => !windowArtists.includes(mainArtist(t)));
    if (idx < 0) idx = 0;
    const [next] = remaining.splice(idx, 1);
    out.push(next);
    windowArtists.push(mainArtist(next));
    while (windowArtists.length > ARTIST_SPACING) windowArtists.shift();
  }
  return out;
}

/**
 * Una radio: guarda su semilla de origen, su "vecindario" de artistas y lo ya
 * sugerido, y entrega tandas con next().
 */
export class Radio {
  constructor({ seed, style = "focused", apiKey, libraryTracks = () => [] }) {
    this.anchor = seed;
    this.style = style;
    this.apiKey = apiKey;
    this.libraryTracks = libraryTracks;
    this.usedSeeds = new Set();
    this.suggested = new Set();
    this.neighbourhood = new Set();
  }

  inNeighbourhood(artist) {
    for (const t of artistTokens(artist)) if (this.neighbourhood.has(t)) return true;
    return false;
  }

  /** Siguiente tanda. [queue] = cola actual (para no repetir y espaciar artistas). */
  async next(queue, batchSize = 20) {
    const focused = this.style === "focused";
    const library = this.libraryTracks();
    const known = new Set(queue.map((t) => t.videoId));
    const knownCore = new Set(queue.map((t) => coreTitle(t.title)));
    const upcoming = queue.slice(-12);

    // 1. Semillas: la última canción en el vecindario que aún no se usó (o la de origen).
    const seeds = [];
    const primary = focused
      ? [...upcoming].reverse().find((t) => t.videoId && !this.usedSeeds.has(t.videoId) && (this.neighbourhood.size === 0 || this.inNeighbourhood(t.artist)))
      : [...upcoming].reverse().find((t) => t.videoId && !this.usedSeeds.has(t.videoId));
    seeds.push(primary || this.anchor);
    if (!focused) {
      // Exploración: además, un rincón al azar de tu biblioteca.
      const pool = library.filter((t) => t.videoId && !this.usedSeeds.has(t.videoId) && mainArtist(t) !== mainArtist(seeds[0]));
      // Dos artistas distintos al azar: la Mix de un artista tira mucho hacia él mismo.
      const picked = new Set([mainArtist(seeds[0])]);
      for (const t of pool.sort(() => Math.random() - 0.5)) {
        if (seeds.length >= 3) break;
        if (picked.has(mainArtist(t))) continue;
        picked.add(mainArtist(t));
        seeds.push(t);
      }
    }

    // 2. Mix de cada semilla.
    const candidates = new Map(); // id → { sources:Set, rank }
    for (const [i, seed] of seeds.entries()) {
      if (!seed?.videoId) continue;
      this.usedSeeds.add(seed.videoId);
      let ids = await mixIds(seed.videoId);
      if (focused || i > 0) ids = ids.slice(0, focused ? FOCUSED_MIX_LIMIT : 15);
      ids.forEach((id, rank) => {
        const c = candidates.get(id) || { sources: new Set(), rank: 99 };
        c.sources.add(seed.videoId);
        c.rank = Math.min(c.rank, rank + (i > 0 ? 3 : 0));
        candidates.set(id, c);
      });
    }
    if (!candidates.size) return [];

    const metas = await videoMeta([...candidates.keys()], this.apiKey);
    const primarySeed = seeds[0];

    // Vecindario: artistas de la semilla y de lo más alto de su Mix.
    if (this.neighbourhood.size === 0 || (this.inNeighbourhood(primarySeed.artist) && this.neighbourhood.size < NEIGHBOURHOOD_MAX)) {
      artistTokens(primarySeed.artist).forEach((t) => this.neighbourhood.add(t));
      metas.forEach((m) => {
        const c = candidates.get(m.videoId);
        if (c && c.sources.has(primarySeed.videoId) && c.rank < NEIGHBOURHOOD_TOP_RANK) artistTokens(m.artist).forEach((t) => this.neighbourhood.add(t));
      });
    }

    // 3. Filtros duros.
    const history = (() => { try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || "{}"); } catch { return {}; } })();
    let eligible = [];
    for (const m of metas) {
      const c = candidates.get(m.videoId);
      if (!m.playable || known.has(m.videoId) || this.suggested.has(m.videoId)) continue;
      if (m.durationMs && (m.durationMs < 60_000 || m.durationMs > 12 * 60_000)) continue;
      if (BLOCKED_TITLE.test(m.title) || !m.title || !m.artist) continue;
      const core = coreTitle(m.title);
      if (core && knownCore.has(core)) continue;
      knownCore.add(core);
      eligible.push({ track: m, sources: c.sources, rank: c.rank });
    }
    const fresh = eligible.filter((c) => {
      const h = history[historyKey(c.track)];
      return !h || Date.now() - (h.last || 0) > HARD_COOLDOWN_MS;
    });
    if (fresh.length >= batchSize / 2) eligible = fresh;

    // 4. Enfocada: solo el vecindario (mejor una tanda corta que una que no pega).
    if (focused && this.neighbourhood.size) {
      const near = eligible.filter((c) => this.inNeighbourhood(c.track.artist) || c.sources.size >= 2 || (c.sources.has(primarySeed.videoId) && c.rank < NEIGHBOURHOOD_TOP_RANK));
      eligible = near.length ? near : eligible.filter((c) => c.sources.has(primarySeed.videoId)).slice(0, Math.ceil(batchSize / 3));
    }
    if (!eligible.length) return [];

    // 5. Puntuar, sortear y espaciar.
    const ctx = {
      style: this.style,
      history,
      tasteArtists: new Set(library.map(mainArtist)),
      tailArtists: upcoming.map(mainArtist),
    };
    const scored = eligible.map((c) => [c, score(c, ctx)]);
    const discoveryShare = focused ? 0.1 : 0.4;
    const isDiscovery = ([c]) => !ctx.tasteArtists.has(mainArtist(c.track)) && !history[historyKey(c.track)];
    const discovery = scored.filter(isDiscovery);
    const familiar = scored.filter((x) => !isDiscovery(x));
    const discoveryTarget = Math.min(discovery.length, Math.floor(batchSize * discoveryShare));
    const familiarTarget = Math.min(familiar.length, batchSize - discoveryTarget);
    const counts = new Map();
    let picked = [...sample(familiar, familiarTarget, counts), ...sample(discovery, discoveryTarget, counts)];
    if (picked.length < batchSize) {
      const taken = new Set(picked.map((c) => c.track.videoId));
      picked = picked.concat(sample(scored.filter(([c]) => !taken.has(c.track.videoId)), batchSize - picked.length, counts));
    }
    const tracks = spaceArtists(picked.map((c) => c.track), upcoming.map(mainArtist));
    tracks.forEach((t) => this.suggested.add(t.videoId));
    return tracks.map((t) => ({ ...t, fromRadio: true }));
  }
}
