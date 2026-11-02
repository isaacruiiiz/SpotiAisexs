// SpotiAisexs web — reproductor completo que funciona solo o sincronizado con
// el teléfono (estilo Spotify Connect).
//
// · Sin teléfono: biblioteca (playlists que el teléfono subió a Firebase),
//   búsqueda (biblioteca + YouTube), radio infinita propia (radio.js),
//   Me gusta y playlists (se guardan aquí y el teléfono las aplica al
//   conectarse, vía libraryInbox) y reproducción aquí.
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
import { youtubeSearch as ytSearch } from "./youtube.js";
import { Radio, recordPlay, recordSkip } from "./radio.js";
import { accentFor, applyAccent } from "./accent.js";

const $ = (id) => document.getElementById(id);
const firebaseConfig = cfg.firebaseConfig || {};
const youtubeApiKey = (cfg.youtubeApiKey || "").trim();
const youtubeSearch = (query, opts) => ytSearch(query, youtubeApiKey, opts);
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
let jam = null;                  // users/{uid}/jam (Jam compartida)
const handledJamRequests = new Set();
let ownerEmail = "";
let ownerUid = "";
let radio = null;                // Radio activa (cola infinita) cuando suena aquí
let radioLoading = false;
let trackStartedAt = 0;          // para distinguir "escuchada" de "saltada"
let accentKey = "";
const RADIO_REFILL_AT = 5;       // quedan ≤5 canciones → pedir otra tanda

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
  // Un invitado de la Jam entra como usuario anónimo: aquí no cuenta como sesión.
  onAuthStateChanged(auth, (user) => (user && !user.isAnonymous ? startSession(user) : showLogin()));
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
  ownerUid = user.uid;
  ownerEmail = user.email || "";
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
  listen(`${base}/jam`, (v) => { jam = v; onJamChanged(); });

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
  if (!base) return Promise.resolve();
  const track = localQueue[localIndex];
  if (!track) return Promise.resolve();
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
  armDisconnect(true);
  updateMediaSession(track, playing);
  return set(ref(db, `${base}/playback`), doc).catch(() => {});
}

const clean = (t) => Object.fromEntries(
  ["title", "artist", "album", "artworkUrl", "videoId", "durationMs", "addedBy"]
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
  if (command.type === "playTracks" && command.tracks?.length) return playTracks(command.tracks, command.index || 0);
  if (!isActiveHere()) return;
  switch (command.type) {
    case "play": if (ytReady) ytPlayer.playVideo(); break;
    case "pause": if (ytReady) ytPlayer.pauseVideo(); break;
    case "toggle": togglePlay(); break;
    case "next": skip(1); break;
    case "previous": skip(-1); break;
    case "seek": if (command.positionMs != null) seekTo(command.positionMs); break;
    case "playIndex": if (command.index != null) loadIndex(localIndex + command.index, 0); break;
    case "queue": (command.tracks || []).forEach((t) => queueLocal(t)); break;
    case "radio": if (command.tracks?.[0]) startRadioHere(command.tracks[0], "focused"); break;
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
        if (e.data === YT.PlayerState.ENDED) { recordPlay(localQueue[localIndex]); trackStartedAt = 0; skip(1, true); }
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

/**
 * Reproduce [tracks] desde [index]. Si otro dispositivo está sonando (el
 * teléfono), suena allí, como en Spotify Connect; si no, aquí.
 * Una sola canción (buscada, de un enlace…) arranca su radio enfocada: la
 * cola sigue con canciones de su entorno. Una playlist suena tal cual y, al
 * acabarse, sigue con radio de su última canción.
 */
function playTracks(tracks, index = 0, startMs = 0, { here = false, radioStyle = null } = {}) {
  const playable = tracks.filter((t) => t?.videoId && t.playable !== false);
  if (!playable.length) return toast("No hay canciones reproducibles");
  const target = tracks[index];
  if (target && target.playable === false) return playBlocked(target);
  if (!here && isRemote()) {
    sendCommand("playTracks", { tracks: tracks.slice(index, index + 100).map(clean), index: 0 });
    toast(`Suena en ${playback.activeDeviceName || "el otro dispositivo"}`);
    return;
  }
  noteListened();
  localQueue = playable.map((t) => ({ ...t }));
  localIndex = Math.max(0, localQueue.findIndex((t) => t === target || t.videoId === target?.videoId));
  if (shuffleOn) shuffleUpcoming();
  const style = radioStyle || (playable.length === 1 ? "focused" : null);
  radio = style ? newRadio(localQueue[localIndex], style) : null;
  playback = { ...(playback || {}), activeDevice: deviceId };
  loadIndex(localIndex, startMs);
}

function newRadio(seed, style) {
  return new Radio({ seed, style, apiKey: youtubeApiKey, libraryTracks: allLibraryTracks });
}

/** Radio desde una canción en este navegador (o en el dispositivo que suena). */
function startRadioHere(seed, style = "focused") {
  if (!seed?.videoId) return;
  if (!youtubeApiKey) return toast("La radio necesita la clave de YouTube en config.js");
  if (isRemote() && style === "focused") {
    sendCommand("radio", { tracks: [clean(seed)] });
    toast(`Radio de «${seed.title}» en ${playback.activeDeviceName || "el otro dispositivo"}`);
    return;
  }
  playTracks([seed], 0, 0, { here: true, radioStyle: style });
  toast(style === "explore" ? "Radio infinita" : `Radio de «${seed.title}»`);
}

/** Escuchada (≥30 s) o saltada antes: alimenta el historial de la radio. */
function noteListened() {
  const prev = localQueue[localIndex];
  if (!prev || !trackStartedAt || !isActiveHere()) return;
  const listened = ytReady ? (ytPlayer.getCurrentTime?.() || 0) : 0;
  if (listened >= 30) recordPlay(prev);
  else if (prev.fromRadio) recordSkip(prev);
  trackStartedAt = 0;
}

/** Pide otra tanda a la radio cuando quedan pocas canciones por delante. */
async function extendRadio({ force = false } = {}) {
  if (!radio || radioLoading || !youtubeApiKey) return false;
  const remaining = localQueue.length - localIndex - 1;
  if (!force && remaining > RADIO_REFILL_AT) return false;
  radioLoading = true;
  renderQueue();
  const owner = radio;
  try {
    const batch = await radio.next(localQueue, 20);
    if (owner !== radio || !batch.length) return false;
    const known = new Set(localQueue.map((t) => t.videoId));
    const fresh = batch.filter((t) => !known.has(t.videoId));
    localQueue.push(...fresh);
    if (isActiveHere()) publish();
    return fresh.length > 0;
  } catch (error) {
    toast(error.message || "La radio no ha podido cargar más canciones");
    return false;
  } finally {
    radioLoading = false;
    render();
  }
}

function loadIndex(index, startMs) {
  if (index < 0 || index >= localQueue.length) return;
  localIndex = index;
  const track = localQueue[index];
  if (!track.videoId) return skip(1, true);
  const go = () => {
    ytPlayer.loadVideoById({ videoId: track.videoId, startSeconds: Math.max(0, (startMs || 0) / 1000) });
    trackStartedAt = Date.now();
    showVideo(true);
    publish();
    render();
    extendRadio();
  };
  ytReady ? go() : (pendingLoad = go);
}

async function skip(delta, auto = false) {
  if (delta < 0 && ytReady && ytPlayer.getCurrentTime() > 3) return seekTo(0);
  if (!auto) noteListened();
  const next = localIndex + delta;
  if (next >= 0 && next < localQueue.length) return loadIndex(next, 0);
  if (delta > 0) {
    // Fin de la cola: sigue con radio de la última canción (como el teléfono).
    if (!radio) radio = newRadio(localQueue[localIndex], "focused");
    if (await extendRadio({ force: true }) && localIndex + 1 < localQueue.length) return loadIndex(localIndex + 1, 0);
    if (auto) { if (ytReady) ytPlayer.pauseVideo(); publish(); }
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
  const index = Math.min(p.queueIndex || 0, queue.length - 1);
  playTracks(queue, index, estimatedPosition(p), { here: true });
  // La cola del otro dispositivo sigue con radio de lo que sonaba.
  radio = newRadio(queue[index], "focused");
}

function shuffleUpcoming() {
  const head = localQueue.slice(0, localIndex + 1);
  localQueue = [...head, ...shuffled(localQueue.slice(localIndex + 1))];
}

/** "Añadir a la cola": tras la actual y tras lo que ya añadiste, antes de la radio. */
function addToQueue(track) {
  if (!track?.videoId) return;
  if (track.playable === false) return playBlocked(track);
  if (isRemote()) {
    sendCommand("queue", { tracks: [clean(track)] });
    return toast("Añadida a la cola");
  }
  if (isActiveHere() && localQueue.length) {
    queueLocal(track);
    toast("Añadida a la cola");
  } else {
    playTracks([track], 0, 0, { here: true });
  }
}

function queueLocal(track) {
  if (!track?.videoId) return;
  let at = localIndex + 1;
  while (at < localQueue.length && (localQueue[at].queued || localQueue[at].addedBy)) at++;
  localQueue.splice(at, 0, { ...track, queued: true, fromRadio: false });
  publish();
  render();
}

/**
 * YouTube no deja reproducir esta canción fuera de su web/app (lo decide la
 * discográfica). Si el teléfono está conectado, que suene allí: la app la
 * reproduce sin esa limitación.
 */
function playBlocked(track) {
  const phone = Object.entries(devices).find(([id, d]) => d?.type === "phone" && isDeviceOnline(id));
  if (!phone) {
    toast("YouTube no deja reproducir esta canción en la web. Ábrela en el teléfono.");
    return;
  }
  push(ref(db, `${base}/commands/${phone[0]}`), {
    type: "playTracks", tracks: [clean(track)], index: 0, from: deviceId, at: serverTimestamp(),
  });
  if (isActiveHere() && ytReady) ytPlayer.pauseVideo();
  toast("YouTube no la deja en la web: suena en tu teléfono");
}

const shuffled = (arr) => {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [a[i], a[j]] = [a[j], a[i]]; }
  return a;
};

// ── Jam compartida ──────────────────────────────────────────────────────
// Los amigos abren jam.html (sin cuenta) y piden canciones; aquí solo se
// atienden si este navegador es el que reproduce (o, si no suena nada en
// ningún sitio, si fue el que abrió la Jam). Los enlaces de Spotify los
// resuelve el teléfono, porque un navegador no puede leer las páginas de Spotify.

const JAM_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

function jamLink() {
  if (!jam?.open || !jam.code) return "";
  const here = new URL(".", location.href);
  return `${here.href}jam.html#${ownerUid}.${jam.code}`;
}

function jamProcessorHere() {
  const active = playback?.activeDevice;
  if (active && isDeviceOnline(active)) return active === deviceId;
  return jam?.hostDevice === deviceId;
}

function onJamChanged() {
  $("jam-btn").classList.toggle("live", !!jam?.open);
  renderJamPanel();
  if (!jam?.open || !jamProcessorHere()) return;
  const requests = Object.entries(jam.requests || {}).sort((a, b) => (a[1].at || 0) - (b[1].at || 0));
  for (const [id, req] of requests) {
    if (!req?.videoId || handledJamRequests.has(id)) continue; // los enlaces de Spotify los resuelve el teléfono
    handledJamRequests.add(id);
    enqueueJamTrack({
      title: req.title || "Canción de la Jam",
      artist: req.artist || "",
      artworkUrl: req.artworkUrl,
      videoId: req.videoId,
      durationMs: req.durationMs,
      addedBy: req.by,
    });
    remove(ref(db, `${base}/jam/requests/${id}`));
    set(ref(db, `${base}/jam/added/${req.videoId}`), { by: req.by || "", at: serverTimestamp() });
    toast(`${req.by || "Alguien"} ha añadido «${req.title || "una canción"}»`);
  }
}

/** Como Spotify Jam: tras la canción actual y tras las que ya añadieron otros. */
function enqueueJamTrack(track) {
  if (isActiveHere() && localQueue.length) {
    let at = localIndex + 1;
    while (at < localQueue.length && localQueue[at].addedBy) at++;
    localQueue.splice(at, 0, track);
    publish();
    render();
  } else {
    playTracks([track], 0, 0, { here: true });
  }
}

async function startJam(hostName) {
  let code = "";
  const bytes = crypto.getRandomValues(new Uint8Array(10));
  bytes.forEach((b) => { code += JAM_ALPHABET[b % JAM_ALPHABET.length]; });
  handledJamRequests.clear();
  await set(ref(db, `${base}/jam`), {
    open: true,
    code,
    hostName: (hostName || "SpotiAisexs").trim().slice(0, 30),
    hostDevice: deviceId,
    createdAt: serverTimestamp(),
  });
}

async function endJam() {
  await set(ref(db, `${base}/jam`), { open: false });
  localQueue.forEach((t) => { delete t.addedBy; });
  if (isActiveHere()) publish();
}

function renderJamPanel() {
  const panel = $("jam-panel");
  if (panel.hidden) return;
  if (!jam?.open) {
    const defaultName = localStorage.getItem("spotiaisexs-jam-host") || ownerEmail.split("@")[0] || "";
    panel.innerHTML = `
      <h3>Jam</h3>
      <p class="muted small">Tus amigos añaden canciones a tu cola desde un enlace, sin cuenta y aunque usen Spotify.</p>
      <label class="jam-label">Tu nombre<input id="jam-host-name" maxlength="30" value="${esc(defaultName)}"></label>
      <button id="jam-start" class="btn-primary">Empezar Jam</button>`;
    $("jam-start").addEventListener("click", async () => {
      const name = $("jam-host-name").value.trim();
      localStorage.setItem("spotiaisexs-jam-host", name);
      $("jam-start").disabled = true;
      try { await startJam(name); } catch (e) { toast(`No se pudo empezar la Jam (${e.code || e.message})`); }
    });
    return;
  }
  const link = jamLink();
  const now = serverNow();
  const guests = [...new Set(Object.values(jam.guests || {})
    .filter((g) => g?.lastSeen && now - g.lastSeen < 90_000)
    .map((g) => g.name))];
  panel.innerHTML = `
    <h3>Jam en directo <span class="live-dot"></span></h3>
    <div class="jam-qr" id="jam-qr" aria-label="Código QR para unirse"></div>
    <p class="muted small">Que lo escaneen con la cámara, o mándales el enlace.</p>
    <div class="jam-link"><input id="jam-link" readonly value="${esc(link)}"><button id="jam-copy" class="btn-ghost">Copiar</button></div>
    <p class="small">${guests.length ? `En la Jam: ${guests.map(esc).join(", ")}` : "Aún no se ha unido nadie"}</p>
    <button id="jam-end" class="btn-ghost">Terminar Jam</button>`;
  drawQr($("jam-qr"), link);
  $("jam-copy").addEventListener("click", async () => {
    try { await navigator.clipboard.writeText(link); toast("Enlace copiado"); }
    catch { $("jam-link").select(); }
  });
  $("jam-end").addEventListener("click", () => endJam());
}

function drawQr(el, text) {
  if (!window.qrcode) { el.textContent = ""; return; }
  const qr = window.qrcode(0, "M");
  qr.addData(text);
  qr.make();
  el.innerHTML = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true });
}

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

$("jam-btn").addEventListener("click", () => {
  $("jam-panel").hidden = !$("jam-panel").hidden;
  $("device-menu").hidden = true;
  renderJamPanel();
});

$("device-btn").addEventListener("click", () => {
  $("device-menu").hidden = !$("device-menu").hidden;
  renderDevices();
});
document.addEventListener("click", (event) => {
  if (!event.target.closest(".devices")) $("device-menu").hidden = true;
  if (!event.target.closest(".jam")) $("jam-panel").hidden = true;
});

async function chooseDevice(id) {
  $("device-menu").hidden = true;
  if (id === playback?.activeDevice && isDeviceOnline(id)) return;
  if (id === deviceId) return playHere();
  // Primero la posición exacta, luego la orden: así el otro coge el punto justo.
  if (isActiveHere()) await publish();
  push(ref(db, `${base}/commands/${id}`), { type: "transfer", from: deviceId, at: serverTimestamp() });
  toast(`Pasando la música a ${devices[id]?.name || "el otro dispositivo"}…`);
}

document.querySelectorAll(".nav-item").forEach((b) => b.addEventListener("click", () => showView(b.dataset.view)));

// En el móvil, Jam y dispositivos van arriba a la derecha (la barra inferior es la cápsula fina).
{
  const narrow = matchMedia("(max-width: 900px)");
  const right = document.querySelector(".pb-right");
  const home = right.parentElement;
  const place = () => (narrow.matches ? $("mobile-top") : home).appendChild(right);
  narrow.addEventListener("change", place);
  place();
}

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

// ── Biblioteca editable desde la web ────────────────────────────────────
// Los cambios se ven aquí al momento (se escriben en library/…) y además se
// dejan en libraryInbox para que el teléfono los aplique a su base de datos
// cuando se conecte. Así la web funciona sola y nada se pierde.

const likedEntry = () => Object.entries(library).find(([, pl]) => pl?.mode === "liked");
const sameSong = (a, b) => !!a && !!b && ((a.videoId && a.videoId === b.videoId) ||
  (norm(a.title) === norm(b.title) && norm(a.artist) === norm(b.artist)));
const isLiked = (track) => !!track && !!likedEntry()?.[1]?.tracks?.some((t) => sameSong(t, track));
const customPlaylists = () => sortedPlaylists().filter((pl) => pl.mode !== "liked" && pl.mode !== "liked_songs");

function libraryOp(op) {
  if (!base) return;
  push(ref(db, `${base}/libraryInbox`), { ...op, at: serverTimestamp() }).catch(() => {});
}

function saveLibraryPlaylist(id, pl) {
  library = { ...library, [id]: pl };
  set(ref(db, `${base}/library/playlists/${id}`), pl).catch(() => {});
  renderLibrary();
  renderView();
}

function toggleLike(track) {
  if (!track?.videoId) return;
  const t = clean(track);
  const entry = likedEntry();
  if (isLiked(track)) {
    const [id, pl] = entry;
    saveLibraryPlaylist(id, { ...pl, tracks: (pl.tracks || []).filter((x) => !sameSong(x, track)) });
    libraryOp({ op: "unlike", track: t });
    toast("Quitada de Me gusta");
  } else {
    const [id, pl] = entry || [`web-liked`, { title: "Me gusta", subtitle: "", mode: "liked", createdAt: Date.now(), tracks: [] }];
    saveLibraryPlaylist(id, { ...pl, tracks: [...(pl.tracks || []), t] });
    libraryOp({ op: "like", track: t });
    toast("Añadida a Me gusta");
  }
  render();
}

function addToPlaylist(id, track) {
  const pl = library[id];
  if (!pl || !track?.videoId) return;
  if ((pl.tracks || []).some((x) => sameSong(x, track))) return toast(`Ya está en «${pl.title}»`);
  saveLibraryPlaylist(id, { ...pl, tracks: [...(pl.tracks || []), clean(track)] });
  libraryOp({ op: "add", playlistId: id, title: pl.title, track: clean(track) });
  toast(`Añadida a «${pl.title}»`);
}

function createPlaylist(title, track = null) {
  const name = (title || "").trim().slice(0, 100);
  if (!name) return;
  const id = `web-${Date.now().toString(36)}`;
  saveLibraryPlaylist(id, { title: name, subtitle: "", mode: "custom", createdAt: Date.now(), tracks: track ? [clean(track)] : [] });
  libraryOp({ op: "create", title: name, ...(track ? { track: clean(track) } : {}) });
  toast(track ? `Creada «${name}» con la canción` : `Creada «${name}»`);
}

// ── Menú de una canción (⋯ o clic derecho) ─────────────────────────────
const ICONS = {
  queue: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h12"/><path d="M4 12h12"/><path d="M4 18h7"/><path d="M18 14v6"/><path d="M15 17h6"/></svg>',
  radio: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="2"/><path d="M8.5 15.5a5 5 0 0 1 0-7"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M5.6 18.4a9 9 0 0 1 0-12.8"/><path d="M18.4 5.6a9 9 0 0 1 0 12.8"/></svg>',
  heart: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M12 20.5s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.7c0 5.6-7.5 10.2-7.5 10.2z"/></svg>',
  plus: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 5v14"/><path d="M5 12h14"/></svg>',
  list: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M5 4v16"/><path d="M10 4v16"/><path d="m15 4.5 4.5 15"/></svg>',
  back: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 6-6 6 6 6"/></svg>',
  more: '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>',
};
let menuAnchor = null;

function openTrackMenu(track, anchor) {
  if (!track) return;
  menuAnchor = anchor;
  renderMenu(track, "main");
}

function renderMenu(track, page) {
  const menu = $("menu");
  const head = `<div class="menu-head">${thumb(artFor(track))}<div class="t-text"><div class="t-title">${esc(track.title)}</div><div class="t-artist">${esc(track.artist)}</div></div></div>`;
  if (page === "main") {
    const liked = isLiked(track);
    menu.innerHTML = head + `
      <button class="menu-item" data-act="queue" role="menuitem">${ICONS.queue}Añadir a la cola</button>
      <button class="menu-item" data-act="radio" role="menuitem">${ICONS.radio}Iniciar radio</button>
      <div class="menu-sep"></div>
      <button class="menu-item${liked ? " on" : ""}" data-act="like" role="menuitem">${ICONS.heart}${liked ? "Quitar de Me gusta" : "Añadir a Me gusta"}</button>
      <button class="menu-item" data-act="playlists" role="menuitem">${ICONS.list}Añadir a una playlist</button>`;
  } else if (page === "playlists") {
    const pls = customPlaylists();
    menu.innerHTML = head + `
      <button class="menu-item menu-back" data-act="back" role="menuitem">${ICONS.back}Volver</button>
      <button class="menu-item" data-act="new" role="menuitem">${ICONS.plus}Nueva playlist</button>
      ${pls.length ? '<div class="menu-sep"></div>' : ""}
      ${pls.map((pl) => `<button class="menu-item" data-act="add" data-id="${esc(pl.id)}" role="menuitem">${thumb(pl.artworkUrl || artFor(pl.tracks[0]))}<span>${esc(pl.title)}</span></button>`).join("")}`;
    menu.querySelectorAll(".menu-item .thumb, .menu-item .thumb-ph").forEach((el) => { el.style.cssText = "width:32px;height:32px;border-radius:6px"; });
  } else if (page === "new") {
    menu.innerHTML = head + `
      <form id="new-pl-form" style="display:flex;flex-direction:column;gap:10px;padding:6px">
        <input id="new-pl-name" class="field" maxlength="100" placeholder="Nombre de la playlist" aria-label="Nombre de la playlist" required>
        <button class="btn-accent" type="submit">Crear y añadir</button>
      </form>`;
    $("new-pl-form").addEventListener("submit", (e) => { e.preventDefault(); createPlaylist($("new-pl-name").value, track); closeMenu(); });
    setTimeout(() => $("new-pl-name").focus(), 0);
  }
  menu.querySelectorAll("[data-act]").forEach((b) => b.addEventListener("click", () => {
    const act = b.dataset.act;
    if (act === "queue") { addToQueue(track); closeMenu(); }
    else if (act === "radio") { startRadioHere(track, "focused"); closeMenu(); }
    else if (act === "like") { toggleLike(track); closeMenu(); }
    else if (act === "playlists") renderMenu(track, "playlists");
    else if (act === "back") renderMenu(track, "main");
    else if (act === "new") renderMenu(track, "new");
    else if (act === "add") { addToPlaylist(b.dataset.id, track); closeMenu(); }
  }));
  menu.hidden = false;
  $("menu-scrim").hidden = false;
  placeMenu();
  menu.querySelector("[data-act], input")?.focus();
}

function placeMenu() {
  const menu = $("menu");
  const r = menuAnchor?.getBoundingClientRect?.() || menuAnchor || { left: innerWidth / 2, right: innerWidth / 2, top: innerHeight / 2, bottom: innerHeight / 2 };
  const w = menu.offsetWidth, h = menu.offsetHeight;
  let left = Math.min(innerWidth - w - 8, Math.max(8, (r.right ?? r.left) - w));
  let top = r.bottom + 6;
  if (top + h > innerHeight - 8) top = Math.max(8, r.top - h - 6);
  menu.style.left = `${left}px`;
  menu.style.top = `${top}px`;
}

function closeMenu() {
  $("menu").hidden = true;
  $("menu-scrim").hidden = true;
  menuAnchor = null;
}
$("menu-scrim").addEventListener("click", closeMenu);
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !$("menu").hidden) closeMenu(); });

$("new-playlist").addEventListener("click", (e) => {
  menuAnchor = e.currentTarget;
  const menu = $("menu");
  menu.innerHTML = `<form id="new-pl-form" style="display:flex;flex-direction:column;gap:10px;padding:6px">
      <input id="new-pl-name" class="field" maxlength="100" placeholder="Nombre de la playlist" aria-label="Nombre de la playlist" required>
      <button class="btn-accent" type="submit">Crear playlist</button></form>`;
  $("new-pl-form").addEventListener("submit", (ev) => { ev.preventDefault(); createPlaylist($("new-pl-name").value); closeMenu(); });
  menu.hidden = false;
  $("menu-scrim").hidden = false;
  const r = e.currentTarget.getBoundingClientRect();
  menu.style.left = `${Math.min(innerWidth - menu.offsetWidth - 8, r.left)}px`;
  menu.style.top = `${r.bottom + 6}px`;
  $("new-pl-name").focus();
});

["np-like", "pb-like"].forEach((id) => $(id).addEventListener("click", () => toggleLike(currentTrack())));

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
  const tokens = norm(query).split(/\s+/).filter(Boolean);
  const local = allLibraryTracks()
    .filter((t) => { const hay = norm(`${t.title} ${t.artist} ${t.album || ""}`); return tokens.every((k) => hay.includes(k)); })
    .slice(0, 30);
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
  updateAccent(track);
  const liked = isLiked(track);
  ["np-like", "pb-like"].forEach((id) => {
    $(id).setAttribute("aria-pressed", String(liked));
    $(id).setAttribute("aria-label", liked ? "Quitar de Me gusta" : "Añadir a Me gusta");
    $(id).disabled = !track?.videoId;
  });

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
  const label = $("radio-label");
  label.hidden = !(isActiveHere() && radio);
  label.textContent = radioLoading ? "Buscando más canciones…" : radio?.style === "explore" ? "Radio infinita" : `Radio de ${radio?.anchor?.artist || "esta canción"}`;
  list.innerHTML = queue.map((t, i) => `
    <li class="queue-item${i === 0 ? " current" : ""}" data-i="${i}">
      ${thumb(artFor(t))}
      <div class="q-text"><div class="q-title">${esc(t.title)}</div><div class="q-artist">${t.addedBy ? `<span class="q-by">${esc(t.addedBy)}</span>` : ""}${esc(t.artist)}</div></div>
      <button class="t-more" data-more="${i}" aria-label="Más opciones para ${esc(t.title)}" style="opacity:1">${ICONS.more}</button>
    </li>`).join("");
  list.querySelectorAll(".queue-item").forEach((li) => li.addEventListener("click", () => {
    const i = Number(li.dataset.i);
    if (i === 0) return;
    if (isActiveHere()) { noteListened(); loadIndex(localIndex + i, 0); }
    else if (isRemote()) sendCommand("playIndex", { index: i });
    else playTracks(queue, i, 0, { here: true });
  }));
  list.querySelectorAll("[data-more]").forEach((b) => b.addEventListener("click", (e) => {
    e.stopPropagation();
    openTrackMenu(queue[Number(b.dataset.more)], b);
  }));
}

/** El acento y el fondo siguen a la portada que suena. */
function updateAccent(track) {
  const url = artFor(track);
  const key = track?.videoId || track?.title || "";
  if (key === accentKey) return;
  accentKey = key;
  const img = $("backdrop-img");
  if (url) { if (img.getAttribute("src") !== url) img.src = url; } else img.removeAttribute("src");
  accentFor(url, key).then((tone) => { if (accentKey === key) applyAccent(tone); });
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
  const canRadio = !!youtubeApiKey && (allLibraryTracks().length > 0 || !!currentTrack());
  const EQ = '<div class="hero-eq" aria-hidden="true"><i style="height:45%"></i><i style="height:75%"></i><i style="height:100%"></i><i style="height:65%"></i><i style="height:38%"></i></div>';
  const PLAY_ICON = '<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M8 5.2v13.6l11-6.8z"/></svg>';
  $("view-home").innerHTML = `
    <h1>${greeting()}</h1>
    ${resume || canRadio ? `<div class="hero-row">
      ${resume ? `
        <button class="hero" id="resume">
          ${artFor(resume.track) ? `<img src="${esc(artFor(resume.track))}" alt="" data-fallback>` : ""}
          <div class="hero-body">
            <span class="hero-kicker">Sigue donde lo dejaste</span>
            <div><div class="hero-title">${esc(resume.track.title)}</div><div class="hero-sub">${esc(resume.track.artist)} · en ${esc(resume.activeDeviceName || "otro dispositivo")}</div></div>
            <span class="hero-cta">${PLAY_ICON}Reanudar aquí</span>
          </div>
        </button>` : ""}
      ${canRadio ? `
        <button class="hero radio" id="infinite-radio">
          ${EQ}
          <div class="hero-body">
            <span class="hero-kicker">Para ti</span>
            <div><div class="hero-title">Radio infinita</div><div class="hero-sub">Canciones nuevas a partir de lo que te gusta</div></div>
            <span class="hero-cta">${PLAY_ICON}Empezar</span>
          </div>
        </button>` : ""}
    </div>` : ""}
    ${pls.length ? `
      <div class="quick">${pls.slice(0, 6).map((pl) => `
        <button class="quick-item" data-id="${esc(pl.id)}">${thumb(pl.artworkUrl || artFor(pl.tracks[0]))}<span>${esc(pl.title)}</span></button>`).join("")}
      </div>
      <h2>Tus playlists</h2>
      <div class="grid">${pls.map((pl) => card(pl)).join("")}</div>`
    : `<div class="empty-state">
        <strong>Tu biblioteca está vacía.</strong><br>
        Enlaza el teléfono en <em>Ajustes → Web y Jam</em> y tus playlists aparecerán aquí, aunque luego el teléfono esté apagado.
        ${youtubeApiKey ? "También puedes buscar cualquier canción, darle a Me gusta y crear playlists desde aquí." : ""}
      </div>`}`;
  $("resume")?.addEventListener("click", playHere);
  $("infinite-radio")?.addEventListener("click", () => {
    const pool = allLibraryTracks();
    const liked = likedEntry()?.[1]?.tracks || [];
    const source = liked.length ? liked : pool;
    const seed = source.length ? source[Math.floor(Math.random() * source.length)] : currentTrack();
    startRadioHere(seed, "explore");
  });
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
  return `<ol class="tracks">${tracks.map((t, i) => {
    const liked = isLiked(t);
    return `
    <li class="track${t.playable === false ? " blocked" : ""}" data-i="${i}" data-video="${esc(t.videoId)}">
      <span class="t-num">${numbered ? i + 1 : ""}</span>
      ${thumb(artFor(t))}
      <div class="t-text"><div class="t-title">${esc(t.title)}</div><div class="t-artist">${t.playable === false ? '<span class="t-badge">Solo en el teléfono</span>' : ""}${esc(t.artist)}${t.durationMs ? ` · ${fmt(t.durationMs)}` : ""}</div></div>
      <button class="t-like" data-like="${i}" aria-pressed="${liked}" aria-label="${liked ? "Quitar de Me gusta" : "Añadir a Me gusta"}">${ICONS.heart.replace('width="20" height="20"', 'width="18" height="18"')}</button>
      <button class="t-more" data-more="${i}" aria-label="Más opciones para ${esc(t.title)}">${ICONS.more}</button>
    </li>`;
  }).join("")}</ol>`;
}

/**
 * Clic: playlist → suena desde esa canción; resultado de búsqueda → esa
 * canción y su radio. ⋯ o clic derecho: menú (cola, radio, Me gusta, playlist).
 */
function bindTrackRows(root, tracks, { single = false } = {}) {
  root.querySelectorAll(".track").forEach((row) => {
    const i = Number(row.dataset.i);
    row.addEventListener("click", () => (single ? playTracks([tracks[i]], 0) : playTracks(tracks, i)));
    row.addEventListener("contextmenu", (e) => { e.preventDefault(); openTrackMenu(tracks[i], { left: e.clientX, right: e.clientX + 270, top: e.clientY, bottom: e.clientY }); });
  });
  root.querySelectorAll("[data-more]").forEach((b) => b.addEventListener("click", (e) => {
    e.stopPropagation();
    openTrackMenu(tracks[Number(b.dataset.more)], b);
  }));
  root.querySelectorAll("[data-like]").forEach((b) => b.addEventListener("click", (e) => {
    e.stopPropagation();
    toggleLike(tracks[Number(b.dataset.like)]);
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
  if ($("sr-local")) bindTrackRows($("sr-local"), s.local, { single: true });
  if ($("sr-remote")) bindTrackRows($("sr-remote"), s.remote, { single: true });
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
setInterval(() => {
  if (playback?.activeDevice && !isActiveHere()) render();
  if (jam?.open) onJamChanged(); // invitados conectados + peticiones pendientes si cambia quién reproduce
}, 10_000);
