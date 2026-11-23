// SpotiAisexs web · núcleo: sesión, reproducción en este navegador, cola,
// radio, biblioteca, Jam y la sincronización con los demás dispositivos.
// La interfaz (app.js) se engancha con `ui` y lee el estado de `S`.
//
// Cómo se reparten los dispositivos (users/{uid}/playback):
// · Uno solo reproduce: el que figura en `activeDevice`. Lo RECLAMA con una
//   escritura completa del documento. A partir de ahí solo ACTUALIZA los
//   campos de estado (canción, posición, cola…), nunca `activeDevice`: así una
//   actualización que llega tarde de quien reproducía antes no le quita la
//   música a quien la acaba de coger (era el fallo al pasar de uno a otro).
// · Los demás son mandos: leen el documento y dejan órdenes en
//   commands/{dispositivo}. Si nadie contesta en unos segundos, se avisa.

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import {
  getDatabase, ref, onValue, set, update, push, remove, onChildAdded, serverTimestamp, onDisconnect, off,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-database.js";
import * as cfg from "./config.js";
import { youtubeSearch as dataApiSearch } from "./youtube.js";
import { Radio, recordPlay, recordSkip } from "./radio.js";
import * as catalog from "./catalog.js";

export { catalog };
export const catalogReady = catalog.catalogReady;
const firebaseConfig = cfg.firebaseConfig || {};
const youtubeApiKey = (cfg.youtubeApiKey || "").trim();
/** Hay con qué buscar fuera de la biblioteca y con qué alimentar la radio. */
export const canSearchOnline = catalogReady || !!youtubeApiKey;

const ONLINE_WINDOW_MS = 75_000;
const ACTIVE_WINDOW_MS = 45_000;
const HEARTBEAT_MS = 25_000;
const POSITION_PUBLISH_MS = 8_000;
const COMMAND_MAX_AGE_MS = 30_000;
const COMMAND_REPLY_MS = 6_000;
const RADIO_REFILL_AT = 5;

/** Lo que la interfaz quiere saber. app.js sustituye estas funciones. */
export const ui = {
  render() {}, toast() {}, session() {}, library() {}, jam() {}, needsTap() {}, video() {},
};

export const store = {
  get(k, fallback = null) { try { const v = localStorage.getItem(k); return v == null ? fallback : JSON.parse(v); } catch { return fallback; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* sin almacenamiento */ } },
};

// ── Identidad de este navegador ─────────────────────────────────────────
function rawItem(k) { try { return localStorage.getItem(k); } catch { return null; } }
export const deviceId = rawItem("spotiaisexs-device") || `web-${crypto.randomUUID().slice(0, 8)}`;
try { localStorage.setItem("spotiaisexs-device", deviceId); } catch { /* modo privado */ }
function browserName() {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Web";
  const os = /Mac OS X/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Linux/.test(ua) ? "Linux" : "";
  return os ? `${browser} en ${os}` : browser;
}
export const deviceName = `Navegador · ${browserName()}`;

// ── Estado ──────────────────────────────────────────────────────────────
export const S = {
  configured: !!firebaseConfig.apiKey && !firebaseConfig.apiKey.startsWith("PEGA_"),
  user: null,
  playback: null,        // documento compartido, tal como está en el servidor
  devices: {},
  library: {},           // id → { title, mode, artworkUrl, tracks: [] }
  jam: null,
  hereActive: false,     // este navegador es el que reproduce
  queue: [],
  index: 0,
  shuffle: false,
  radio: null,
  radioLabel: "",
  radioLoading: false,
  distance: Number(store.get("spotiaisexs-distance", 35)),
  pending: null,         // orden enviada a otro dispositivo, a la espera de respuesta
  recents: store.get("spotiaisexs-recents", []),
};

let db, auth, base = null;
let serverOffset = 0;
let unsubscribers = [];
let heartbeatTimer = null;
let disconnectArmed = false;
let ytPlayer = null, ytReady = false, pendingLoad = null;
let trackStartedAt = 0;
let loadToken = 0;
let userPaused = false;
const handledJamRequests = new Set();

export const serverNow = () => Date.now() + serverOffset;
export const isActiveHere = () => S.hereActive;
export function isDeviceOnline(id) {
  if (!id) return false;
  if (id === deviceId) return true;
  const seen = S.devices[id]?.lastSeen;
  if (seen && serverNow() - seen < ONLINE_WINDOW_MS) return true;
  // Mientras suena publica su posición cada pocos segundos: eso también vale como señal de vida.
  const p = S.playback;
  return p?.activeDevice === id && !!p.isPlaying && !!p.updatedAt && serverNow() - p.updatedAt < ACTIVE_WINDOW_MS;
}
/** Otro dispositivo reproduce y sigue conectado: aquí somos un mando. */
export const isRemote = () => !S.hereActive && !!S.playback?.activeDevice && S.playback.activeDevice !== deviceId && isDeviceOnline(S.playback.activeDevice);

// ── Sesión ──────────────────────────────────────────────────────────────
export function boot() {
  if (!S.configured) return;
  const app = initializeApp(firebaseConfig);
  auth = getAuth(app);
  db = getDatabase(app);
  // Un invitado de la Jam entra como usuario anónimo: aquí no cuenta como sesión.
  onAuthStateChanged(auth, (user) => (user && !user.isAnonymous ? startSession(user) : endSession()));
}

export async function login(email, password) {
  try {
    await signInWithEmailAndPassword(auth, email.trim(), password);
    return "";
  } catch (error) {
    return /invalid|wrong|not-found/i.test(error.code || "") ? "Correo o contraseña incorrectos" : `No se pudo entrar (${error.code || error.message})`;
  }
}

export async function logout() {
  if (S.hereActive) { pausePlayer(); await publish(); }
  await signOut(auth);
}

function listen(path, handler) {
  const r = ref(db, path);
  onValue(r, (snap) => handler(snap.val()));
  unsubscribers.push(() => off(r));
}

function startSession(user) {
  stopListening();
  S.user = { uid: user.uid, email: user.email || "" };
  base = `users/${user.uid}`;

  const deviceRef = ref(db, `${base}/devices/${deviceId}`);
  const beat = () => set(deviceRef, { name: deviceName, type: "web", lastSeen: serverTimestamp() }).catch(() => {});
  beat();
  onDisconnect(deviceRef).remove();
  heartbeatTimer = setInterval(beat, HEARTBEAT_MS);

  listen(".info/serverTimeOffset", (v) => { serverOffset = Number(v) || 0; });
  listen(`${base}/playback`, onPlayback);
  listen(`${base}/devices`, (v) => { S.devices = v || {}; ui.render(); });
  listen(`${base}/library/playlists`, (v) => { S.library = v || {}; ui.library(); });
  listen(`${base}/jam`, (v) => { S.jam = v; onJamChanged(); });

  const commandsRef = ref(db, `${base}/commands/${deviceId}`);
  onChildAdded(commandsRef, (snap) => {
    const command = snap.val();
    remove(snap.ref);
    if (!command || (command.at && serverNow() - command.at > COMMAND_MAX_AGE_MS)) return;
    executeCommand(command);
  });
  unsubscribers.push(() => off(commandsRef));
  ui.session(true);
}

function stopListening() {
  unsubscribers.forEach((fn) => fn());
  unsubscribers = [];
  clearInterval(heartbeatTimer);
}

function endSession() {
  stopListening();
  base = null;
  S.user = null;
  ui.session(false);
}

// ── Estado compartido ───────────────────────────────────────────────────
function onPlayback(value) {
  const previousId = S.playback?.track?.videoId;
  S.playback = value;
  const owner = value?.activeDevice;
  if (S.hereActive && owner && owner !== deviceId) {
    // Otro dispositivo ha reclamado la reproducción: aquí se para.
    yieldPlayback();
  } else if (S.hereActive && value && value.writer && value.writer !== deviceId) {
    // Alguien ha escrito encima de nuestro estado (una actualización suya que llegó tarde): se repone.
    setTimeout(publish, 150);
  }
  if (S.pending && value?.updatedAt && value.updatedAt >= S.pending.sentAt - 500) clearPending();
  if (!S.hereActive && value?.track?.videoId && value.track.videoId !== previousId) addRecent(value.track);
  ui.render();
}

function yieldPlayback() {
  S.hereActive = false;
  if (ytReady) ytPlayer.pauseVideo();
  ui.video(false);
  ui.needsTap(false);
  armDisconnect(false);
}

export function estimatedPosition(p = S.playback) {
  if (!p) return 0;
  const elapsed = p.isPlaying && p.updatedAt && isDeviceOnline(p.activeDevice) ? Math.max(0, serverNow() - p.updatedAt) : 0;
  const pos = (p.positionMs || 0) + elapsed;
  return p.durationMs > 0 ? Math.min(pos, p.durationMs) : pos;
}

/** Si esta pestaña se cierra mientras suena aquí, queda marcada como pausada. */
function armDisconnect(on) {
  if (!base || on === disconnectArmed) return;
  const r = onDisconnect(ref(db, `${base}/playback/isPlaying`));
  (on ? r.set(false) : r.cancel()).catch(() => {});
  disconnectArmed = on;
}

export const clean = (t) => Object.fromEntries(
  ["title", "artist", "album", "artworkUrl", "videoId", "durationMs", "addedBy"]
    .filter((k) => t?.[k] !== undefined && t?.[k] !== null && t?.[k] !== "")
    .map((k) => [k, t[k]]),
);

function playerState() { return ytReady ? ytPlayer.getPlayerState?.() : -1; }
const playingState = (s) => s === 1 || s === 3;

function statusDoc() {
  const track = S.queue[S.index];
  const positionMs = ytReady ? Math.round((ytPlayer.getCurrentTime?.() || 0) * 1000) : 0;
  const durationMs = ytReady && ytPlayer.getDuration?.() > 0 ? Math.round(ytPlayer.getDuration() * 1000) : (track.durationMs || 0);
  return {
    track: clean(track),
    isPlaying: playingState(playerState()),
    positionMs,
    durationMs,
    updatedAt: serverTimestamp(),
    writer: deviceId,
    queue: S.queue.slice(S.index, S.index + 50).map(clean),
    queueIndex: 0,
    radioDistance: S.radio ? S.distance : null,
    radioLabel: S.radio ? S.radioLabel : null,
  };
}

let lastPublish = 0;
/** Publica el estado. La primera vez (o si el documento no es nuestro) reclama la reproducción. */
export function publish() {
  if (!base || !S.hereActive || !S.queue[S.index]) return Promise.resolve();
  lastPublish = Date.now();
  const doc = statusDoc();
  updateMediaSession(S.queue[S.index], doc.isPlaying);
  armDisconnect(true);
  const playbackRef = ref(db, `${base}/playback`);
  const claim = S.playback?.activeDevice !== deviceId;
  const write = claim
    ? set(playbackRef, { activeDevice: deviceId, activeDeviceName: deviceName, activeDeviceType: "web", ...doc })
    : update(playbackRef, doc);
  return write.catch(() => {});
}

// ── Órdenes a otro dispositivo ──────────────────────────────────────────
function clearPending() {
  if (!S.pending) return;
  clearTimeout(S.pending.timer);
  S.pending = null;
}

export function sendCommand(type, extra = {}, target = S.playback?.activeDevice) {
  if (!base || !target || target === deviceId) return;
  push(ref(db, `${base}/commands/${target}`), { type, from: deviceId, at: serverTimestamp(), ...extra }).catch(() => {});
  clearPending();
  const name = S.devices[target]?.name || S.playback?.activeDeviceName || "El otro dispositivo";
  S.pending = {
    type,
    sentAt: serverNow(),
    wasPlaying: !!S.playback?.isPlaying,
    timer: setTimeout(() => {
      S.pending = null;
      ui.toast(`${name} no responde. Comprueba que la app sigue abierta y con conexión.`);
      ui.render();
    }, COMMAND_REPLY_MS),
  };
  ui.render();
}

function executeCommand(command) {
  if (command.type === "transfer") return playHere({ remote: true });
  if (command.type === "playTracks" && command.tracks?.length) return playTracks(command.tracks, command.index || 0, 0, { here: true, remote: true });
  if (!S.hereActive) return;
  switch (command.type) {
    case "play": resumePlayer(); break;
    case "pause": pausePlayer(); break;
    case "toggle": togglePlay(); break;
    case "next": skip(1); break;
    case "previous": skip(-1); break;
    case "seek": if (command.positionMs != null) seekTo(command.positionMs); break;
    case "playIndex": if (command.index != null) jumpTo(S.index + command.index); break;
    case "queue": (command.tracks || []).forEach((t) => queueLocal(t)); break;
    case "radio": if (command.tracks?.[0]) startRadio(command.tracks[0]); break;
    case "dial": if (command.value != null) setDistance(command.value, { remote: true }); break;
  }
}

// ── Reproductor de YouTube incrustado ───────────────────────────────────
window.onYouTubeIframeAPIReady = () => {
  ytPlayer = new YT.Player("yt-player", {
    width: "100%",
    height: "100%",
    playerVars: { autoplay: 1, controls: 0, playsinline: 1, rel: 0, modestbranding: 1 },
    events: {
      onReady: () => {
        ytReady = true;
        ytPlayer.setVolume(Number(store.get("spotiaisexs-volume", 80)));
        if (pendingLoad) { const p = pendingLoad; pendingLoad = null; p(); }
      },
      onStateChange: (e) => {
        if (!S.hereActive) return;
        if (e.data === 0) { recordPlay(S.queue[S.index]); trackStartedAt = 0; skip(1, true); return; }
        if (e.data === 1) {
          ui.needsTap(false);
          // La versión alternativa ha funcionado: se recuerda para la próxima vez.
          const track = S.queue[S.index];
          if (track?.playingAlt) rememberAlternative(track.videoId, track.playingAlt);
        }
        if (e.data === 1 || e.data === 2) { publish(); ui.render(); }
      },
      onError: (e) => { if (S.hereActive) onPlayerError(e?.data); },
    },
  });
};

export function loadYouTubeApi() {
  if (document.getElementById("yt-api")) return;
  const tag = document.createElement("script");
  tag.id = "yt-api";
  tag.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(tag);
}

export function setVolume(value) {
  store.set("spotiaisexs-volume", Number(value));
  if (ytReady) ytPlayer.setVolume(Number(value));
}

// YouTube no deja incrustar algunas canciones fuera de su web. Antes de darla
// por perdida se prueba con otra versión del mismo tema (el vídeo oficial, un
// lyric video…), y la que funciona se recuerda.
const alternatives = store.get("spotiaisexs-alternatives", {});
function rememberAlternative(videoId, alt) {
  if (alternatives[videoId] === alt) return;
  alternatives[videoId] = alt;
  const keys = Object.keys(alternatives);
  if (keys.length > 400) delete alternatives[keys[0]];
  store.set("spotiaisexs-alternatives", alternatives);
}
const norm = (s) => String(s || "").toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");
const coreTitle = (title) => norm(title).replace(/\(.*?\)|\[.*?\]|\s-\s.*$|\bfeat\.?.*$|\bft\.?.*$/g, " ").replace(/[^a-z0-9]+/g, " ").trim();

async function findAlternatives(track) {
  const query = `${track.artist || ""} ${track.title || ""}`.trim();
  const wanted = coreTitle(track.title);
  let found = [];
  try {
    if (catalogReady) found = await catalog.searchVideos(query, 8);
    else if (youtubeApiKey) found = (await dataApiSearch(query, youtubeApiKey)).filter((t) => t.playable !== false);
  } catch { found = []; }
  return found
    .filter((t) => t.videoId && t.videoId !== track.videoId && (!wanted || coreTitle(t.title).includes(wanted) || norm(t.title).includes(wanted)))
    .map((t) => t.videoId)
    .slice(0, 3);
}

async function onPlayerError(code) {
  const track = S.queue[S.index];
  if (!track) return;
  const token = loadToken;
  if (!track.alts) track.alts = await findAlternatives(track);
  if (token !== loadToken || !S.hereActive) return;
  const next = track.alts.shift();
  if (next) {
    track.playingAlt = next;
    ytPlayer.loadVideoById({ videoId: next, startSeconds: (track.resumeMs || 0) / 1000 });
    return;
  }
  if (alternatives[track.videoId]) { delete alternatives[track.videoId]; store.set("spotiaisexs-alternatives", alternatives); }
  ui.toast(code === 101 || code === 150
    ? `YouTube no deja reproducir «${track.title}» en la web. Paso a la siguiente.`
    : `No se ha podido cargar «${track.title}». Paso a la siguiente.`);
  skip(1, true);
}

// ── Reproducción en este navegador ──────────────────────────────────────
/**
 * Reproduce [tracks] desde [index]. Si otro dispositivo está sonando, suena
 * allí (como en Spotify Connect) salvo que se pida `here`. Una canción suelta
 * arranca su radio; una lista suena tal cual y al acabarse sigue con radio.
 */
export function playTracks(tracks, index = 0, startMs = 0, { here = false, remote = false, radio = null, label = "" } = {}) {
  const playable = tracks.filter((t) => t?.videoId);
  if (!playable.length) return ui.toast("No hay canciones que se puedan reproducir");
  const target = tracks[index] || playable[0];
  if (!here && isRemote()) {
    sendCommand("playTracks", { tracks: tracks.slice(index, index + 100).map(clean), index: 0 });
    return ui.toast(`Suena en ${S.playback.activeDeviceName || "el otro dispositivo"}`);
  }
  noteListened();
  S.queue = playable.map((t) => ({ ...t }));
  S.index = Math.max(0, S.queue.findIndex((t) => t.videoId === target.videoId));
  if (S.shuffle && playable.length > 1) shuffleUpcoming();
  const style = radio || (playable.length === 1 ? (S.distance < 50 ? "focused" : "explore") : null);
  S.radio = style && canSearchOnline ? newRadio(S.queue[S.index], style) : null;
  S.radioLabel = S.radio ? (label || `Radio de ${S.queue[S.index].artist || "esta canción"}`) : "";
  S.hereActive = true;
  loadIndex(S.index, startMs, { remote });
}

function newRadio(seed, style) {
  return new Radio({
    seed, style, apiKey: youtubeApiKey, libraryTracks: allLibraryTracks,
    related: catalogReady ? catalog.related : null,
    distance: S.distance,
  });
}

/** Radio a partir de una canción, aquí o en el dispositivo que esté sonando. */
export function startRadio(seed, { style = null, label = "", here = false } = {}) {
  if (!seed?.videoId) return;
  if (!canSearchOnline) return ui.toast("La radio necesita el catálogo o la clave de YouTube en config.js");
  if (!here && isRemote()) {
    sendCommand("radio", { tracks: [clean(seed)] });
    return ui.toast(`Radio de «${seed.title}» en ${S.playback.activeDeviceName || "el otro dispositivo"}`);
  }
  playTracks([seed], 0, 0, { here: true, radio: style || (S.distance < 50 ? "focused" : "explore"), label });
}

/** El dial: 0 = pegada a la canción, 100 = todo nuevo. Rehace lo que viene. */
export function setDistance(value, { remote = false } = {}) {
  S.distance = Math.max(0, Math.min(100, Math.round(Number(value) || 0)));
  store.set("spotiaisexs-distance", S.distance);
  if (!remote && isRemote()) return sendCommand("dial", { value: S.distance });
  if (S.hereActive && S.radio) {
    S.radio.distance = S.distance;
    S.radio.style = S.distance < 50 ? "focused" : "explore";
    // Lo que puso la radio y aún no ha sonado se cambia por canciones con la distancia nueva.
    S.queue = S.queue.filter((t, i) => i <= S.index || !t.fromRadio);
    extendRadio({ force: true });
  }
  ui.render();
}

export const distanceLabel = (d) => (d < 18 ? "Mismo artista" : d < 50 ? "Cerca" : d < 78 ? "Abierto" : "Todo nuevo");

/** Escuchada (≥30 s) o saltada antes: alimenta el historial de la radio. */
function noteListened() {
  const prev = S.queue[S.index];
  if (!prev || !trackStartedAt || !S.hereActive) return;
  const listened = ytReady ? (ytPlayer.getCurrentTime?.() || 0) : 0;
  if (listened >= 30) recordPlay(prev);
  else if (prev.fromRadio) recordSkip(prev);
  trackStartedAt = 0;
}

async function extendRadio({ force = false } = {}) {
  if (!S.radio || S.radioLoading) return false;
  if (!force && S.queue.length - S.index - 1 > RADIO_REFILL_AT) return false;
  S.radioLoading = true;
  ui.render();
  const owner = S.radio;
  try {
    const batch = await owner.next(S.queue, 20);
    if (owner !== S.radio || !batch.length) return false;
    const known = new Set(S.queue.map((t) => t.videoId));
    const fresh = batch.filter((t) => !known.has(t.videoId));
    S.queue.push(...fresh);
    if (S.hereActive) publish();
    return fresh.length > 0;
  } catch (error) {
    ui.toast(error.message || "La radio no ha podido cargar más canciones");
    return false;
  } finally {
    S.radioLoading = false;
    ui.render();
  }
}

function loadIndex(index, startMs = 0, { remote = false } = {}) {
  if (index < 0 || index >= S.queue.length) return;
  S.index = index;
  const track = S.queue[index];
  const token = ++loadToken;
  userPaused = false;
  const go = () => {
    track.resumeMs = startMs || 0;
    track.playingAlt = alternatives[track.videoId] || null;
    ytPlayer.loadVideoById({ videoId: track.playingAlt || track.videoId, startSeconds: Math.max(0, (startMs || 0) / 1000) });
    trackStartedAt = Date.now();
    ui.video(true);
    addRecent(track);
    publish();
    ui.render();
    extendRadio();
    // El navegador puede negarse a arrancar el sonido si la orden vino de otro
    // dispositivo y aún no has tocado nada en esta pestaña: se pide un toque.
    setTimeout(() => {
      if (token !== loadToken || !S.hereActive || userPaused) return;
      const s = playerState();
      if (s === -1 || s === 5) ui.needsTap(true, remote);
    }, 3500);
  };
  ytReady ? go() : (pendingLoad = go);
}

export function jumpTo(index) {
  noteListened();
  loadIndex(index, 0);
}

export async function skip(delta, auto = false) {
  if (delta < 0 && ytReady && ytPlayer.getCurrentTime() > 3) return seekTo(0);
  if (!auto) noteListened();
  const next = S.index + delta;
  if (next >= 0 && next < S.queue.length) return loadIndex(next, 0);
  if (delta > 0) {
    // Fin de la cola: sigue con radio de la última canción (como el teléfono).
    if (!S.radio && canSearchOnline) {
      S.radio = newRadio(S.queue[S.index], "focused");
      S.radioLabel = `Radio de ${S.queue[S.index]?.artist || "esta canción"}`;
    }
    if (await extendRadio({ force: true }) && S.index + 1 < S.queue.length) return loadIndex(S.index + 1, 0);
    if (auto) { pausePlayer(); publish(); }
  }
}

export function seekTo(ms) {
  if (!ytReady) return;
  ytPlayer.seekTo(ms / 1000, true);
  setTimeout(publish, 300);
}
export function resumePlayer() { userPaused = false; if (ytReady) ytPlayer.playVideo(); }
export function pausePlayer() { userPaused = true; if (ytReady) ytPlayer.pauseVideo(); }
export function togglePlay() { playingState(playerState()) ? pausePlayer() : resumePlayer(); }

/** "Escuchar aquí": continúa en este navegador lo que suena (o sonó) en cualquier dispositivo. */
export function playHere({ remote = false } = {}) {
  const p = S.playback;
  if (!p?.track) return;
  const queue = p.queue?.length ? p.queue : [p.track];
  const index = Math.min(p.queueIndex || 0, queue.length - 1);
  const label = p.radioLabel || "";
  playTracks(queue, index, estimatedPosition(p), { here: true, remote });
  // La cola del otro dispositivo sigue con radio de lo que sonaba.
  if (canSearchOnline) {
    S.radio = newRadio(queue[index], S.distance < 50 ? "focused" : "explore");
    S.radioLabel = label || `Radio de ${queue[index].artist || "esta canción"}`;
  }
}

/** Pasa la reproducción al dispositivo [id]. */
export async function transferTo(id) {
  if (id === deviceId) return playHere();
  if (id === S.playback?.activeDevice && isDeviceOnline(id)) return;
  if (!S.playback?.track && !S.hereActive) return ui.toast("No hay nada sonando que pasar");
  // Primero la posición exacta, luego la orden: así el otro coge el punto justo.
  if (S.hereActive) await publish();
  sendCommand("transfer", {}, id);
  ui.toast(`Pasando la música a ${S.devices[id]?.name || "el otro dispositivo"}…`);
}

function shuffleUpcoming() {
  const head = S.queue.slice(0, S.index + 1);
  S.queue = [...head, ...shuffled(S.queue.slice(S.index + 1))];
}
export function toggleShuffle() {
  S.shuffle = !S.shuffle;
  if (S.shuffle && S.hereActive) { shuffleUpcoming(); publish(); }
  ui.render();
}

/** "Añadir a la cola": tras la actual y tras lo que ya añadiste, antes de la radio. */
export function addToQueue(track) {
  if (!track?.videoId) return;
  if (isRemote()) { sendCommand("queue", { tracks: [clean(track)] }); return ui.toast("Añadida a la cola"); }
  if (S.hereActive && S.queue.length) { queueLocal(track); ui.toast("Añadida a la cola"); }
  else playTracks([track], 0, 0, { here: true });
}

function queueLocal(track) {
  if (!track?.videoId) return;
  let at = S.index + 1;
  while (at < S.queue.length && (S.queue[at].queued || S.queue[at].addedBy)) at++;
  S.queue.splice(at, 0, { ...track, queued: true, fromRadio: false });
  publish();
  ui.render();
}

export const shuffled = (arr) => {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
};

// ── Lo que suena, visto desde la interfaz ───────────────────────────────
export function currentTrack() { return S.hereActive ? S.queue[S.index] : S.playback?.track; }
export function currentDuration() {
  if (S.hereActive && ytReady && ytPlayer.getDuration?.() > 0) return ytPlayer.getDuration() * 1000;
  return S.playback?.durationMs || currentTrack()?.durationMs || 0;
}
export function currentPosition() {
  if (S.hereActive && ytReady) return (ytPlayer.getCurrentTime?.() || 0) * 1000;
  return estimatedPosition();
}
export function isPlayingNow() {
  if (S.hereActive) return playingState(playerState());
  const playing = !!S.playback?.isPlaying && isDeviceOnline(S.playback?.activeDevice);
  // Mientras llega la respuesta, el botón ya refleja lo que has pedido.
  if (S.pending && ["toggle", "play", "pause"].includes(S.pending.type)) return S.pending.type === "play" || (S.pending.type === "toggle" && !S.pending.wasPlaying);
  return playing;
}
export function visibleQueue() {
  return S.hereActive ? S.queue.slice(S.index, S.index + 50) : (S.playback?.queue || []);
}
export function radioInfo() {
  if (S.hereActive) return S.radio ? { label: S.radioLabel, distance: S.distance, loading: S.radioLoading } : null;
  if (isRemote() && S.playback.radioDistance != null) return { label: S.playback.radioLabel || "Radio", distance: S.playback.radioDistance, loading: false };
  return null;
}

/** Cada pocos segundos, mientras suena aquí, se publica la posición. */
export function tick() {
  if (S.hereActive && playingState(playerState()) && Date.now() - lastPublish > POSITION_PUBLISH_MS) publish();
}

function updateMediaSession(track, playing) {
  if (!("mediaSession" in navigator) || !track) return;
  const art = artFor(track);
  navigator.mediaSession.metadata = new MediaMetadata({
    title: track.title, artist: track.artist, album: track.album || "",
    artwork: art ? [{ src: art, sizes: "480x360" }] : [],
  });
  navigator.mediaSession.playbackState = playing ? "playing" : "paused";
}
export const artFor = (t) => t?.artworkUrl || (t?.videoId ? `https://i.ytimg.com/vi/${t.videoId}/hqdefault.jpg` : "");

function addRecent(track) {
  if (!track?.videoId) return;
  S.recents = [clean(track), ...S.recents.filter((t) => t.videoId !== track.videoId)].slice(0, 30);
  store.set("spotiaisexs-recents", S.recents);
}

// ── Emisoras ────────────────────────────────────────────────────────────
export const STATIONS = [
  { id: "mix", name: "Tu mezcla", hint: "Lo tuyo y algo nuevo, según el dial" },
  { id: "discover", name: "Descubrir", hint: "Canciones que no tienes" },
  { id: "rediscover", name: "Redescubrir", hint: "Lo que hace tiempo que no suena" },
  { id: "liked", name: "Me gusta", hint: "Tus favoritas, mezcladas" },
];
const PHONE_STATION = { mix: "TU_MEZCLA", discover: "DESCUBRIR", rediscover: "REDESCUBRIR", liked: "ME_GUSTA" };

export function playStation(id) {
  const station = STATIONS.find((s) => s.id === id);
  if (!station) return;
  if (isRemote()) {
    sendCommand("station", { name: PHONE_STATION[id] });
    return ui.toast(`${station.name} en ${S.playback.activeDeviceName || "el otro dispositivo"}`);
  }
  const liked = likedTracks();
  const pool = allLibraryTracks();
  const source = liked.length ? liked : pool;
  if (id === "liked") {
    if (!liked.length) return ui.toast("Aún no tienes canciones en Me gusta");
    return playTracks(shuffled(liked).slice(0, 100), 0, 0, { here: true });
  }
  if (id === "rediscover") {
    const recent = new Set(S.recents.map((t) => t.videoId));
    const old = pool.filter((t) => !recent.has(t.videoId));
    if (!old.length) return ui.toast("Tu biblioteca aún es pequeña para redescubrir");
    return playTracks(shuffled(old).slice(0, 60), 0, 0, { here: true });
  }
  const seed = source.length ? source[Math.floor(Math.random() * source.length)] : (currentTrack() || S.recents[0]);
  if (!seed) return ui.toast("Busca y escucha algo primero: la emisora parte de tus canciones");
  if (id === "discover") S.distance = Math.max(S.distance, 85);
  startRadio(seed, { style: id === "discover" || S.distance >= 50 ? "explore" : "focused", label: station.name, here: true });
}

// ── Biblioteca ──────────────────────────────────────────────────────────
// Los cambios se ven aquí al momento (se escriben en library/…) y además se
// dejan en libraryInbox para que el teléfono los aplique cuando se conecte.
export const sameSong = (a, b) => !!a && !!b && ((a.videoId && a.videoId === b.videoId) ||
  (norm(a.title) === norm(b.title) && norm(a.artist) === norm(b.artist)));
export const likedEntry = () => Object.entries(S.library).find(([, pl]) => pl?.mode === "liked");
export const likedTracks = () => likedEntry()?.[1]?.tracks || [];
export const isLiked = (track) => !!track && likedTracks().some((t) => sameSong(t, track));
export function sortedPlaylists() {
  return Object.entries(S.library)
    .map(([id, pl]) => ({ id, ...pl, tracks: pl.tracks || [] }))
    .sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || (b.mode === "liked" ? 1 : 0) - (a.mode === "liked" ? 1 : 0) || (b.createdAt || 0) - (a.createdAt || 0));
}
export function allLibraryTracks() {
  const seen = new Set();
  const out = [];
  Object.values(S.library).forEach((pl) => (pl.tracks || []).forEach((t) => {
    if (t?.videoId && !seen.has(t.videoId)) { seen.add(t.videoId); out.push(t); }
  }));
  return out;
}
/** Los artistas que más aparecen en tu biblioteca. */
export function topArtists(limit = 10) {
  const count = new Map();
  allLibraryTracks().forEach((t) => {
    const name = String(t.artist || "").split(/,|&| feat\.? | ft\.? | x /i)[0].trim();
    if (!name) return;
    const entry = count.get(name.toLowerCase()) || { name, n: 0, artworkUrl: artFor(t) };
    entry.n++;
    count.set(name.toLowerCase(), entry);
  });
  return [...count.values()].sort((a, b) => b.n - a.n).slice(0, limit);
}

function libraryOp(op) {
  if (!base) return;
  push(ref(db, `${base}/libraryInbox`), { ...op, at: serverTimestamp() }).catch(() => {});
}
function saveLibraryPlaylist(id, pl) {
  S.library = { ...S.library, [id]: pl };
  set(ref(db, `${base}/library/playlists/${id}`), pl).catch(() => {});
  ui.library();
}

export function toggleLike(track) {
  if (!track?.videoId) return;
  const t = clean(track);
  const entry = likedEntry();
  if (isLiked(track)) {
    const [id, pl] = entry;
    saveLibraryPlaylist(id, { ...pl, tracks: (pl.tracks || []).filter((x) => !sameSong(x, track)) });
    libraryOp({ op: "unlike", track: t });
    ui.toast("Quitada de Me gusta");
  } else {
    const [id, pl] = entry || ["web-liked", { title: "Me gusta", subtitle: "", mode: "liked", createdAt: Date.now(), tracks: [] }];
    saveLibraryPlaylist(id, { ...pl, tracks: [...(pl.tracks || []), t] });
    libraryOp({ op: "like", track: t });
    ui.toast("Añadida a Me gusta");
  }
}

export function addToPlaylist(id, track) {
  const pl = S.library[id];
  if (!pl || !track?.videoId) return;
  if ((pl.tracks || []).some((x) => sameSong(x, track))) return ui.toast(`Ya está en «${pl.title}»`);
  saveLibraryPlaylist(id, { ...pl, tracks: [...(pl.tracks || []), clean(track)] });
  libraryOp({ op: "add", playlistId: id, title: pl.title, track: clean(track) });
  ui.toast(`Añadida a «${pl.title}»`);
}

/** Crea una playlist, vacía o con [tracks] (guardar un álbum, una "Esto es"…). */
export function createPlaylist(title, tracks = [], artworkUrl = "") {
  const name = (title || "").trim().slice(0, 100);
  if (!name) return null;
  const id = `web-${Date.now().toString(36)}`;
  const list = tracks.filter((t) => t?.videoId).slice(0, 300).map(clean);
  saveLibraryPlaylist(id, { title: name, subtitle: "", mode: "custom", createdAt: Date.now(), tracks: list, ...(artworkUrl ? { artworkUrl } : {}) });
  libraryOp({ op: "create", title: name, ...(list.length === 1 ? { track: list[0] } : {}), ...(list.length > 1 ? { tracks: list } : {}) });
  ui.toast(list.length ? `Guardada «${name}» con ${list.length} ${list.length === 1 ? "canción" : "canciones"}` : `Creada «${name}»`);
  return id;
}

// ── Búsqueda ────────────────────────────────────────────────────────────
export async function searchAll(query) {
  const tokens = norm(query).split(/\s+/).filter(Boolean);
  const local = allLibraryTracks()
    .filter((t) => { const hay = norm(`${t.title} ${t.artist} ${t.album || ""}`); return tokens.every((k) => hay.includes(k)); })
    .slice(0, 12);
  if (catalogReady) return { local, ...(await catalog.search(query)) };
  if (youtubeApiKey) return { local, songs: await dataApiSearch(query, youtubeApiKey), artists: [], albums: [] };
  return { local, songs: [], artists: [], albums: [] };
}

const THIS_IS_MAX = 60;
const splitCredits = (credits) => String(credits || "").split(/\s*(?:,|&|;|\/|\+)\s*|\s+(?:ft\.?|feat\.?|featuring|with|and|x|y)\s+/i);
const simple = (s) => norm(s).replace(/[^\p{L}\p{N}]+/gu, " ").trim();
/** El artista canta en la canción: compara nombres enteros ("Eme" no es "Emeli Sandé"). */
export function isBy(credits, artist) {
  const wanted = simple(artist);
  if (!wanted || !credits) return false;
  return simple(credits) === wanted || splitCredits(credits).some((c) => simple(c) === wanted);
}
/** Una canción de cada álbum por turnos, para no poner un disco entero seguido. */
function interleave(lists) {
  const out = [];
  const longest = Math.max(0, ...lists.map((l) => l.length));
  for (let i = 0; i < longest; i++) lists.forEach((l) => { if (l[i]) out.push(l[i]); });
  return out;
}

/**
 * "Esto es {artista}": sus canciones alternadas con lo que tienes tú de ese
 * artista, hasta 60. La página del artista a veces solo trae 5, así que se
 * completa con una búsqueda de sus canciones y las de sus álbumes y sencillos.
 */
export async function buildThisIs(name, browseId = "") {
  let id = browseId;
  if (!id) id = (await catalog.searchArtists(name, 1))[0]?.browseId || "";
  if (!id) throw new Error(`No se ha encontrado a ${name}`);
  const page = await catalog.artist(id);
  const key = norm(page.name);
  const mine = allLibraryTracks().filter((t) => norm(t.artist).includes(key));
  let catalogSongs = page.topSongs;
  if (new Set([...page.topSongs, ...mine].map((t) => coreTitle(t.title))).size < THIS_IS_MAX) {
    const releases = [...page.albums, ...page.singles].filter((r) => r.browseId).slice(0, 12);
    const [found, ...albums] = await Promise.all([
      catalog.searchSongs(page.name, 60).catch(() => []),
      ...releases.map((r) => catalog.album(r.browseId).then((al) => al.tracks).catch(() => [])),
    ]);
    const more = [...found, ...interleave(albums)].filter((t) => isBy(t.artist, page.name));
    catalogSongs = [...page.topSongs, ...more];
  }
  const byKey = new Map();
  catalogSongs.forEach((t) => { const k = coreTitle(t.title); if (!byKey.has(k)) byKey.set(k, t); });
  const mineKeys = new Set(mine.map((t) => coreTitle(t.title)));
  // Si tienes el vídeo de una canción que también está en el catálogo, suena la canción.
  const mineSongs = mine.slice(0, 20).map((t) => byKey.get(coreTitle(t.title)) || t);
  const out = [];
  const seen = new Set();
  const add = (t) => {
    const k = coreTitle(t.title);
    if (out.length < THIS_IS_MAX && !seen.has(k) && !seen.has(t.videoId)) { seen.add(k); seen.add(t.videoId); out.push({ ...t, mine: mineKeys.has(k) }); }
  };
  for (let i = 0; i < Math.max(mineSongs.length, catalogSongs.length) && out.length < THIS_IS_MAX; i++) {
    if (mineSongs[i]) add(mineSongs[i]);
    if (catalogSongs[i]) add(catalogSongs[i]);
  }
  return { artist: page, tracks: out, hasMine: out.some((t) => t.mine) };
}

// ── Playlists de mood ───────────────────────────────────────────────────
// Las mismas que en la app: canciones de las playlists de YouTube Music para
// cada mood, una de cada lista por turnos. Las que tienes en tu biblioteca se
// marcan como tuyas y van primero.
export const MOODS = [
  { id: "gym", title: "Gym", subtitle: "Para darlo todo", searches: ["workout hits gym", "gym motivation songs"] },
  { id: "motivacion", title: "Motivación", subtitle: "Para venirte arriba", searches: ["canciones motivación", "feel good hits"] },
  { id: "chill", title: "Chill", subtitle: "Para bajar revoluciones", searches: ["chill vibes", "chill pop relax"] },
  { id: "fiesta", title: "Fiesta", subtitle: "Para la previa", searches: ["fiesta éxitos", "party hits"] },
  { id: "pop-es", title: "Pop español", subtitle: "Pop en español", searches: ["pop español éxitos", "pop en español"] },
  { id: "pop-en", title: "Pop inglés", subtitle: "Pop en inglés", searches: ["pop hits", "english pop hits"] },
  { id: "70s", title: "Años 70", subtitle: "Lo mejor de los 70", searches: ["70s hits", "éxitos años 70"] },
  { id: "80s", title: "Años 80", subtitle: "Lo mejor de los 80", searches: ["80s hits", "éxitos años 80"] },
  { id: "90s", title: "Años 90", subtitle: "Lo mejor de los 90", searches: ["90s hits", "éxitos años 90"] },
  { id: "2000s", title: "Años 2000", subtitle: "Lo mejor de los 2000", searches: ["2000s hits", "éxitos años 2000"] },
];
const VIDEO_LABEL = /\s*[(\[][^)\]]*(official\s+(music\s+)?video|music\s+video|lyrics?\s+video|video\s*(oficial|official|musical|lyric)|videoclip|visuali[sz]er|\bmv\b)[^)\]]*[)\]]|\s*[(\[]\s*video\s*[)\]]/gi;
const moodCache = new Map();

export async function buildMood(id) {
  const mood = MOODS.find((m) => m.id === id);
  if (!mood) throw new Error("Esta playlist no existe");
  const hit = moodCache.get(id);
  if (hit && Date.now() - hit.at < 30 * 60 * 1000) return hit.data;
  const found = (await Promise.all(mood.searches.map((q) => catalog.searchPlaylists(q, 6).catch(() => [])))).flat();
  const seenIds = new Set();
  const chosen = [...found.filter((p) => p.id.startsWith("RDCLAK") || /youtube music/i.test(p.author)), ...found]
    .filter((p) => !seenIds.has(p.id) && seenIds.add(p.id)).slice(0, 3);
  const lists = await Promise.all(chosen.map((p) => catalog.playlist(p.id).then((pl) => pl.tracks).catch(() => [])));
  const mineKeys = new Set(allLibraryTracks().map((t) => coreTitle(t.title)));
  const out = [];
  const seen = new Set();
  for (const t of interleave(lists)) {
    const title = t.title.replace(VIDEO_LABEL, "").trim() || t.title;
    const k = coreTitle(title);
    if (out.length >= THIS_IS_MAX || seen.has(k) || seen.has(t.videoId)) continue;
    seen.add(k); seen.add(t.videoId);
    out.push({ ...t, title, mine: mineKeys.has(k) });
  }
  if (!out.length) throw new Error(`No hay canciones para ${mood.title} ahora mismo. Reintenta.`);
  // Tus canciones primero, luego el resto en su orden.
  const tracks = [...out.filter((t) => t.mine), ...out.filter((t) => !t.mine)];
  const data = { mood, title: mood.title, artworkUrl: chosen[0]?.artworkUrl || tracks[0].artworkUrl, tracks, hasMine: tracks.some((t) => t.mine) };
  moodCache.set(id, { at: Date.now(), data });
  return data;
}

// ── Jam ─────────────────────────────────────────────────────────────────
// Los amigos abren jam.html (sin cuenta) y piden canciones; aquí solo se
// atienden si este navegador es el que reproduce (o, si no suena nada en
// ningún sitio, si fue el que abrió la Jam). Los enlaces de Spotify los
// resuelve el teléfono.
const JAM_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

export function jamLink() {
  if (!S.jam?.open || !S.jam.code || !S.user) return "";
  return `${new URL(".", location.href).href}jam.html#${S.user.uid}.${S.jam.code}`;
}
function jamProcessorHere() {
  const active = S.playback?.activeDevice;
  if (active && isDeviceOnline(active)) return active === deviceId && S.hereActive;
  return S.jam?.hostDevice === deviceId;
}
function onJamChanged() {
  ui.jam();
  if (!S.jam?.open || !jamProcessorHere()) return;
  const requests = Object.entries(S.jam.requests || {}).sort((a, b) => (a[1].at || 0) - (b[1].at || 0));
  for (const [id, req] of requests) {
    if (!req?.videoId || handledJamRequests.has(id)) continue;
    handledJamRequests.add(id);
    const track = { title: req.title || "Canción de la Jam", artist: req.artist || "", artworkUrl: req.artworkUrl, videoId: req.videoId, durationMs: req.durationMs, addedBy: req.by };
    if (S.hereActive && S.queue.length) {
      let at = S.index + 1;
      while (at < S.queue.length && S.queue[at].addedBy) at++;
      S.queue.splice(at, 0, track);
      publish();
    } else {
      playTracks([track], 0, 0, { here: true });
    }
    remove(ref(db, `${base}/jam/requests/${id}`));
    set(ref(db, `${base}/jam/added/${req.videoId}`), { by: req.by || "", at: serverTimestamp() });
    ui.toast(`${req.by || "Alguien"} ha añadido «${req.title || "una canción"}»`);
  }
  ui.render();
}
export async function startJam(hostName) {
  let code = "";
  crypto.getRandomValues(new Uint8Array(10)).forEach((b) => { code += JAM_ALPHABET[b % JAM_ALPHABET.length]; });
  handledJamRequests.clear();
  await set(ref(db, `${base}/jam`), {
    open: true, code, hostName: (hostName || "SpotiAisexs").trim().slice(0, 30), hostDevice: deviceId, createdAt: serverTimestamp(),
  });
}
export async function endJam() {
  await set(ref(db, `${base}/jam`), { open: false });
  S.queue.forEach((t) => { delete t.addedBy; });
  if (S.hereActive) publish();
}
