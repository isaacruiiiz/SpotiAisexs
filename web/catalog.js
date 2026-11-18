// Catálogo de YouTube Music para la web: buscar, artistas, álbumes, playlists y
// canciones relacionadas (la radio). Habla con el Worker de web/worker/, que
// reenvía las consultas; aquí se interpretan las respuestas igual que lo hace
// la app (InnerTubeMusicApi.kt).

import * as cfg from "./config.js";

const BASE = String(cfg.catalogUrl || "").trim().replace(/\/+$/, "");
/** Hay puente de catálogo configurado: la web no depende del teléfono ni de la clave de YouTube. */
export const catalogReady = !!BASE;

const SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA==";
const VIDEOS = "EgWKAQIQAWoKEAkQBRAKEAMQBA==";
const ARTISTS = "EgWKAQIgAWoKEAkQBRAKEAMQBA==";
const ALBUMS = "EgWKAQIYAWoKEAkQBRAKEAMQBA==";
const CACHE_MS = 10 * 60 * 1000;
const cache = new Map();

async function call(endpoint, body) {
  if (!BASE) throw new Error("Falta configurar el catálogo");
  const key = endpoint + JSON.stringify(body);
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.data;
  let res;
  try {
    res = await fetch(`${BASE}/${endpoint}`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  } catch {
    throw new Error("No se pudo conectar con el catálogo");
  }
  if (!res.ok) throw new Error(`El catálogo ha respondido con un error (${res.status})`);
  const data = await res.json();
  cache.set(key, { at: Date.now(), data });
  if (cache.size > 80) cache.delete(cache.keys().next().value);
  return data;
}

// ── Utilidades para recorrer las respuestas ────────────────────────────
/** Todos los objetos guardados bajo la clave [key], a cualquier profundidad. */
export function collect(node, key, out = []) {
  if (Array.isArray(node)) node.forEach((n) => collect(n, key, out));
  else if (node && typeof node === "object") {
    for (const [k, v] of Object.entries(node)) {
      if (k === key && v && typeof v === "object" && !Array.isArray(v)) out.push(v);
      collect(v, key, out);
    }
  }
  return out;
}
function findString(node, key) {
  if (Array.isArray(node)) { for (const n of node) { const f = findString(n, key); if (f) return f; } return null; }
  if (node && typeof node === "object") {
    if (typeof node[key] === "string" && node[key]) return node[key];
    for (const v of Object.values(node)) { const f = findString(v, key); if (f) return f; }
  }
  return null;
}
const runsText = (obj) => (obj?.runs || []).map((r) => r?.text || "").join("").trim() || (obj?.simpleText || "").trim();
const browseIdOf = (run) => run?.navigationEndpoint?.browseEndpoint?.browseId || "";

export function hiRes(url) {
  if (!url) return "";
  const u = url.startsWith("//") ? `https:${url}` : url;
  return (/googleusercontent\.com|ggpht\.com/.test(u) && u.includes("=")) ? `${u.slice(0, u.lastIndexOf("="))}=w544-h544-l90-rj` : u;
}
function thumbOf(renderer) {
  const lists = [];
  (function walk(n) {
    if (Array.isArray(n)) n.forEach(walk);
    else if (n && typeof n === "object") {
      if (Array.isArray(n.thumbnails) && n.thumbnails.length) lists.push(n.thumbnails);
      Object.values(n).forEach(walk);
    }
  })(renderer?.thumbnail || renderer?.thumbnailRenderer || renderer);
  const best = lists[0]?.[lists[0].length - 1]?.url;
  return best ? hiRes(best) : "";
}
function parseDuration(text) {
  const m = /^(?:(\d+):)?(\d{1,2}):(\d{2})$/.exec(String(text || "").trim());
  return m ? ((Number(m[1] || 0) * 3600) + Number(m[2]) * 60 + Number(m[3])) * 1000 : 0;
}
const NOT_ARTIST = /^(song|video|album|single|ep|playlist|artist|•|·|\d[\d.,]*\s*[kmb]?\s*(views|plays|subscribers|likes)?|(19|20)\d{2})$/i;
const likelyArtist = (t) => !!t && !NOT_ARTIST.test(t.trim()) && !parseDuration(t);

function watchId(r) {
  return r?.playlistItemData?.videoId
    || r?.navigationEndpoint?.watchEndpoint?.videoId
    || (r?.overlay || r?.thumbnailOverlay)?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId
    || r?.onTap?.watchEndpoint?.videoId
    || r?.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.navigationEndpoint?.watchEndpoint?.videoId
    || null;
}

function trackFromRuns(videoId, title, runs, renderer, extra = {}) {
  const artistRuns = runs.filter((r) => browseIdOf(r).startsWith("UC"));
  const artist = artistRuns.length
    ? artistRuns.map((r) => r.text).join(", ")
    : (runs.map((r) => r.text).find(likelyArtist) || "");
  const albumRun = runs.find((r) => browseIdOf(r).startsWith("MPRE"));
  const durationMs = runs.map((r) => parseDuration(r.text)).find(Boolean) || 0;
  const track = {
    videoId, title, artist,
    artworkUrl: thumbOf(renderer) || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
    ...extra,
  };
  if (albumRun) { track.album = albumRun.text; track.albumId = browseIdOf(albumRun); }
  if (artistRuns[0]) track.artistId = browseIdOf(artistRuns[0]);
  if (durationMs) track.durationMs = durationMs;
  return track;
}

/** Fila de canción (resultado de búsqueda, canción de artista, de álbum o de playlist). */
function parseSong(r) {
  const videoId = watchId(r);
  if (!videoId) return null;
  const cols = r.flexColumns || [];
  const title = runsText(cols[0]?.musicResponsiveListItemFlexColumnRenderer?.text) || runsText(r.title);
  if (!title) return null;
  const runs = [
    ...cols.slice(1).flatMap((c) => c?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || []),
    ...(r.fixedColumns || []).flatMap((c) => c?.musicResponsiveListItemFixedColumnRenderer?.text?.runs || []),
    ...(r.subtitle?.runs || []),
  ];
  return trackFromRuns(videoId, title, runs, r);
}

/** Canción de la lista "A continuación" (endpoint next): la base de la radio. */
function parsePanelSong(r) {
  if (r.unplayableText) return null;
  const videoId = r.videoId || r.navigationEndpoint?.watchEndpoint?.videoId;
  const title = runsText(r.title);
  if (!videoId || !title) return null;
  const runs = (r.longBylineText || r.shortBylineText)?.runs || [];
  const track = trackFromRuns(videoId, title, runs, r);
  const length = (r.lengthText?.runs || []).map((x) => parseDuration(x.text)).find(Boolean);
  if (length) track.durationMs = length;
  return track;
}

function songsIn(root) {
  const out = [];
  collect(root, "musicResponsiveListItemRenderer").forEach((r) => { const t = parseSong(r); if (t) out.push(t); });
  collect(root, "playlistPanelVideoRenderer").forEach((r) => { const t = parsePanelSong(r); if (t) out.push(t); });
  const seen = new Set();
  return out.filter((t) => !seen.has(t.videoId) && seen.add(t.videoId));
}

function entitiesIn(root, prefix) {
  const out = [];
  collect(root, "musicResponsiveListItemRenderer").forEach((r) => {
    const browseId = r.navigationEndpoint?.browseEndpoint?.browseId || "";
    if (!browseId.startsWith(prefix)) return;
    const cols = r.flexColumns || [];
    const name = runsText(cols[0]?.musicResponsiveListItemFlexColumnRenderer?.text);
    if (!name) return;
    const details = cols[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
    const artistRun = details.find((x) => browseIdOf(x).startsWith("UC"));
    const year = details.map((x) => (x.text || "").trim()).find((t) => /^(19|20)\d{2}$/.test(t));
    out.push({ browseId, name, artist: artistRun?.text || "", artistId: artistRun ? browseIdOf(artistRun) : "", year: year || "", artworkUrl: thumbOf(r) });
  });
  const seen = new Set();
  return out.filter((e) => !seen.has(e.browseId) && seen.add(e.browseId));
}

function twoRowItems(shelf) {
  return collect(shelf, "musicTwoRowItemRenderer").map((item) => {
    const title = runsText(item.title);
    const nav = item.navigationEndpoint?.browseEndpoint || item.title?.runs?.[0]?.navigationEndpoint?.browseEndpoint;
    const subs = (item.subtitle?.runs || []).map((r) => (r.text || "").trim());
    return {
      title,
      browseId: nav?.browseId || "",
      year: subs.find((t) => /^(19|20)\d{2}$/.test(t)) || "",
      type: subs.find((t) => /^(single|ep|album)$/i.test(t)) || "",
      subtitle: subs.filter((t) => t && t !== "•").join(" "),
      artworkUrl: thumbOf(item),
    };
  }).filter((x) => x.title && x.browseId);
}
const headingOf = (shelf) => runsText(shelf.title) || runsText(shelf.header?.musicCarouselShelfBasicHeaderRenderer?.title) || "";

// ── API pública ────────────────────────────────────────────────────────

/** Canciones, artistas y álbumes para [query]. Tres consultas en paralelo; si una falla, el resto vale. */
export async function search(query) {
  const q = query.trim();
  const [songs, artists, albums] = await Promise.allSettled([
    call("search", { query: q, params: SONGS }),
    call("search", { query: q, params: ARTISTS }),
    call("search", { query: q, params: ALBUMS }),
  ]);
  if (songs.status === "rejected" && artists.status === "rejected") throw songs.reason;
  return {
    songs: songs.status === "fulfilled" ? songsIn(songs.value).slice(0, 30) : [],
    artists: artists.status === "fulfilled" ? entitiesIn(artists.value, "UC").slice(0, 8) : [],
    albums: albums.status === "fulfilled" ? entitiesIn(albums.value, "MPRE").slice(0, 10) : [],
  };
}

/** Solo canciones (para la Jam, "Esto es" y buscar un artista por nombre). */
export async function searchSongs(query, limit = 20) {
  return songsIn(await call("search", { query: query.trim(), params: SONGS })).slice(0, limit);
}

/** Vídeos del mismo tema: se usan cuando YouTube no deja incrustar la versión "canción". */
export async function searchVideos(query, limit = 6) {
  return songsIn(await call("search", { query: query.trim(), params: VIDEOS })).slice(0, limit);
}

export async function searchArtists(query, limit = 5) {
  return entitiesIn(await call("search", { query: query.trim(), params: ARTISTS }), "UC").slice(0, limit);
}

/** Canciones relacionadas con [videoId] (la "Mix" de YouTube Music), en su orden. */
export async function related(videoId) {
  const root = await call("next", { videoId, playlistId: `RDAMVM${videoId}`, params: "wAEB", isAudioOnly: true });
  return songsIn(root).filter((t) => t.videoId !== videoId);
}

export async function artist(browseId) {
  const root = await call("browse", { browseId });
  const header = root.header?.musicVisualHeaderRenderer || root.header?.musicImmersiveHeaderRenderer || root.header?.musicHeaderRenderer || {};
  const name = runsText(header.title) || "Artista";
  const subscribers = runsText(header.subscriptionButton?.subscribeButtonRenderer?.subscriberCountText) || "";
  const artworkUrl = thumbOf(header.foregroundThumbnail || header.thumbnail || header);
  const data = { browseId, name, subscribers, bio: runsText(header.description), artworkUrl, topSongs: [], albums: [], singles: [], similar: [] };
  const shelves = [...collect(root, "musicShelfRenderer"), ...collect(root, "musicCarouselShelfRenderer")];
  let more = null;
  for (const shelf of shelves) {
    const heading = headingOf(shelf).toLowerCase();
    if (/song|track/.test(heading)) {
      if (data.topSongs.length) continue;
      data.topSongs = songsIn(shelf).map((t) => ({ ...t, artist: t.artist || name }));
      more = shelf.bottomEndpoint?.browseEndpoint
        || shelf.title?.runs?.[0]?.navigationEndpoint?.browseEndpoint
        || shelf.header?.musicCarouselShelfBasicHeaderRenderer?.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint
        || null;
    } else if (/album/.test(heading)) data.albums.push(...twoRowItems(shelf));
    else if (/single|\bep/.test(heading)) data.singles.push(...twoRowItems(shelf));
    else if (/similar|fans|like/.test(heading)) data.similar.push(...twoRowItems(shelf).filter((x) => x.browseId.startsWith("UC")));
  }
  // La página solo trae 5 canciones; "ver todas" lleva a la lista completa.
  if (more?.browseId) {
    try {
      const full = songsIn(await call("browse", { browseId: more.browseId, ...(more.params ? { params: more.params } : {}) }));
      if (full.length > data.topSongs.length) data.topSongs = full.slice(0, 60).map((t) => ({ ...t, artist: t.artist || name }));
    } catch { /* nos quedamos con las cinco */ }
  }
  return data;
}

function listHeader(root) {
  return root.header?.musicDetailHeaderRenderer
    || root.header?.musicResponsiveHeaderRenderer
    || root.header?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer
    || collect(root, "musicResponsiveHeaderRenderer")[0]
    || {};
}

export async function album(browseId) {
  const root = await call("browse", { browseId });
  const header = listHeader(root);
  const title = runsText(header.title) || "Álbum";
  const subRuns = [...(header.subtitle?.runs || []), ...(header.straplineTextOne?.runs || [])];
  const artistRun = subRuns.find((r) => browseIdOf(r).startsWith("UC"));
  const artistName = artistRun?.text || runsText(header.straplineTextOne) || "";
  const year = subRuns.map((r) => (r.text || "").trim()).find((t) => /^(19|20)\d{2}$/.test(t)) || "";
  const artworkUrl = thumbOf(header.thumbnail || header);
  const tracks = songsIn(root).map((t) => ({ ...t, artist: likelyArtist(t.artist) ? t.artist : artistName, album: title, artworkUrl: artworkUrl || t.artworkUrl }));
  return { browseId, title, artist: artistName, artistId: artistRun ? browseIdOf(artistRun) : "", year, artworkUrl, tracks };
}

/** Playlist pública de YouTube Music (id "PL…", "RDCLAK…" o "VL…"). */
export async function playlist(id) {
  const browseId = id.startsWith("VL") ? id : `VL${id}`;
  const root = await call("browse", { browseId });
  const header = listHeader(root);
  const tracks = songsIn(root);
  return { id, title: runsText(header.title) || "Playlist", author: runsText(header.straplineTextOne) || runsText(header.subtitle) || "", artworkUrl: thumbOf(header.thumbnail || header) || tracks[0]?.artworkUrl || "", tracks };
}

export { parseDuration as _parseDuration, songsIn as _songsIn, findString as _findString };
