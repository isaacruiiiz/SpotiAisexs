// SpotiAisexs web — reproductor completo que funciona solo o sincronizado con
// el teléfono (estilo Spotify Connect).
//
// · Sin teléfono: biblioteca (playlists que el teléfono subió a Firebase),
//   búsqueda (biblioteca + YouTube Data API opcional) y reproducción aquí.
// · Con teléfono: un solo dispositivo "activo" reproduce y publica
//   users/{uid}/playback; los demás lo ven y mandan órdenes a commands/{id}.
//   Ver docs/WEB_SYNC.md.

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import {
  getDatabase, ref, onValue, set, push, remove, onChildAdded, serverTimestamp, onDisconnect, off,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-database.js";
import * as cfg from "./config.js";

const $ = (id) => document.getElementById(id);
const firebaseConfig = cfg.firebaseConfig || {};
const youtubeApiKey = (cfg.youtubeApiKey || "").trim();
const ONLINE_WINDOW_MS = 75_000;
const HEARTBEAT_MS = 25_000;
const POSITION_PUBLISH_MS = 8_000;
const COMMAND_MAX_AGE_MS = 30_000;

// ── Identidad de este navegador ─────────────────────────────────────────
const deviceId = localStorage.getItem("spotiaisexs-device") || `web-${crypto.randomUUID().slice(0, 8)}`;
localStorage.setItem("spotiaisexs-device", deviceId);
const deviceName = `Navegador · ${browserName()}`;

function browserName() {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Web";
  const os = /Mac OS X/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Linux/.test(ua) ? "Linux" : "";
  return os ? `${browser} en ${os}` : browser;
}

// ── Estado ──────────────────────────────────────────────────────────────
let db, auth, base = null;
let serverOffset = 0;            // reloj del servidor - reloj local
let playback = null;             // documento compartido
let devices = {};
let library = {};                // id → { title, subtitle, artworkUrl, tracks: [] }
let localQueue = [];             // cola cuando este navegador es el activo
let localIndex = 0;
let shuffleOn = false;
let ytPlayer = null, ytReady = false, pendingLoad = null;
let lastPositionPublish = 0;
let unsubscribers = [];
let heartbeatTimer = null;
let currentView = "home";
let openPlaylistId = null;
let lastSearch = { query: "", local: [], remote: [], error: "" };
let disconnectArmed = false;

const serverNow = () => Date.now() + serverOffset;
const isActiveHere = () => playback?.activeDevice === deviceId;
const isDeviceOnline = (id) => {
  if (!id) return false;
  if (id === deviceId) return true;
  const d = devices[id];
  return !!d?.lastSeen && serverNow() - d.lastSeen < ONLINE_WINDOW_MS;
};
/** Otro dispositivo está reproduciendo y sigue conectado → somos un mando. */
const isRemote = () => !!playback?.activeDevice && !isActiveHere() && isDeviceOnline(playback.activeDevice);

// ── Arranque ────────────────────────────────────────────────────────────
if (!firebaseConfig.apiKey || firebaseConfig.apiKey.startsWith("PEGA_")) {
  $("setup").hidden = false;
} else {
  const app = initializeApp(firebaseConfig);
  auth = getAuth(app);
  db = getDatabase(app);
  onAuthStateChanged(auth, (user) => (user ? startSession(user) : showLogin()));
}

function showLogin() {
  stopSession();
  $("app").hidden = true;
  $("login").hidden = false;
}

$("login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  $("login-error").textContent = "";
  try {
    await signInWithEmailAndPassword(auth, $("login-email").value.trim(), $("login-password").value);
  } catch (error) {
    $("login-error").textContent = /invalid|wrong|not-found/i.test(error.code || "")
      ? "Correo o contraseña incorrectos"
      : `No se pudo entrar (${error.code || error.message})`;
  }
});

$("logout").addEventListener("click", async () => {
  if (isActiveHere()) pauseLocal();
  await signOut(auth);
});

function listen(path, handler) {
  const r = ref(db, path);
  onValue(r, (snap) => handler(snap.val()));
  unsubscribers.push(() => off(r));
}

function startSession(user) {
  stopSession();
  base = `users/${user.uid}`;
  $("login").hidden = true;
  $("app").hidden = false;

  const deviceRef = ref(db, `${base}/devices/${deviceId}`);
  const beat = () => set(deviceRef, { name: deviceName, type: "web", lastSeen: serverTimestamp() });
  beat();
  onDisconnect(deviceRef).remove();
  heartbeatTimer = setInterval(beat, HEARTBEAT_MS);

  listen(".info/serverTimeOffset", (v) => { serverOffset = Number(v) || 0; });
  listen(`${base}/playback`, onPlayback);
  listen(`${base}/devices`, (v) => { devices = v || {}; render(); });
  listen(`${base}/library/playlists`, (v) => { library = v || {}; renderLibrary(); renderView(); });

  const commandsRef = ref(db, `${base}/commands/${deviceId}`);
  onChildAdded(commandsRef, (snap) => {
    const command = snap.val();
    remove(snap.ref);
    if (!command || (command.at && serverNow() - command.at > COMMAND_MAX_AGE_MS)) return;
    executeCommand(command);
  });
  unsubscribers.push(() => off(commandsRef));

  showView("home");
}

function stopSession() {
  unsubscribers.forEach((fn) => fn());
  unsubscribers = [];
  clearInterval(heartbeatTimer);
  base = null;
}

// ── Estado compartido ───────────────────────────────────────────────────
function onPlayback(value) {
  const wasActive = isActiveHere();
  playback = value;
  if (wasActive && !isActiveHere()) {
    // Otro dispositivo se ha quedado la reproducción: paramos aquí.
    if (ytReady) ytPlayer.pauseVideo();
    showVideo(false);
    armDisconnect(false);
  }
  render();
}

function estimatedPosition(p) {
  if (!p) return 0;
  const online = isDeviceOnline(p.activeDevice);
  const elapsed = p.isPlaying && online && p.updatedAt ? Math.max(0, serverNow() - p.updatedAt) : 0;
  const pos = (p.positionMs || 0) + elapsed;
  return p.durationMs > 0 ? Math.min(pos, p.durationMs) : pos;
}

/** Si esta pestaña se cierra mientras suena aquí, la marca como pausada. */
function armDisconnect(on) {
  if (!base || on === disconnectArmed) return;
  const r = onDisconnect(ref(db, `${base}/playback/isPlaying`));
  (on ? r.set(false) : r.cancel()).catch(() => {});
  disconnectArmed = on;
}

function publish() {
  if (!base) return;
  const track = localQueue[localIndex];
  if (!track) return;
  const state = ytReady ? ytPlayer.getPlayerState?.() : -1;
  const playing = ytReady && (state === YT.PlayerState.PLAYING || state === YT.PlayerState.BUFFERING);
  const positionMs = ytReady ? Math.round((ytPlayer.getCurrentTime?.() || 0) * 1000) : 0;
  const durationMs = ytReady && ytPlayer.getDuration?.() > 0 ? Math.round(ytPlayer.getDuration() * 1000) : (track.durationMs || 0);
  const doc = {
    activeDevice: deviceId,
    activeDeviceName: deviceName,
    activeDeviceType: "web",
    track: clean(track),
    isPlaying: playing,
    positionMs,
    durationMs,
    updatedAt: serverTimestamp(),
    queue: localQueue.slice(localIndex, localIndex + 50).map(clean),
    queueIndex: 0,
  };
  lastPositionPublish = Date.now();
  playback = { ...doc, updatedAt: serverNow() };
  set(ref(db, `${base}/playback`), doc);
  armDisconnect(true);
  updateMediaSession(track, playing);
}

const clean = (t) => Object.fromEntries(
  ["title", "artist", "album", "artworkUrl", "videoId", "durationMs"]
    .filter((k) => t?.[k] !== undefined && t?.[k] !== null && t?.[k] !== "")
    .map((k) => [k, t[k]]),
);

function sendCommand(type, extra = {}) {
  const target = playback?.activeDevice;
  if (!base || !target || target === deviceId) return;
  push(ref(db, `${base}/commands/${target}`), { type, from: deviceId, at: serverTimestamp(), ...extra });
}

function executeCommand(command) {
  if (command.type === "transfer") return playHere();
  if (!isActiveHere()) return;
  switch (command.type) {
    case "play": if (ytReady) ytPlayer.playVideo(); break;
    case "pause": if (ytReady) ytPlayer.pauseVideo(); break;
    case "toggle": togglePlay(); break;
    case "next": skip(1); break;
    case "previous": skip(-1); break;
    case "seek": if (command.positionMs != null) seekTo(command.positionMs); break;
    case "playIndex": if (command.index != null) loadIndex(localIndex + command.index, 0); break;
  }
}

// ── Reproducción local (YouTube IFrame) ─────────────────────────────────
window.onYouTubeIframeAPIReady = () => {
  ytPlayer = new YT.Player("yt-player", {
    width: "100%",
    height: "100%",
    playerVars: { autoplay: 1, controls: 0, playsinline: 1, rel: 0, modestbranding: 1 },
    events: {
      onReady: () => {
        ytReady = true;
        ytPlayer.setVolume(Number($("volume").value));
        if (pendingLoad) { const p = pendingLoad; pendingLoad = null; p(); }
      },
      onStateChange: (e) => {
        if (!isActiveHere()) return;
        if (e.data === YT.PlayerState.ENDED) skip(1, true);
        else if (e.data === YT.PlayerState.PLAYING || e.data === YT.PlayerState.PAUSED) { publish(); render(); }
      },
      // 101/150: el vídeo no permite reproducirse incrustado → siguiente.
      onError: () => { if (isActiveHere()) { toast("Esta canción no se puede reproducir en la web, saltando…"); skip(1, true); } },
    },
  });
};
{
  // La API se carga después de definir el callback (evita la carrera de carga).
  const tag = document.createElement("script");
  tag.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(tag);
}

function showVideo(visible) {
  // YouTube exige que su reproductor sea visible mientras suena.
  $("yt-holder").hidden = !visible;
  $("nowpanel").classList.toggle("must-show", visible);
}

/** Reproduce [tracks] desde [index] en este navegador (pasa a ser el activo). */
function playTracks(tracks, index = 0, startMs = 0) {
  const playable = tracks.filter((t) => t?.videoId);
  if (!playable.length) return toast("No hay canciones reproducibles");
  const target = tracks[index];
  localQueue = playable.map((t) => ({ ...t }));
  localIndex = Math.max(0, localQueue.findIndex((t) => t === target || t.videoId === target?.videoId));
  if (shuffleOn) shuffleUpcoming();
  playback = { ...(playback || {}), activeDevice: deviceId };
  loadIndex(localIndex, startMs);
}

function loadIndex(index, startMs) {
  if (index < 0 || index >= localQueue.length) return;
  localIndex = index;
  const track = localQueue[index];
  if (!track.videoId) return skip(1, true);
  const go = () => {
    ytPlayer.loadVideoById({ videoId: track.videoId, startSeconds: Math.max(0, (startMs || 0) / 1000) });
    showVideo(true);
    publish();
    render();
  };
  ytReady ? go() : (pendingLoad = go);
}

async function skip(delta, auto = false) {
  if (delta < 0 && ytReady && ytPlayer.getCurrentTime() > 3) return seekTo(0);
  const next = localIndex + delta;
  if (next >= 0 && next < localQueue.length) return loadIndex(next, 0);
  if (delta > 0) {
    // Fin de la cola: seguir con más del mismo artista (como la radio del teléfono).
    const more = await radioFrom(localQueue[localIndex]);
    if (more.length) {
      localQueue.push(...more);
      return loadIndex(localIndex + 1, 0);
    }
    if (auto) { if (ytReady) ytPlayer.pauseVideo(); publish(); }
  }
}

async function radioFrom(seed) {
  if (!seed) return [];
  const played = new Set(localQueue.map((t) => t.videoId));
  // 1) Biblioteca: otras canciones del mismo artista.
  const fromLibrary = shuffled(allLibraryTracks().filter((t) => t.artist === seed.artist && !played.has(t.videoId))).slice(0, 10);
  if (fromLibrary.length >= 5 || !youtubeApiKey) return fromLibrary;
  // 2) YouTube: más del artista.
  try {
    const found = await youtubeSearch(seed.artist);
    return [...fromLibrary, ...found.filter((t) => !played.has(t.videoId))].slice(0, 15);
  } catch {
    return fromLibrary;
  }
}

function seekTo(ms) {
  if (!ytReady) return;
  ytPlayer.seekTo(ms / 1000, true);
  setTimeout(publish, 300);
}

function togglePlay() {
  if (!ytReady) return;
  ytPlayer.getPlayerState() === YT.PlayerState.PLAYING ? ytPlayer.pauseVideo() : ytPlayer.playVideo();
}

function pauseLocal() {
  if (ytReady) ytPlayer.pauseVideo();
  if (isActiveHere()) setTimeout(publish, 200);
}

/** "Reproducir aquí": continúa en este navegador lo último que sonó en cualquier dispositivo. */
function playHere() {
  const p = playback;
  if (!p?.track) return;
  const queue = (p.queue?.length ? p.queue : [p.track]);
  playTracks(queue, Math.min(p.queueIndex || 0, queue.length - 1), estimatedPosition(p));
}

function shuffleUpcoming() {
  const head = localQueue.slice(0, localIndex + 1);
  localQueue = [...head, ...shuffled(localQueue.slice(localIndex + 1))];
}

function addToQueue(track) {
  if (!track?.videoId) return;
  if (isActiveHere() && localQueue.length) {
    localQueue.splice(localIndex + 1, 0, { ...track });
    publish();
    render();
    toast("Añadida a continuación");
  } else {
    playTracks([track], 0);
  }
}

const shuffled = (arr) => {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
};

// ── Controles ───────────────────────────────────────────────────────────
$("play").addEventListener("click", () => {
  if (isActiveHere()) return togglePlay();
  if (isRemote()) return sendCommand("toggle");
  // Nadie reproduce (o el dispositivo que lo hacía se desconectó): seguir aquí.
  if (playback?.track) return playHere();
  showView("search");
});
$("next").addEventListener("click", () => (isActiveHere() ? skip(1) : isRemote() ? sendCommand("next") : null));
$("prev").addEventListener("click", () => (isActiveHere() ? skip(-1) : isRemote() ? sendCommand("previous") : null));
$("shuffle").addEventListener("click", () => {
  shuffleOn = !shuffleOn;
  $("shuffle").setAttribute("aria-pressed", String(shuffleOn));
  if (shuffleOn && isActiveHere()) { shuffleUpcoming(); publish(); render(); }
});
$("queue-toggle").addEventListener("click", () => $("app").classList.toggle("queue-hidden"));
$("volume").addEventListener("input", (e) => {
  localStorage.setItem("spotiaisexs-volume", e.target.value);
  if (ytReady) ytPlayer.setVolume(Number(e.target.value));
});
$("volume").value = localStorage.getItem("spotiaisexs-volume") || "80";

$("bar").addEventListener("click", (event) => {
  const duration = currentDuration();
  if (!duration) return;
  const rect = event.currentTarget.getBoundingClientRect();
  const ms = Math.round(((event.clientX - rect.left) / rect.width) * duration);
  if (isActiveHere()) seekTo(ms);
  else if (isRemote()) sendCommand("seek", { positionMs: ms });
});

$("device-btn").addEventListener("click", () => {
  $("device-menu").hidden = !$("device-menu").hidden;
  renderDevices();
});
document.addEventListener("click", (event) => {
  if (!event.target.closest(".devices")) $("device-menu").hidden = true;
});

function chooseDevice(id) {
  $("device-menu").hidden = true;
  if (id === playback?.activeDevice && isDeviceOnline(id)) return;
  if (id === deviceId) return playHere();
  if (isActiveHere()) publish();
  push(ref(db, `${base}/commands/${id}`), { type: "transfer", from: deviceId, at: serverTimestamp() });
  toast("Pasando la música al teléfono…");
}

document.querySelectorAll(".nav-item").forEach((b) => b.addEventListener("click", () => showView(b.dataset.view)));

$("search-form").addEventListener("submit", (e) => {
  e.preventDefault();
  runSearch($("search-input").value.trim());
});

document.addEventListener("keydown", (event) => {
  if (event.target.closest("input")) return;
  if (event.code === "Space") { event.preventDefault(); $("play").click(); }
  if (event.code === "ArrowRight" && event.shiftKey) $("next").click();
  if (event.code === "ArrowLeft" && event.shiftKey) $("prev").click();
});

// Teclas multimedia y controles del sistema.
function updateMediaSession(track, playing) {
  if (!("mediaSession" in navigator) || !track) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: track.title, artist: track.artist, album: track.album || "",
    artwork: artFor(track) ? [{ src: artFor(track), sizes: "480x360" }] : [],
  });
  navigator.mediaSession.playbackState = playing ? "playing" : "paused";
}
if ("mediaSession" in navigator) {
  navigator.mediaSession.setActionHandler("play", () => $("play").click());
  navigator.mediaSession.setActionHandler("pause", () => $("play").click());
  navigator.mediaSession.setActionHandler("nexttrack", () => $("next").click());
  navigator.mediaSession.setActionHandler("previoustrack", () => $("prev").click());
}

// ── Búsqueda ────────────────────────────────────────────────────────────
function allLibraryTracks() {
  const seen = new Set();
  const out = [];
  Object.values(library).forEach((pl) => (pl.tracks || []).forEach((t) => {
    if (t?.videoId && !seen.has(t.videoId)) { seen.add(t.videoId); out.push(t); }
  }));
  return out;
}

const norm = (s) => (s || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");

async function runSearch(query) {
  if (!query) return;
  const q = norm(query);
  const local = allLibraryTracks().filter((t) => norm(`${t.title} ${t.artist} ${t.album || ""}`).includes(q)).slice(0, 30);
  lastSearch = { query, local, remote: [], error: "", loading: !!youtubeApiKey };
  renderView();
  if (!youtubeApiKey) return;
  try {
    lastSearch.remote = await youtubeSearch(query);
  } catch (error) {
    lastSearch.error = error.message;
  }
  lastSearch.loading = false;
  if (lastSearch.query === query) renderView();
}

function decodeEntities(s) {
  const t = document.createElement("textarea");
  t.innerHTML = s || "";
  return t.value;
}

/** Búsqueda con la API oficial de YouTube (100 unidades de cuota por búsqueda). */
async function youtubeSearch(query) {
  const url = new URL("https://www.googleapis.com/youtube/v3/search");
  url.search = new URLSearchParams({
    part: "snippet", type: "video", videoCategoryId: "10", videoEmbeddable: "true",
    maxResults: "20", q: query, key: youtubeApiKey,
  }).toString();
  const res = await fetch(url);
  const data = await res.json();
  if (!res.ok) {
    const reason = data?.error?.errors?.[0]?.reason || data?.error?.message || res.status;
    throw new Error(reason === "quotaExceeded" ? "Se ha agotado la cuota diaria de búsquedas de YouTube" : `Error de YouTube: ${reason}`);
  }
  return (data.items || []).map((item) => {
    let title = decodeEntities(item.snippet.title);
    let artist = decodeEntities(item.snippet.channelTitle).replace(/\s*-\s*Topic$/i, "").replace(/VEVO$/i, "").trim();
    const m = title.match(/^(.+?)\s[-–—]\s(.+)$/);
    if (m) { artist = m[1].trim(); title = m[2].trim(); }
    title = title.replace(/\s*[([](official|video|audio|lyric|letra|visuali[sz]er|videoclip|hd|4k|mv)[^)\]]*[)\]]/gi, "").trim();
    const th = item.snippet.thumbnails || {};
    return { title, artist, videoId: item.id.videoId, artworkUrl: (th.high || th.medium || th.default || {}).url };
  });
}

// ── Pintado ─────────────────────────────────────────────────────────────
const songs = (n) => `${n} ${n === 1 ? "canción" : "canciones"}`;
const fmt = (ms) => {
  const s = Math.max(0, Math.floor((ms || 0) / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
const artFor = (t) => t?.artworkUrl || (t?.videoId ? `https://i.ytimg.com/vi/${t.videoId}/hqdefault.jpg` : "");
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const LOGO_SVG = '<svg viewBox="0 0 100 100" width="40%" height="40%" fill="currentColor" aria-hidden="true"><rect x="21.5" y="37" width="9" height="26" rx="4.5"/><rect x="33.5" y="28" width="9" height="44" rx="4.5"/><rect x="45.5" y="20" width="9" height="60" rx="4.5"/><rect x="57.5" y="30" width="9" height="40" rx="4.5"/><rect x="69.5" y="39" width="9" height="22" rx="4.5"/></svg>';

/** Imagen con reserva: si no carga, se sustituye por el logo. */
function thumb(url, cls = "thumb") {
  if (!url) return `<div class="${cls === "thumb" ? "thumb-ph" : cls}">${LOGO_SVG}</div>`;
  return `<img class="${cls}" src="${esc(url)}" alt="" loading="lazy" data-fallback>`;
}
document.addEventListener("error", (e) => {
  const img = e.target;
  if (img.tagName === "IMG" && img.hasAttribute("data-fallback")) {
    const ph = document.createElement("div");
    ph.className = img.className.replace("thumb", "thumb-ph");
    ph.innerHTML = LOGO_SVG;
    img.replaceWith(ph);
  }
}, true);

function currentTrack() {
  return isActiveHere() ? localQueue[localIndex] : playback?.track;
}
function currentDuration() {
  if (isActiveHere() && ytReady && ytPlayer.getDuration?.() > 0) return ytPlayer.getDuration() * 1000;
  return playback?.durationMs || playback?.track?.durationMs || 0;
}
function currentPosition() {
  if (isActiveHere() && ytReady) return (ytPlayer.getCurrentTime?.() || 0) * 1000;
  return estimatedPosition(playback);
}
function isPlayingNow() {
  if (isActiveHere()) {
    if (!ytReady) return false;
    const s = ytPlayer.getPlayerState?.();
    return s === YT.PlayerState.PLAYING || s === YT.PlayerState.BUFFERING;
  }
  return !!playback?.isPlaying && isDeviceOnline(playback?.activeDevice);
}

function setImg(imgId, emptyId, url) {
  const img = $(imgId);
  if (url && url !== img.dataset.failed) {
    if (img.getAttribute("src") !== url) img.src = url;
    img.hidden = false;
    $(emptyId).hidden = true;
  } else {
    img.hidden = true;
    $(emptyId).hidden = false;
  }
}
[["np-cover", "np-empty"], ["pb-cover", "pb-cover-empty"]].forEach(([imgId, emptyId]) => {
  $(imgId).addEventListener("error", () => {
    $(imgId).dataset.failed = $(imgId).getAttribute("src");
    $(imgId).hidden = true;
    $(emptyId).hidden = false;
  });
});

function render() {
  const track = currentTrack();
  $("np-title").textContent = track?.title || "Nada sonando";
  $("np-artist").textContent = track?.artist || "Busca algo o abre una playlist";
  $("pb-title").textContent = track?.title || "—";
  $("pb-artist").textContent = track?.artist || "";
  document.title = track ? `${track.title} · ${track.artist}` : "SpotiAisexs";
  setImg("np-cover", "np-empty", artFor(track));
  setImg("pb-cover", "pb-cover-empty", artFor(track));
  if (!isActiveHere()) showVideo(false);

  const playing = isPlayingNow();
  $("ic-play").toggleAttribute("hidden", playing);
  $("ic-pause").toggleAttribute("hidden", !playing);
  $("play").setAttribute("aria-label", playing ? "Pausar" : "Reproducir");

  let label = "Este navegador";
  if (isRemote()) label = `Escuchando en ${playback.activeDeviceName || "otro dispositivo"}`;
  $("device-label").textContent = label;
  $("device-btn").classList.toggle("remote", isRemote());

  renderQueue();
  renderDevices();
  markPlayingRows();
}

function renderQueue() {
  const list = $("queue-list");
  const queue = isActiveHere() ? localQueue.slice(localIndex, localIndex + 50) : (playback?.queue || []);
  $("queue-empty").hidden = queue.length > 0;
  list.innerHTML = queue.map((t, i) => `
    <li class="queue-item${i === 0 ? " current" : ""}" data-i="${i}">
      ${thumb(artFor(t))}
      <div class="q-text"><div class="q-title">${esc(t.title)}</div><div class="q-artist">${esc(t.artist)}</div></div>
    </li>`).join("");
  list.querySelectorAll(".queue-item").forEach((li) => li.addEventListener("click", () => {
    const i = Number(li.dataset.i);
    if (i === 0) return;
    if (isActiveHere()) loadIndex(localIndex + i, 0);
    else if (isRemote()) sendCommand("playIndex", { index: i });
    else playTracks(queue, i);
  }));
}

function renderDevices() {
  const menu = $("device-menu");
  if (menu.hidden) return;
  const entries = Object.entries(devices).filter(([id]) => id !== deviceId && isDeviceOnline(id));
  entries.unshift([deviceId, { name: deviceName, type: "web" }]);
  const activeId = isRemote() || isActiveHere() ? playback.activeDevice : null;
  menu.innerHTML = `<h3>Conectar a un dispositivo</h3>` + entries.map(([id, d]) => `
    <button class="device-item${id === activeId ? " active" : ""}" data-id="${esc(id)}">
      ${d.type === "phone"
        ? '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/></svg>'
        : '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8"/></svg>'}
      <span>${esc(id === deviceId ? "Este navegador" : d.name)}<small>${id === activeId ? "Reproduciendo" : id === deviceId ? esc(deviceName) : "Disponible"}</small></span>
    </button>`).join("")
    + (entries.length === 1 ? '<p class="muted small" style="margin:6px 12px">Abre la app en el teléfono para verlo aquí.</p>' : "");
  menu.querySelectorAll(".device-item").forEach((b) => b.addEventListener("click", () => chooseDevice(b.dataset.id)));
}

function sortedPlaylists() {
  return Object.entries(library)
    .map(([id, pl]) => ({ id, ...pl, tracks: pl.tracks || [] }))
    .sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || (b.mode === "liked" ? 1 : 0) - (a.mode === "liked" ? 1 : 0) || (b.createdAt || 0) - (a.createdAt || 0));
}

function renderLibrary() {
  const list = $("library-list");
  const items = sortedPlaylists();
  list.innerHTML = items.length
    ? items.map((pl) => `
      <li class="lib-item${pl.id === openPlaylistId && currentView === "playlist" ? " active" : ""}" data-id="${esc(pl.id)}">
        ${thumb(pl.artworkUrl || artFor(pl.tracks[0]))}
        <div class="lib-text"><div class="lib-title">${esc(pl.title)}</div><div class="lib-sub">Playlist · ${songs(pl.tracks.length)}</div></div>
      </li>`).join("")
    : '<li class="muted small" style="padding:8px 10px">Conecta el teléfono para traer tus playlists.</li>';
  list.querySelectorAll(".lib-item").forEach((li) => li.addEventListener("click", () => openPlaylist(li.dataset.id)));
}

function showView(view) {
  currentView = view;
  ["home", "search", "library", "playlist"].forEach((v) => { $(`view-${v}`).hidden = v !== view; });
  document.querySelectorAll(".nav-item").forEach((b) => b.classList.toggle("active", b.dataset.view === view));
  $("main").scrollTop = 0;
  renderView();
  if (view === "search") setTimeout(() => $("search-input").focus(), 0);
}

function openPlaylist(id) {
  openPlaylistId = id;
  showView("playlist");
  renderLibrary();
}

function renderView() {
  if (currentView === "home") renderHome();
  if (currentView === "search") renderSearch();
  if (currentView === "library") renderLibraryView();
  if (currentView === "playlist") renderPlaylist();
  markPlayingRows();
}

function greeting() {
  const h = new Date().getHours();
  return h < 6 ? "Buenas noches" : h < 13 ? "Buenos días" : h < 21 ? "Buenas tardes" : "Buenas noches";
}

function renderHome() {
  const pls = sortedPlaylists();
  const resume = playback?.track && !isActiveHere() && !isRemote() ? playback : null;
  $("view-home").innerHTML = `
    <h1>${greeting()}</h1>
    ${resume ? `
      <div class="quick" style="margin-bottom:8px">
        <button class="quick-item" id="resume">${thumb(artFor(resume.track))}<span>Seguir con «${esc(resume.track.title)}»<br><span class="muted small">Última vez en ${esc(resume.activeDeviceName || "otro dispositivo")}</span></span></button>
      </div>` : ""}
    ${pls.length ? `
      <div class="quick">${pls.slice(0, 6).map((pl) => `
        <button class="quick-item" data-id="${esc(pl.id)}">${thumb(pl.artworkUrl || artFor(pl.tracks[0]))}<span>${esc(pl.title)}</span></button>`).join("")}
      </div>
      <h2>Tus playlists</h2>
      <div class="grid">${pls.map((pl) => card(pl)).join("")}</div>`
    : `<div class="empty-state">
        <strong>Tu biblioteca está vacía.</strong><br>
        Enlaza el teléfono en <em>Ajustes → Importar → Conectar con la web</em> y tus playlists aparecerán aquí,
        aunque luego el teléfono esté apagado. ${youtubeApiKey ? "También puedes buscar cualquier canción." : ""}
      </div>`}`;
  $("resume")?.addEventListener("click", playHere);
  bindCards($("view-home"));
}

function card(pl) {
  return `<div class="card" data-id="${esc(pl.id)}" role="button" tabindex="0">
    ${thumb(pl.artworkUrl || artFor(pl.tracks[0]))}
    <div><div class="card-title">${esc(pl.title)}</div><div class="card-sub">${songs(pl.tracks.length)}</div></div>
    <button class="card-play" data-play="${esc(pl.id)}" aria-label="Reproducir ${esc(pl.title)}"><svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M8 5.5v13l11-6.5z"/></svg></button>
  </div>`;
}

function bindCards(root) {
  root.querySelectorAll("[data-play]").forEach((b) => b.addEventListener("click", (e) => {
    e.stopPropagation();
    const pl = library[b.dataset.play];
    if (pl) playTracks(pl.tracks || [], 0);
  }));
  root.querySelectorAll(".card[data-id], .quick-item[data-id]").forEach((c) => c.addEventListener("click", () => openPlaylist(c.dataset.id)));
}

function renderLibraryView() {
  const pls = sortedPlaylists();
  $("view-library").innerHTML = `<h1>Biblioteca</h1>${pls.length ? `<div class="grid">${pls.map(card).join("")}</div>` : '<p class="muted">Conecta el teléfono para traer tus playlists.</p>'}`;
  bindCards($("view-library"));
}

function trackRows(tracks, { numbered = true } = {}) {
  return `<ol class="tracks">${tracks.map((t, i) => `
    <li class="track" data-i="${i}" data-video="${esc(t.videoId)}">
      <span class="t-num">${numbered ? i + 1 : ""}</span>
      ${thumb(artFor(t))}
      <div class="t-text"><div class="t-title">${esc(t.title)}</div><div class="t-artist">${esc(t.artist)}</div></div>
      <button class="t-add" data-add="${i}" aria-label="Añadir a la cola" title="Añadir a la cola"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M12 5v14"/><path d="M5 12h14"/></svg></button>
    </li>`).join("")}</ol>`;
}

function bindTrackRows(root, tracks) {
  root.querySelectorAll(".track").forEach((row) => row.addEventListener("click", () => playTracks(tracks, Number(row.dataset.i))));
  root.querySelectorAll("[data-add]").forEach((b) => b.addEventListener("click", (e) => {
    e.stopPropagation();
    addToQueue(tracks[Number(b.dataset.add)]);
  }));
}

function renderPlaylist() {
  const pl = library[openPlaylistId];
  const root = $("view-playlist");
  if (!pl) { root.innerHTML = '<p class="muted">Esta playlist ya no existe.</p>'; return; }
  const tracks = pl.tracks || [];
  root.innerHTML = `
    <div class="pl-header">
      ${thumb(pl.artworkUrl || artFor(tracks[0]))}
      <div><div class="pl-kind">Playlist</div><h1>${esc(pl.title)}</h1><div class="muted">${songs(tracks.length)}</div></div>
    </div>
    <div class="pl-actions">
      <button class="big-play" id="pl-play" aria-label="Reproducir"><svg viewBox="0 0 24 24" width="26" height="26" fill="currentColor"><path d="M8 5.5v13l11-6.5z"/></svg></button>
      <button class="btn-icon" id="pl-shuffle" aria-label="Reproducir en aleatorio"><svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 4h4v4"/><path d="M4 20 20 4"/><path d="M20 16v4h-4"/><path d="m15 15 5 5"/><path d="M4 4l5 5"/></svg></button>
    </div>
    ${trackRows(tracks)}`;
  $("pl-play").addEventListener("click", () => playTracks(tracks, 0));
  $("pl-shuffle").addEventListener("click", () => { const s = shuffled(tracks); playTracks(s, 0); });
  bindTrackRows(root, tracks);
}

function renderSearch() {
  const root = $("search-results");
  const s = lastSearch;
  if (!s.query) {
    root.innerHTML = `<p class="muted" style="margin-top:20px">${youtubeApiKey
      ? "Busca en tu biblioteca y en YouTube."
      : "Busca en tu biblioteca. Para buscar cualquier canción, añade una clave de YouTube en config.js (ver docs/WEB_SYNC.md)."}</p>`;
    return;
  }
  root.innerHTML = `
    ${s.local.length ? `<h2>En tu biblioteca</h2><div id="sr-local">${trackRows(s.local, { numbered: false })}</div>` : ""}
    ${youtubeApiKey ? `<h2>YouTube</h2>${s.loading ? '<p class="muted">Buscando…</p>' : s.error ? `<p class="error">${esc(s.error)}</p>` : `<div id="sr-remote">${trackRows(s.remote, { numbered: false })}</div>`}` : ""}
    ${!s.local.length && !youtubeApiKey ? '<p class="muted">Nada en tu biblioteca con ese nombre.</p>' : ""}`;
  if ($("sr-local")) bindTrackRows($("sr-local"), s.local);
  if ($("sr-remote")) bindTrackRows($("sr-remote"), s.remote);
}

function markPlayingRows() {
  const id = currentTrack()?.videoId;
  document.querySelectorAll(".track").forEach((row) => row.classList.toggle("playing", !!id && row.dataset.video === id));
}

let toastTimer;
function toast(text) {
  $("toast").textContent = text;
  $("toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $("toast").hidden = true; }, 2600);
}

// Barra de progreso fluida + posición publicada cada pocos segundos.
setInterval(() => {
  const duration = currentDuration();
  const position = Math.min(currentPosition(), duration || Infinity);
  const pct = duration ? (position / duration) * 100 : 0;
  $("bar-fill").style.width = `${pct}%`;
  $("bar-knob").style.left = `${pct}%`;
  $("t-pos").textContent = fmt(position);
  $("t-dur").textContent = fmt(duration);
  if (isActiveHere() && isPlayingNow() && Date.now() - lastPositionPublish > POSITION_PUBLISH_MS) publish();
}, 500);

// Si el dispositivo activo deja de dar señales, actualizar la vista (deja de "sonar").
setInterval(() => { if (playback?.activeDevice && !isActiveHere()) render(); }, 10_000);
