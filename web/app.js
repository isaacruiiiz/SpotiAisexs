// SpotiAisexs web — reproductor sincronizado con el teléfono (estilo Spotify Connect).
//
// Un solo dispositivo es el "activo" y reproduce; publica users/{uid}/playback.
// Los demás muestran ese estado y mandan órdenes a users/{uid}/commands/{id}.
// "Reproducir aquí" copia cola y posición, se queda el sitio de activo y el
// dispositivo anterior se pausa solo. Ver docs/WEB_SYNC.md.

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, signOut,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import {
  getDatabase, ref, onValue, set, push, remove, onChildAdded, serverTimestamp, onDisconnect, off,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-database.js";
import { firebaseConfig } from "./config.js";

const $ = (id) => document.getElementById(id);
const ONLINE_WINDOW_MS = 75_000;
const HEARTBEAT_MS = 30_000;
const POSITION_PUBLISH_MS = 10_000;
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
let playback = null;          // documento compartido
let devices = {};             // dispositivos conectados
let localQueue = [];          // cola cuando este navegador es el activo
let localIndex = 0;
let ytPlayer = null, ytReady = false, pendingLoad = null;
let lastPositionPublish = 0;
let unsubscribers = [];
let heartbeatTimer = null;

const isActiveHere = () => playback?.activeDevice === deviceId;

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
  if (isActiveHere()) pauseLocal(true);
  await signOut(auth);
});

function startSession(user) {
  stopSession();
  base = `users/${user.uid}`;
  $("login").hidden = true;
  $("app").hidden = false;

  // Presencia: aparece en la lista de dispositivos y se borra al cerrar la pestaña.
  const deviceRef = ref(db, `${base}/devices/${deviceId}`);
  const beat = () => set(deviceRef, { name: deviceName, type: "web", lastSeen: serverTimestamp() });
  beat();
  onDisconnect(deviceRef).remove();
  heartbeatTimer = setInterval(beat, HEARTBEAT_MS);

  const connectedRef = ref(db, ".info/connected");
  onValue(connectedRef, (snap) => $("sync-dot").classList.toggle("on", snap.val() === true));
  unsubscribers.push(() => off(connectedRef));

  const playbackRef = ref(db, `${base}/playback`);
  onValue(playbackRef, (snap) => onPlayback(snap.val()));
  unsubscribers.push(() => off(playbackRef));

  const devicesRef = ref(db, `${base}/devices`);
  onValue(devicesRef, (snap) => { devices = snap.val() || {}; renderDevices(); });
  unsubscribers.push(() => off(devicesRef));

  const commandsRef = ref(db, `${base}/commands/${deviceId}`);
  onChildAdded(commandsRef, (snap) => {
    const command = snap.val();
    remove(snap.ref);
    if (!command || (command.at && Date.now() - command.at > COMMAND_MAX_AGE_MS)) return;
    executeCommand(command);
  });
  unsubscribers.push(() => off(commandsRef));
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
    pauseLocal(false);
    showVideo(false);
  }
  render();
}

function estimatedPosition(p) {
  if (!p) return 0;
  const elapsed = p.isPlaying && p.updatedAt ? Math.max(0, Date.now() - p.updatedAt) : 0;
  const pos = (p.positionMs || 0) + elapsed;
  return p.durationMs > 0 ? Math.min(pos, p.durationMs) : pos;
}

function publish() {
  if (!base) return;
  const track = localQueue[localIndex];
  if (!track) return;
  const playing = ytReady && ytPlayer.getPlayerState?.() === YT.PlayerState.PLAYING;
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
  playback = { ...doc, updatedAt: Date.now() };
  set(ref(db, `${base}/playback`), doc);
}

const clean = (t) => Object.fromEntries(Object.entries(t || {}).filter(([, v]) => v !== undefined && v !== null));

function sendCommand(type, extra = {}) {
  const target = playback?.activeDevice;
  if (!base || !target || target === deviceId) return;
  push(ref(db, `${base}/commands/${target}`), { type, from: deviceId, at: serverTimestamp(), ...extra });
}

function executeCommand(command) {
  if (command.type === "transfer") return playHere();
  if (!isActiveHere()) return;
  switch (command.type) {
    case "play": ytPlayer?.playVideo(); break;
    case "pause": ytPlayer?.pauseVideo(); break;
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
        if (pendingLoad) { const p = pendingLoad; pendingLoad = null; p(); }
      },
      onStateChange: (e) => {
        if (!isActiveHere()) return;
        if (e.data === YT.PlayerState.ENDED) skip(1);
        else if (e.data === YT.PlayerState.PLAYING || e.data === YT.PlayerState.PAUSED) { publish(); render(); }
      },
      // 101/150: el vídeo no permite reproducirse incrustado → siguiente.
      onError: () => { if (isActiveHere()) skip(1); },
    },
  });
};

// Cargar la API de YouTube después de definir el callback (evita la carrera
// en la que la API termina antes que este módulo y nunca avisa).
{
  const tag = document.createElement("script");
  tag.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(tag);
}

function showVideo(visible) {
  // YouTube exige que su reproductor sea visible mientras suena.
  $("yt-holder").hidden = !visible;
}

function loadIndex(index, startMs) {
  if (index < 0 || index >= localQueue.length) return;
  localIndex = index;
  const track = localQueue[index];
  if (!track.videoId) return skip(1);
  const go = () => {
    ytPlayer.loadVideoById({ videoId: track.videoId, startSeconds: Math.max(0, (startMs || 0) / 1000) });
    showVideo(true);
    publish();
    render();
  };
  ytReady ? go() : (pendingLoad = go);
}

function skip(delta) {
  const next = localIndex + delta;
  if (delta < 0 && ytReady && ytPlayer.getCurrentTime() > 3) return seekTo(0);
  if (next >= 0 && next < localQueue.length) loadIndex(next, 0);
  else if (delta > 0) { pauseLocal(true); }
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

function pauseLocal(announce) {
  if (ytReady) ytPlayer.pauseVideo();
  if (announce && isActiveHere()) setTimeout(publish, 200);
}

/** "Reproducir aquí": este navegador pasa a ser el dispositivo activo. */
function playHere() {
  const p = playback;
  if (!p?.track) return;
  localQueue = (p.queue?.length ? p.queue : [p.track]).map((t) => ({ ...t }));
  localIndex = Math.min(p.queueIndex || 0, localQueue.length - 1);
  // Quedarse el sitio de activo primero: el teléfono ve el cambio y se pausa.
  playback = { ...p, activeDevice: deviceId };
  loadIndex(localIndex, estimatedPosition(p));
}

// ── Controles ───────────────────────────────────────────────────────────
$("play").addEventListener("click", () => {
  if (isActiveHere()) togglePlay();
  else if (playback?.activeDevice) sendCommand("toggle");
});
$("next").addEventListener("click", () => (isActiveHere() ? skip(1) : sendCommand("next")));
$("prev").addEventListener("click", () => (isActiveHere() ? skip(-1) : sendCommand("previous")));

$("bar").addEventListener("click", (event) => {
  const duration = currentDuration();
  if (!duration) return;
  const rect = event.currentTarget.getBoundingClientRect();
  const ms = Math.round(((event.clientX - rect.left) / rect.width) * duration);
  isActiveHere() ? seekTo(ms) : sendCommand("seek", { positionMs: ms });
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
  if (id === playback?.activeDevice) return;
  if (id === deviceId) return playHere();
  // Pasar la reproducción a otro dispositivo (p. ej. el teléfono).
  if (isActiveHere()) publish();
  push(ref(db, `${base}/commands/${id}`), { type: "transfer", from: deviceId, at: serverTimestamp() });
}

// ── Pintado ─────────────────────────────────────────────────────────────
// Portada que no carga → se muestra el logo en su lugar.
$("cover").addEventListener("error", () => { $("cover").hidden = true; $("cover-empty").hidden = false; });

function currentDuration() {
  if (isActiveHere() && ytReady && ytPlayer.getDuration?.() > 0) return ytPlayer.getDuration() * 1000;
  return playback?.durationMs || playback?.track?.durationMs || 0;
}

function currentPosition() {
  if (isActiveHere() && ytReady) return (ytPlayer.getCurrentTime?.() || 0) * 1000;
  return estimatedPosition(playback);
}

function isPlayingNow() {
  if (isActiveHere() && ytReady) return ytPlayer.getPlayerState?.() === YT.PlayerState.PLAYING;
  return !!playback?.isPlaying;
}

const fmt = (ms) => {
  const s = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};

function render() {
  const track = isActiveHere() ? localQueue[localIndex] : playback?.track;
  $("title").textContent = track?.title || "Nada sonando";
  $("artist").textContent = track?.artist || "Pon algo en el teléfono o aquí";
  document.title = track ? `${track.title} · ${track.artist}` : "SpotiAisexs";

  const art = track?.artworkUrl || (track?.videoId ? `https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg` : "");
  if (art && $("cover").src !== art) {
    $("cover").hidden = false;
    $("cover-empty").hidden = true;
    $("cover").src = art;
  } else if (!art) {
    $("cover").hidden = true;
    $("cover-empty").hidden = false;
  }
  if (!isActiveHere()) showVideo(false);

  const playing = isPlayingNow();
  // SVG elements have no .hidden property: toggle the attribute instead.
  $("ic-play").toggleAttribute("hidden", playing);
  $("ic-pause").toggleAttribute("hidden", !playing);
  $("play").setAttribute("aria-label", playing ? "Pausar" : "Reproducir");

  const label = playback?.activeDevice
    ? (isActiveHere() ? "Reproduciendo en este navegador" : `Escuchando en ${playback.activeDeviceName || "otro dispositivo"}`)
    : "Sin dispositivo activo";
  $("device-label").textContent = label;
  $("device-btn").classList.toggle("remote", !!playback?.activeDevice && !isActiveHere());

  renderQueue();
  renderDevices();
}

function renderQueue() {
  const list = $("queue-list");
  const queue = isActiveHere() ? localQueue.slice(localIndex, localIndex + 50) : (playback?.queue || []);
  list.replaceChildren();
  $("queue-empty").hidden = queue.length > 0;
  queue.forEach((t, i) => {
    const li = document.createElement("li");
    li.className = `queue-item${i === 0 ? " current" : ""}`;
    const thumb = t.artworkUrl || (t.videoId ? `https://i.ytimg.com/vi/${t.videoId}/default.jpg` : "");
    li.innerHTML = `${thumb ? `<img alt="" loading="lazy">` : `<div class="q-thumb"></div>`}<div class="q-text"><div class="q-title"></div><div class="q-artist"></div></div>`;
    if (thumb) {
      const img = li.querySelector("img");
      img.addEventListener("error", () => img.replaceWith(Object.assign(document.createElement("div"), { className: "q-thumb" })), { once: true });
      img.src = thumb;
    }
    li.querySelector(".q-title").textContent = t.title;
    li.querySelector(".q-artist").textContent = t.artist;
    li.addEventListener("click", () => {
      if (i === 0) return;
      isActiveHere() ? loadIndex(localIndex + i, 0) : sendCommand("playIndex", { index: i });
    });
    list.appendChild(li);
  });
}

function renderDevices() {
  const menu = $("device-menu");
  if (menu.hidden) return;
  const now = Date.now();
  const entries = Object.entries(devices)
    .filter(([id, d]) => id === deviceId || (d?.lastSeen && now - d.lastSeen < ONLINE_WINDOW_MS));
  if (!entries.some(([id]) => id === deviceId)) entries.unshift([deviceId, { name: deviceName, type: "web" }]);
  menu.replaceChildren();
  const h = document.createElement("h3");
  h.textContent = "Conectar a un dispositivo";
  menu.appendChild(h);
  entries.forEach(([id, d]) => {
    const b = document.createElement("button");
    b.className = `device-item${id === playback?.activeDevice ? " active" : ""}`;
    const icon = d.type === "phone"
      ? '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/></svg>'
      : '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8"/></svg>';
    b.innerHTML = `${icon}<span><span class="d-name"></span><small></small></span>`;
    b.querySelector(".d-name").textContent = id === deviceId ? "Este navegador" : d.name;
    b.querySelector("small").textContent = id === playback?.activeDevice ? "Reproduciendo" : (id === deviceId ? deviceName : "Disponible");
    b.addEventListener("click", () => chooseDevice(id));
    menu.appendChild(b);
  });
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

// Atajos: espacio = play/pausa, flechas = anterior/siguiente.
document.addEventListener("keydown", (event) => {
  if (event.target.closest("input")) return;
  if (event.code === "Space") { event.preventDefault(); $("play").click(); }
  if (event.code === "ArrowRight" && event.shiftKey) $("next").click();
  if (event.code === "ArrowLeft" && event.shiftKey) $("prev").click();
});
