// Búsqueda de canciones con la API oficial de YouTube (Data API v3).
// La comparten el reproductor (app.js) y la página de la Jam (jam.js).

const CACHE_MS = 10 * 60 * 1000;

function decodeEntities(s) {
  const t = document.createElement("textarea");
  t.innerHTML = s || "";
  return t.value;
}

export function isoDurationMs(iso) {
  const m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso || "");
  return m ? ((+m[1] || 0) * 3600 + (+m[2] || 0) * 60 + (+m[3] || 0)) * 1000 : undefined;
}

async function youtubeApi(endpoint, params, key) {
  const url = new URL(`https://www.googleapis.com/youtube/v3/${endpoint}`);
  url.search = new URLSearchParams({ ...params, key }).toString();
  const res = await fetch(url);
  const data = await res.json();
  if (!res.ok) {
    const reason = data?.error?.errors?.[0]?.reason || data?.error?.message || res.status;
    throw new Error(reason === "quotaExceeded" ? "Se ha agotado la cuota diaria de búsquedas de YouTube" : `Error de YouTube: ${reason}`);
  }
  return data;
}

async function youtubeSearchRaw(query, extra, key) {
  const data = await youtubeApi("search", {
    part: "snippet", type: "video", maxResults: "20", q: query,
    regionCode: "ES", relevanceLanguage: "es", ...extra,
  }, key);
  const items = data.items || [];
  if (!items.length) return [];
  // Estado de cada vídeo: si se puede incrustar y cuánto dura.
  const details = await youtubeApi("videos", {
    part: "status,contentDetails", id: items.map((i) => i.id.videoId).join(","),
  }, key).catch(() => ({ items: [] }));
  const info = Object.fromEntries((details.items || []).map((v) => [v.id, v]));
  return items.map((item) => describe(item.id.videoId, item.snippet, info[item.id.videoId]));
}

const simplify = (s) => String(s || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]/g, "");

/** Título/artista limpios a partir de los datos de YouTube. [detail]: videos.list (estado y duración). */
function describe(videoId, snippet, detail) {
  const rawTitle = decodeEntities(snippet.title);
  const channel = decodeEntities(snippet.channelTitle).replace(/\s*-\s*Topic$/i, "").replace(/VEVO$/i, "").replace(/\s*(official|oficial)$/i, "").trim();
  let title = rawTitle;
  let artist = channel;
  const m = rawTitle.match(/^(.+?)\s[-–—|]\s(.+)$/);
  if (m) {
    // "Artista - Canción" o "Canción - Artista": manda el nombre del canal.
    const [left, right] = [m[1].trim(), m[2].trim()];
    const ch = simplify(channel);
    const rightIsArtist = ch && simplify(right).startsWith(ch) && !simplify(left).startsWith(ch);
    [artist, title] = rightIsArtist ? [right, left] : [left, right];
    // "BENY JR" → "Beny Jr" si el canal lo escribe así.
    if (ch && simplify(artist) === ch) artist = channel;
  }
  title = title.replace(/\s*[([](official|video|vídeo|audio|lyric|letra|visuali[sz]er|videoclip|hd|4k|mv)[^)\]]*[)\]]/gi, "").trim() || rawTitle;
  const th = snippet.thumbnails || {};
  return {
    title,
    artist,
    videoId,
    artworkUrl: (th.high || th.medium || th.default || {}).url,
    durationMs: detail ? isoDurationMs(detail.contentDetails?.duration) : undefined,
    // Sin datos (p. ej. falló la consulta) se intenta igualmente.
    playable: detail ? detail.status?.embeddable !== false : true,
  };
}

/** Un elemento de videos.list (part=snippet,contentDetails,status) → canción. */
export function parseVideo(item) {
  return describe(item.id, item.snippet || {}, item);
}

/**
 * Busca sin filtros (los filtros de categoría/incrustable escondían muchas
 * canciones) y marca con `playable: false` las que YouTube no deja reproducir
 * fuera de su web. Si ninguna de las primeras se puede, añade arriba una
 * versión reproducible (audio/letra). Los resultados se guardan 10 minutos
 * para no gastar cuota repitiendo búsquedas.
 */
export async function youtubeSearch(query, key, { fallback = true } = {}) {
  const cacheKey = `yt-search:${fallback ? 1 : 0}:${query.trim().toLowerCase()}`;
  try {
    const hit = JSON.parse(sessionStorage.getItem(cacheKey) || "null");
    if (hit && Date.now() - hit.at < CACHE_MS) return hit.results;
  } catch { /* sin almacenamiento: buscar */ }

  let results = await youtubeSearchRaw(query, {}, key);
  if (fallback && results.length && !results.slice(0, 5).some((t) => t.playable)) {
    const alt = await youtubeSearchRaw(query, { videoEmbeddable: "true", videoSyndicated: "true" }, key);
    const seen = new Set(results.map((t) => t.videoId));
    results = [...alt.filter((t) => t.playable && !seen.has(t.videoId)), ...results];
  }
  try { sessionStorage.setItem(cacheKey, JSON.stringify({ at: Date.now(), results })); } catch { /* da igual */ }
  return results;
}
