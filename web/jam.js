// SpotiAisexs · Jam para invitados.
//
// Se abre con jam.html#<uid del anfitrión>.<código>. Los invitados entran como
// usuarios anónimos de Firebase (sin cuenta) y ven la cola: lo que suena y lo
// que viene. Desde ahí buscan una canción y la añaden con el menú ⋯ o
// deslizándola hacia la derecha, como en Spotify. Las reglas de la base de
// datos solo aceptan peticiones con el código de la Jam y mientras esté
// abierta; el dispositivo que reproduce las mete en la cola.

import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-app.js";
import { getAuth, onAuthStateChanged, signInAnonymously } from "https://www.gstatic.com/firebasejs/10.12.2/firebase-auth.js";
import {
  getDatabase, ref, onValue, set, push, serverTimestamp, onDisconnect, off,
} from "https://www.gstatic.com/firebasejs/10.12.2/firebase-database.js";
import * as cfg from "./config.js";
import { youtubeSearch } from "./youtube.js";
import { accentFor, applyAccent } from "./accent.js";

const $ = (id) => document.getElementById(id);
const youtubeApiKey = (cfg.youtubeApiKey || "").trim();
const HEARTBEAT_MS = 30_000;
const ONLINE_MS = 90_000;
const SWIPE_ADD_PX = 72;
const SPOTIFY_LINK = /(open\.spotify\.com\/(?:intl-[a-z]{2}(?:-[a-z]{2})?\/)?track\/[A-Za-z0-9]{22}|spotify:track:[A-Za-z0-9]{22}|spotify\.link\/\S+|spotify\.app\.link\/\S+)/i;

const storage = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch { /* sin almacenamiento */ } },
};
const [hostUid, code] = decodeURIComponent(location.hash.slice(1)).split(".");
let db, me = null, base = "";
let hostName = "";
let playback = null, added = {}, guests = {};
let joined = false;
let heartbeat = null;
let listeners = [];
let results = [];
let sheetTrack = null;
const myRequests = new Map();   // videoId → { title, at }

// ── Arranque ────────────────────────────────────────────────────────────
if (!hostUid || !code) {
  showStatus("Este enlace de Jam no es válido. Pide a quien la ha creado que te lo mande otra vez.");
} else if (!cfg.firebaseConfig?.apiKey || cfg.firebaseConfig.apiKey.startsWith("PEGA_")) {
  showStatus("Esta web todavía no está configurada.");
} else {
  base = `users/${hostUid}`;
  const app = initializeApp(cfg.firebaseConfig);
  const auth = getAuth(app);
  db = getDatabase(app);
  onAuthStateChanged(auth, async (user) => {
    if (!user) {
      try { await signInAnonymously(auth); } catch (e) {
        showStatus(e.code === "auth/operation-not-allowed" || e.code === "auth/admin-restricted-operation"
          ? "La Jam no está activada en Firebase (falta el acceso anónimo). Avisa a quien te ha invitado."
          : `No se pudo conectar (${e.code || e.message}).`);
      }
      return;
    }
    me = user;
    watchJam();
  });
}

function watch(path, onData) {
  const r = ref(db, path);
  onValue(r, (snap) => onData(snap.val()), () => ended());
  listeners.push(() => off(r));
}

function watchJam() {
  watch(`${base}/jam/hostName`, (v) => {
    hostName = v || "";
    $("jam-title").textContent = hostName ? `Jam de ${hostName}` : "Jam";
    document.title = hostName ? `Jam de ${hostName} · SpotiAisexs` : "Jam · SpotiAisexs";
  });
  watch(`${base}/jam/open`, (open) => {
    if (open !== true) return ended();
    if (!joined) askName();
  });
}

function askName() {
  $("status").hidden = true;
  $("join").hidden = false;
  $("guest-name").value = storage.get("jam-guest-name") || "";
  $("guest-name").focus();
}

$("join").addEventListener("submit", (e) => {
  e.preventDefault();
  const name = $("guest-name").value.trim().slice(0, 30);
  if (!name) return;
  storage.set("jam-guest-name", name);
  join(name);
});

function join(name) {
  joined = true;
  $("join").hidden = true;
  $("live").hidden = false;
  if (!youtubeApiKey) $("search-input").placeholder = "Pega el enlace de una canción de Spotify";

  const presence = ref(db, `${base}/jam/guests/${me.uid}`);
  const beat = () => set(presence, { name, code, lastSeen: serverTimestamp() }).catch(() => {});
  beat();
  onDisconnect(presence).remove().catch(() => {});
  heartbeat = setInterval(beat, HEARTBEAT_MS);

  watch(`${base}/playback`, (v) => { playback = v; render(); });
  watch(`${base}/jam/added`, (v) => { added = v || {}; render(); });
  watch(`${base}/jam/guests`, (v) => { guests = v || {}; renderGuests(); });
}

function ended() {
  listeners.forEach((fn) => fn());
  listeners = [];
  clearInterval(heartbeat);
  closeSheet();
  $("live").hidden = true;
  $("join").hidden = true;
  showStatus("Esta Jam ha terminado. ¡Gracias por la música!");
}

function showStatus(text) {
  $("status").hidden = false;
  $("status-text").textContent = text;
}

// ── Buscar ──────────────────────────────────────────────────────────────
$("search-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const q = $("search-input").value.trim();
  if (!q) return;
  $("search-input").blur();
  showResults(true);
  const link = q.match(SPOTIFY_LINK);
  if (link) return addSpotifyLink(normalizeLink(link[0]));
  if (!youtubeApiKey) return note("Aquí solo se pueden pegar enlaces de canciones de Spotify.");
  await search(q);
});
$("search-input").addEventListener("focus", () => { $("search-cancel").hidden = false; });
$("search-cancel").addEventListener("click", () => {
  $("search-input").value = "";
  showResults(false);
});

function showResults(on) {
  $("results-view").hidden = !on;
  $("queue-view").hidden = on;
  $("search-cancel").hidden = !on;
  if (!on) { results = []; $("results").innerHTML = ""; }
}

const normalizeLink = (l) => (l.startsWith("http") || l.startsWith("spotify:") ? l : `https://${l}`);
const note = (text) => { $("results").innerHTML = `<li class="section-note">${esc(text)}</li>`; };

async function search(query, heading = "") {
  note("Buscando…");
  try {
    const found = await youtubeSearch(query, youtubeApiKey);
    // Primero las que se pueden poner en cualquier dispositivo.
    results = [...found.filter((t) => t.playable), ...found.filter((t) => !t.playable)].slice(0, 20);
    $("results").innerHTML = (heading ? `<li class="section-note">${esc(heading)}</li>` : "")
      + (results.length ? results.map(resultRow).join("") : `<li class="section-note">No hay resultados.</li>`);
    bindRows();
  } catch (error) {
    note(error.message);
  }
}

const isDone = (videoId) => myRequests.has(videoId) || isQueued(videoId) || playback?.track?.videoId === videoId;

function resultRow(t, i) {
  return `<li class="track-wrap" data-i="${i}">
    <div class="swipe-bg" aria-hidden="true">${QUEUE_ICON}</div>
    <div class="track">
      ${t.artworkUrl ? `<img src="${esc(t.artworkUrl)}" alt="" loading="lazy" draggable="false">` : `<div class="ph"></div>`}
      <div class="t-text"><div class="t-title">${esc(t.title)}</div><div class="t-sub">${isDone(t.videoId) ? `<span class="t-added">En la cola</span> · ` : ""}${esc(t.artist)}${t.durationMs ? ` · ${fmt(t.durationMs)}` : ""}</div></div>
      <button class="t-more" type="button" data-more="${i}" aria-label="Más opciones para ${esc(t.title)}">${MORE_ICON}</button>
    </div>
  </li>`;
}

function bindRows() {
  $("results").querySelectorAll("[data-more]").forEach((b) => b.addEventListener("click", (e) => {
    e.stopPropagation();
    openSheet(results[Number(b.dataset.more)]);
  }));
  $("results").querySelectorAll(".track-wrap").forEach((li) => enableSwipe(li, () => results[Number(li.dataset.i)]));
}

// ── Deslizar a la derecha = añadir a la cola (como en Spotify) ─────────
function enableSwipe(wrap, getTrack) {
  const row = wrap.querySelector(".track");
  const bg = wrap.querySelector(".swipe-bg");
  let startX = 0, startY = 0, dx = 0, active = false, decided = false, pointerId = null;

  const reset = () => {
    row.classList.remove("dragging");
    row.style.transform = "";
    bg.style.opacity = "0";
    active = false; decided = false; dx = 0;
  };
  row.addEventListener("pointerdown", (e) => {
    if (e.target.closest(".t-more") || (e.pointerType === "mouse" && e.button !== 0)) return;
    startX = e.clientX; startY = e.clientY; dx = 0; active = true; decided = false; pointerId = e.pointerId;
  });
  row.addEventListener("pointermove", (e) => {
    if (!active || e.pointerId !== pointerId) return;
    const mx = e.clientX - startX, my = e.clientY - startY;
    if (!decided) {
      if (Math.abs(mx) < 8 && Math.abs(my) < 8) return;
      decided = true;
      // Gesto vertical: es un scroll, no un deslizamiento.
      if (Math.abs(my) > Math.abs(mx) || mx < 0) { active = false; return; }
      row.setPointerCapture(pointerId);
      row.classList.add("dragging");
    }
    dx = Math.max(0, Math.min(mx, 140));
    row.style.transform = `translateX(${dx}px)`;
    bg.style.opacity = String(Math.min(1, dx / SWIPE_ADD_PX));
  });
  const finish = () => {
    if (!active) return;
    const add = dx >= SWIPE_ADD_PX;
    reset();
    if (add) {
      navigator.vibrate?.(12);
      requestSong(getTrack());
    }
  };
  row.addEventListener("pointerup", finish);
  row.addEventListener("pointercancel", reset);
}

// ── Menú ⋯ ──────────────────────────────────────────────────────────────
function openSheet(track) {
  if (!track) return;
  sheetTrack = track;
  $("sheet-art").src = track.artworkUrl || `https://i.ytimg.com/vi/${track.videoId}/hqdefault.jpg`;
  $("sheet-title").textContent = track.title;
  $("sheet-artist").textContent = track.artist || "";
  const done = isDone(track.videoId);
  $("sheet-add").disabled = done;
  $("sheet-add").lastChild.textContent = done ? " Ya está en la cola" : " Añadir a la cola";
  $("sheet-scrim").hidden = false;
  $("sheet").hidden = false;
  $("sheet-add").focus();
}
function closeSheet() {
  sheetTrack = null;
  $("sheet-scrim").hidden = true;
  $("sheet").hidden = true;
}
$("sheet-add").addEventListener("click", () => { const t = sheetTrack; closeSheet(); requestSong(t); });
$("sheet-close").addEventListener("click", closeSheet);
$("sheet-scrim").addEventListener("click", closeSheet);
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && !$("sheet").hidden) closeSheet(); });

async function requestSong(t) {
  if (!t) return;
  if (isDone(t.videoId)) return toast("Esa ya está en la cola");
  try {
    await push(ref(db, `${base}/jam/requests`), clean({
      code,
      videoId: t.videoId,
      title: t.title.slice(0, 200),
      artist: (t.artist || "").slice(0, 200),
      artworkUrl: t.artworkUrl,
      durationMs: t.durationMs,
      by: myName(),
      at: serverTimestamp(),
    }));
    myRequests.set(t.videoId, { title: t.title, artist: t.artist, artworkUrl: t.artworkUrl, at: Date.now() });
    toast("Añadida a la cola");
    refreshResultMarks();
    render();
  } catch (e) {
    toast(/permission/i.test(e.code || e.message) ? "La Jam ya no acepta canciones." : "No se pudo añadir, prueba otra vez.");
  }
}

function refreshResultMarks() {
  if ($("results-view").hidden || !results.length) return;
  $("results").querySelectorAll(".track-wrap").forEach((li) => {
    const t = results[Number(li.dataset.i)];
    const sub = li.querySelector(".t-sub");
    if (t && isDone(t.videoId) && !sub.querySelector(".t-added")) sub.insertAdjacentHTML("afterbegin", `<span class="t-added">En la cola</span> · `);
  });
}

/**
 * Enlace de Spotify pegado en el buscador: se intenta leer el título para que
 * elijas la versión. Si el navegador no puede, el enlace se envía tal cual y
 * lo busca el teléfono del anfitrión.
 */
async function addSpotifyLink(link) {
  try {
    const res = await fetch(`https://open.spotify.com/oembed?url=${encodeURIComponent(link)}`);
    if (!res.ok) throw new Error(String(res.status));
    const data = await res.json();
    if (data?.title && youtubeApiKey) return search(data.title, `Del enlace de Spotify: «${data.title}»`);
    throw new Error("sin título");
  } catch {
    try {
      await push(ref(db, `${base}/jam/requests`), { code, spotifyUrl: link.slice(0, 300), by: myName(), at: serverTimestamp() });
      $("search-input").value = "";
      showResults(false);
      toast("Enlace enviado: aparecerá en la cola en unos segundos.");
    } catch (e) {
      toast(/permission/i.test(e.code || e.message) ? "La Jam ya no acepta canciones." : "No se pudo enviar el enlace.");
    }
  }
}

// ── Pintado ─────────────────────────────────────────────────────────────
const MORE_ICON = '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg>';
const QUEUE_ICON = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M4 6h12"/><path d="M4 12h12"/><path d="M4 18h7"/><path d="M18 14v6"/><path d="M15 17h6"/></svg>';
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmt = (ms) => { const s = Math.round(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`; };
const clean = (o) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== null && v !== ""));
const art = (t) => t?.artworkUrl || (t?.videoId ? `https://i.ytimg.com/vi/${t.videoId}/hqdefault.jpg` : "");
const myName = () => storage.get("jam-guest-name") || "Invitado";
const byOf = (t) => t?.addedBy || (t?.videoId && added[t.videoId]?.by) || "";
const isQueued = (videoId) => (playback?.queue || []).some((t) => t.videoId === videoId);

let lastAccentKey = "";
function render() {
  if (!joined) return;
  const track = playback?.track;
  $("now-title").textContent = track?.title || "Nada sonando todavía";
  const by = byOf(track);
  $("now-artist").textContent = track ? `${track.artist || ""}${by ? ` · añadida por ${by}` : ""}` : "";
  $("now-bars").hidden = !(track && playback.isPlaying);
  const url = art(track);
  $("now-cover").hidden = !url;
  $("now-cover-empty").hidden = !!url;
  if (url && $("now-cover").getAttribute("src") !== url) $("now-cover").src = url;
  if (url && $("backdrop-img").getAttribute("src") !== url) $("backdrop-img").src = url;
  const key = track?.videoId || track?.title || "";
  if (key !== lastAccentKey) {
    lastAccentKey = key;
    accentFor(url, key).then((tone) => { if (lastAccentKey === key) applyAccent(tone); });
  }

  // Las que he pedido y aún no han entrado en la cola.
  const queue = (playback?.queue || []).slice(1);
  for (const [videoId, req] of myRequests) {
    if (isQueued(videoId) || track?.videoId === videoId || Date.now() - req.at > 120_000) myRequests.delete(videoId);
  }
  const pending = [...myRequests.values()].map((r) => `
    <li class="track pending">${r.artworkUrl ? `<img src="${esc(r.artworkUrl)}" alt="">` : `<div class="ph"></div>`}<div class="t-text"><div class="t-title">${esc(r.title)}</div><div class="t-sub">Entrando en la cola…</div></div></li>`).join("");
  $("queue").innerHTML = queue.map((t) => {
    const who = byOf(t);
    return `<li class="track">
      ${art(t) ? `<img src="${esc(art(t))}" alt="" loading="lazy">` : `<div class="ph"></div>`}
      <div class="t-text"><div class="t-title">${esc(t.title)}</div><div class="t-sub">${who ? `<span class="t-by">${esc(who)}</span> · ` : ""}${esc(t.artist)}</div></div>
    </li>`;
  }).join("") + pending;
  $("queue-empty").hidden = queue.length > 0 || myRequests.size > 0;
}

function renderGuests() {
  const now = Date.now();
  const names = [...new Set(Object.values(guests).filter((g) => g?.lastSeen && now - g.lastSeen < ONLINE_MS).map((g) => g.name))];
  $("guest-count").textContent = names.length ? `Escuchando: ${names.join(", ")}` : "";
}

// Portada que no carga → hueco neutro en lugar del icono roto.
document.addEventListener("error", (e) => {
  const img = e.target;
  if (img.tagName !== "IMG") return;
  if (img.id === "now-cover") { img.hidden = true; $("now-cover-empty").hidden = false; return; }
  if (img.id === "backdrop-img") return;
  if (img.id === "sheet-art") { img.removeAttribute("src"); return; }
  const ph = document.createElement("div");
  ph.className = "ph";
  img.replaceWith(ph);
}, true);

let toastTimer;
function toast(text) {
  $("toast").textContent = text;
  $("toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { $("toast").hidden = true; }, 2600);
}
