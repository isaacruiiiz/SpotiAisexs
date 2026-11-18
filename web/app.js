// SpotiAisexs web · interfaz. El estado y la reproducción viven en core.js.
import * as core from "./core.js";
import { S, ui, catalog, catalogReady } from "./core.js";

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const songs = (n) => `${n} ${n === 1 ? "canción" : "canciones"}`;
const fmt = (ms) => {
  const s = Math.max(0, Math.floor((ms || 0) / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
const enc = encodeURIComponent;
const svg = (body, size = 20, extra = 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"') =>
  `<svg viewBox="0 0 24 24" width="${size}" height="${size}" ${extra} aria-hidden="true">${body}</svg>`;
const I = {
  play: svg('<path d="M7.5 4.5v15l12-7.5z"/>', 26, 'fill="currentColor"'),
  shuffle: svg('<path d="M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6c.7-1.1 2-1.7 3.3-1.7H22"/><path d="m18 2 4 4-4 4"/><path d="M2 6h1.9c1.5 0 2.9.9 3.6 2.2"/><path d="M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8"/><path d="m18 14 4 4-4 4"/>', 18),
  radio: svg('<circle cx="12" cy="12" r="2.5"/><path d="M7 7a7 7 0 0 0 0 10"/><path d="M17 7a7 7 0 0 1 0 10"/>', 20),
  save: svg('<path d="M6 4h12v16l-6-4.5L6 20z"/>', 18),
  heart: svg('<path d="M12 20.5s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.7c0 5.6-7.5 10.2-7.5 10.2z"/>', 20),
  more: svg('<circle cx="5" cy="12" r="1.7"/><circle cx="12" cy="12" r="1.7"/><circle cx="19" cy="12" r="1.7"/>', 20, 'fill="currentColor"'),
  queue: svg('<path d="M4 6h12"/><path d="M4 12h12"/><path d="M4 18h7"/><path d="M18 14v6"/><path d="M15 17h6"/>', 20),
  list: svg('<path d="M5 4v16"/><path d="M10 4v16"/><path d="m15 4.5 4.5 15"/>', 20),
  plus: svg('<path d="M12 5v14"/><path d="M5 12h14"/>', 20),
  back: svg('<path d="m15 5-7 7 7 7"/>', 22),
  person: svg('<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>', 20),
  album: svg('<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/>', 20),
  chevron: svg('<path d="m9 5 7 7-7 7"/>', 20),
  eq: svg('<rect x="4" y="10" width="3.4" height="10" rx="1"/><rect x="10.3" y="4" width="3.4" height="16" rx="1"/><rect x="16.6" y="13" width="3.4" height="7" rx="1"/>', 14, 'fill="currentColor" class="eq"'),
  phone: svg('<rect x="7" y="2" width="10" height="20" rx="2"/><path d="M11 18h2"/>', 22),
  screen: svg('<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8"/>', 22),
};

function img(url, cls = "thumb") {
  if (!url) return `<div class="${cls.replace("thumb", "thumb-ph")}"></div>`;
  return `<img class="${cls}" src="${esc(url)}" alt="" loading="lazy" data-fallback>`;
}
document.addEventListener("error", (e) => {
  const el = e.target;
  if (el.tagName === "IMG" && el.hasAttribute("data-fallback")) { el.removeAttribute("data-fallback"); el.src = "logo.svg"; el.style.objectFit = "contain"; el.style.padding = "22%"; }
}, true);

// ── Avisos ──────────────────────────────────────────────────────────────
let toastTimer = null;
ui.toast = (message) => {
  const el = $("toast");
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 4200);
};

// ── Listas de canciones pintadas (para saber qué se ha pulsado) ─────────
const lists = new Map();
function reg(id, tracks) { lists.set(id, tracks); return id; }

function row(t, i, listId, { num = false, sub = null, time = true, current = false } = {}) {
  const liked = core.isLiked(t);
  return `<li class="row${current ? " current" : ""}" data-list="${listId}" data-i="${i}" data-vid="${esc(t.videoId || "")}">
    ${num ? `<span class="row-num">${i + 1}</span>` : ""}
    ${img(core.artFor(t))}
    <div class="row-text">
      <div class="row-title">${I.eq}<span>${esc(t.title)}</span></div>
      <div class="row-sub">${t.addedBy ? `<span class="by">${esc(t.addedBy)}</span>` : ""}${esc(sub ?? t.artist ?? "")}</div>
    </div>
    ${t.mine ? '<span class="tag">Tuya</span>' : ""}
    ${time && t.durationMs ? `<span class="row-time hide-narrow">${fmt(t.durationMs)}</span>` : ""}
    <button class="icon-btn like hide-narrow" data-act="like" aria-pressed="${liked}" aria-label="${liked ? "Quitar de Me gusta" : "Añadir a Me gusta"}">${I.heart}</button>
    <button class="icon-btn" data-act="more" aria-label="Más opciones de ${esc(t.title)}">${I.more}</button>
  </li>`;
}
const rows = (tracks, listId, opts) => `<ol class="rows">${tracks.map((t, i) => row(t, i, reg(listId, tracks), opts)).join("")}</ol>`;

function tile({ href, title, sub = "", art = "", round = false, strip = "" }) {
  return `<a class="tile${round ? " round" : ""}" href="${esc(href)}">
    <div class="tile-art">${art ? `<img src="${esc(art)}" alt="" loading="lazy" data-fallback>` : '<div class="tile-ph"></div>'}${strip ? `<span class="strip">${esc(strip)}</span>` : ""}</div>
    <div class="tile-title">${esc(title)}</div>${sub ? `<div class="tile-sub">${esc(sub)}</div>` : ""}
  </a>`;
}
const section = (title, body, right = "") => (body ? `<section class="section"><div class="section-head"><h2>${esc(title)}</h2>${right}</div>${body}</section>` : "");

// ── Rutas ───────────────────────────────────────────────────────────────
let route = { name: "home", args: [] };
const pageCache = new Map();
let search = { query: "", loading: false, error: "", results: null };

function parseRoute() {
  const parts = location.hash.replace(/^#\/?/, "").split("/").map(decodeURIComponent);
  const names = { "": "home", buscar: "search", biblioteca: "library", playlist: "playlist", artista: "artist", album: "album", lista: "ytlist", estoes: "thisis" };
  return { name: names[parts[0]] || "home", args: parts.slice(1) };
}

function navigate() {
  route = parseRoute();
  const top = route.name === "playlist" ? "library" : ["artist", "album", "ytlist", "thisis"].includes(route.name) ? "search" : route.name;
  document.querySelectorAll("[data-nav]").forEach((a) => a.classList.toggle("active", a.dataset.nav === top));
  $("app").classList.remove("now-open");
  renderPage();
  $("main").scrollTop = 0;
  if (route.name === "search") {
    const q = route.args[0] || "";
    if (q && q !== search.query) runSearch(q);
    setTimeout(() => $("search-input")?.focus(), 0);
  }
}
window.addEventListener("hashchange", navigate);

function renderPage() {
  const pages = { home: pageHome, search: pageSearch, library: pageLibrary, playlist: pagePlaylist, artist: pageArtist, album: pageAlbum, ytlist: pageYtList, thisis: pageThisIs };
  $("main").innerHTML = (pages[route.name] || pageHome)();
  markPlaying();
  renderSideList();
}

/** Páginas que piden datos al catálogo: se pinta "Cargando" y luego el resultado. */
function remote(key, loader, render) {
  const hit = pageCache.get(key);
  if (hit?.data) return render(hit.data);
  if (hit?.error) return `<div class="empty">${backButton()}<p>${esc(hit.error)}</p></div>`;
  if (!hit) {
    pageCache.set(key, { loading: true });
    loader().then((data) => pageCache.set(key, { data }), (e) => pageCache.set(key, { error: e.message || "No se ha podido cargar" }))
      .then(() => { if (pageKey() === key) renderPage(); });
  }
  return '<div class="loading">Cargando…</div>';
}
const pageKey = () => `${route.name}:${route.args.join("/")}`;
const backButton = () => `<button class="icon-btn hero-back" data-act="back" aria-label="Volver">${I.back}</button>`;
const needsCatalog = (what) => `<div class="empty">${backButton()}<p>${what} necesita el catálogo completo. Se activa en dos minutos: mira «Catálogo completo en la web» en docs/WEB_SYNC.md.</p></div>`;

function detail({ image, kicker = "", title, meta, actions, body }) {
  return `<div class="hero">${image ? `<img src="${esc(image)}" alt="" data-fallback>` : ""}${backButton()}
      <div class="hero-text">${kicker ? `<span class="hero-kicker">${esc(kicker)}</span>` : ""}<h1>${esc(title)}</h1></div></div>
    <div class="detail"><p class="meta">${esc(meta)}</p><div class="actions">${actions}</div>${body}</div>`;
}
const playRound = (listId, label) => `<button class="play-round" data-act="play-list" data-list="${listId}" aria-label="Reproducir ${esc(label)}">${I.play}</button>`;
const shuffleBtn = (listId) => `<button class="btn-line" data-act="shuffle-list" data-list="${listId}">${I.shuffle}Aleatorio</button>`;
const saveBtn = `<button class="btn-line" data-act="save-list">${I.save}Guardar</button>`;

// ── Páginas ─────────────────────────────────────────────────────────────
function pageHome() {
  const pls = core.sortedPlaylists();
  const p = S.playback;
  const resume = p?.track && !core.isActiveHere() && !core.isRemote() ? p : null;
  const artists = core.topArtists(10);
  const d = core.radioInfo()?.distance ?? S.distance;
  const stations = core.STATIONS.filter((s) => s.id !== "mix").map((s) =>
    `<button class="station" data-act="station" data-id="${s.id}"><strong>${esc(s.name)}</strong><span>${esc(s.hint)}</span></button>`).join("");
  return `<div class="page">
    <div class="mix">
      <div><h2>Tu mezcla</h2><p>Lo que ya te gusta y algo nuevo. Mueve el dial para quedarte cerca de lo conocido o alejarte.</p></div>
      <button class="play-round" data-act="station" data-id="mix" aria-label="Reproducir Tu mezcla">${I.play}</button>
      <div class="dial-box"><div class="dial-head"><span class="cond">¿Cuánto me alejo?</span><span class="dial-zone" data-zone>${core.distanceLabel(d)}</span></div>
        <input class="dial" data-dial type="range" min="0" max="100" step="1" value="${d}" aria-label="¿Cuánto me alejo de lo que conozco?">
        <div class="dial-ends"><span>Pegada</span><span>Todo nuevo</span></div></div>
    </div>
    ${resume ? section("Sigue donde lo dejaste", `<div class="top-result" data-act="resume">${img(core.artFor(resume.track), "")}
        <div class="row-text"><h3>${esc(resume.track.title)}</h3><p class="muted">${esc(resume.track.artist || "")}, en ${esc(resume.activeDeviceName || "otro dispositivo")}</p></div>
        <span class="btn-line accent">Seguir aquí</span></div>`) : ""}
    ${section("Emisoras", `<div class="stations">${stations}</div>`)}
    ${section("Hace poco", S.recents.length ? `<div class="shelf">${S.recents.slice(0, 14).map((t, i) =>
      `<button class="tile" data-act="play-recent" data-i="${i}"><div class="tile-art"><img src="${esc(core.artFor(t))}" alt="" loading="lazy" data-fallback></div><div class="tile-title">${esc(t.title)}</div><div class="tile-sub">${esc(t.artist || "")}</div></button>`).join("")}</div>` : "")}
    ${section("Tus playlists", pls.length ? `<div class="shelf">${pls.map((pl) => tile({ href: `#/playlist/${enc(pl.id)}`, title: pl.title, sub: songs(pl.tracks.length), art: pl.artworkUrl || core.artFor(pl.tracks[0]) })).join("")}</div>` : "")}
    ${catalogReady && artists.length ? section("Esto es", `<div class="shelf">${artists.slice(0, 8).map((a) => tile({ href: `#/estoes/${enc(a.name)}`, title: `Esto es ${a.name}`, sub: "Sus éxitos y lo tuyo", art: a.artworkUrl, strip: "Esto es" })).join("")}</div>`) : ""}
    ${catalogReady && artists.length ? section("Artistas para ti", `<div class="shelf">${artists.map((a) => tile({ href: `#/artista/n/${enc(a.name)}`, title: a.name, art: a.artworkUrl, round: true })).join("")}</div>`) : ""}
    ${!pls.length ? '<p class="notice"><strong>Tu biblioteca está vacía.</strong> Busca cualquier canción y dale a Me gusta o crea una playlist desde aquí. Si enlazas el teléfono (Ajustes, Web y Jam), tus playlists de la app aparecen solas.</p>' : ""}
    ${!catalogReady ? '<p class="notice"><strong>Falta el catálogo completo.</strong> Sin él no hay páginas de artista ni álbum y las búsquedas tienen un límite diario. Se activa en dos minutos: mira «Catálogo completo en la web» en docs/WEB_SYNC.md.</p>' : ""}
  </div>`;
}

function pageLibrary() {
  const pls = core.sortedPlaylists();
  return `<div class="page"><h1>Biblioteca</h1>
    <div class="actions"><button class="btn-line" data-act="new-playlist">${I.plus}Nueva playlist</button></div>
    ${pls.length ? `<section class="section"><div class="grid">${pls.map((pl) => tile({ href: `#/playlist/${enc(pl.id)}`, title: pl.title, sub: songs(pl.tracks.length), art: pl.artworkUrl || core.artFor(pl.tracks[0]) })).join("")}</div></section>`
      : '<p class="notice">Aún no hay playlists. Crea una aquí o enlaza el teléfono para traer las de la app.</p>'}</div>`;
}

function pagePlaylist() {
  const pl = S.library[route.args[0]];
  if (!pl) return `<div class="empty">${backButton()}<p>Esta playlist no está en tu biblioteca.</p></div>`;
  const tracks = pl.tracks || [];
  const listId = reg("page", tracks);
  return detail({
    image: pl.artworkUrl || core.artFor(tracks[0]),
    title: pl.title,
    meta: `Playlist, ${songs(tracks.length)}`,
    actions: `${shuffleBtn(listId)}<span class="spacer"></span>${playRound(listId, pl.title)}`,
    body: tracks.length ? `<div class="section">${rows(tracks, "page")}</div>` : '<p class="notice">Esta playlist todavía no tiene canciones. Añádelas desde el menú de cualquier canción.</p>',
  });
}

function pageArtist() {
  if (!catalogReady) return needsCatalog("La página de artista");
  const byName = route.args[0] === "n";
  const loader = async () => {
    const id = byName ? (await catalog.searchArtists(route.args[1], 1))[0]?.browseId : route.args[0];
    if (!id) throw new Error(`No se ha encontrado a ${route.args[1] || "este artista"}`);
    return catalog.artist(id);
  };
  return remote(pageKey(), loader, (a) => {
    const listId = reg("page", a.topSongs);
    const all = !!pageCache.get(pageKey()).all;
    const shown = a.topSongs.slice(0, all ? 60 : 5);
    const releases = (items, kind) => `<div class="shelf">${items.map((x) => tile({ href: `#/album/${enc(x.browseId)}`, title: x.title, sub: [/single/i.test(x.type) ? "Sencillo" : /ep/i.test(x.type) ? "EP" : kind, x.year].filter(Boolean).join(", "), art: x.artworkUrl })).join("")}</div>`;
    return detail({
      image: a.artworkUrl,
      title: a.name,
      meta: a.subscribers ? `${a.subscribers} de suscriptores` : "Artista",
      actions: `<button class="btn-line accent" data-act="artist-radio">${I.radio}Emisora</button>${a.topSongs.length ? shuffleBtn(listId) : ""}<span class="spacer"></span>${a.topSongs.length ? playRound(listId, a.name) : ""}`,
      body: `<a class="thisis" href="#/estoes/${enc(a.name)}/${enc(a.browseId)}"><div class="tile-art">${img(a.artworkUrl, "")}<span class="strip"></span></div>
          <div class="row-text"><div class="row-title"><span>Esto es ${esc(a.name)}</span></div><div class="row-sub">Sus éxitos y lo que más le escuchas tú</div></div>${I.chevron}</a>
        ${a.topSongs.length ? section("Populares", `<ol class="rows">${shown.map((t, i) => row(t, i, "page", { num: true, sub: t.album || t.artist })).join("")}</ol>
          ${a.topSongs.length > 5 && !all ? `<div class="actions"><button class="btn-line" data-act="show-all">Ver las ${a.topSongs.length} canciones</button></div>` : ""}`) : ""}
        ${a.albums.length ? section("Álbumes", releases(a.albums, "Álbum")) : ""}
        ${a.singles.length ? section("Sencillos y EP", releases(a.singles, "Sencillo")) : ""}
        ${a.bio ? section("Acerca de", `<p class="bio">${esc(a.bio.slice(0, 700))}${a.bio.length > 700 ? "…" : ""}</p>`) : ""}
        ${a.similar.length ? section("Artistas parecidos", `<div class="shelf">${a.similar.map((x) => tile({ href: `#/artista/${enc(x.browseId)}`, title: x.title, art: x.artworkUrl, round: true })).join("")}</div>`) : ""}`,
    });
  });
}

function pageAlbum() {
  if (!catalogReady) return needsCatalog("La página de álbum");
  return remote(pageKey(), () => catalog.album(route.args[0]), (al) => {
    const listId = reg("page", al.tracks);
    return detail({
      image: al.artworkUrl,
      title: al.title,
      meta: [al.artist, al.year, songs(al.tracks.length)].filter(Boolean).join(", "),
      actions: `${saveBtn}${shuffleBtn(listId)}${al.artistId ? `<a class="btn-line" href="#/artista/${enc(al.artistId)}">${I.person}Artista</a>` : ""}<span class="spacer"></span>${playRound(listId, al.title)}`,
      body: `<div class="section">${rows(al.tracks, "page", { num: true })}</div>`,
    });
  });
}

function pageYtList() {
  if (!catalogReady) return needsCatalog("Esta playlist");
  return remote(pageKey(), () => catalog.playlist(route.args[0]), (pl) => {
    const listId = reg("page", pl.tracks);
    return detail({
      image: pl.artworkUrl, title: pl.title, meta: [pl.author, songs(pl.tracks.length)].filter(Boolean).join(", "),
      actions: `${saveBtn}${shuffleBtn(listId)}<span class="spacer"></span>${playRound(listId, pl.title)}`,
      body: `<div class="section">${rows(pl.tracks, "page")}</div>`,
    });
  });
}

function pageThisIs() {
  if (!catalogReady) return needsCatalog("«Esto es»");
  return remote(pageKey(), () => core.buildThisIs(route.args[0], route.args[1] || ""), (x) => {
    const listId = reg("page", x.tracks);
    return detail({
      image: x.artist.artworkUrl, kicker: "Esto es", title: x.artist.name,
      meta: `${x.hasMine ? "Sus éxitos y lo que tienes tú de este artista" : "Sus canciones más escuchadas"}. ${songs(x.tracks.length)}.`,
      actions: `${saveBtn}${shuffleBtn(listId)}<span class="spacer"></span>${playRound(listId, `Esto es ${x.artist.name}`)}`,
      body: `${x.hasMine ? '<div class="legend"><span class="tag">Tuya</span><span>de las que tienes en tu biblioteca</span></div>' : '<div class="section"></div>'}${rows(x.tracks, "page")}`,
    });
  });
}

function pageSearch() {
  const r = search.results;
  let body = "";
  if (search.loading) body = '<div class="loading">Buscando…</div>';
  else if (search.error) body = `<p class="notice">${esc(search.error)}</p>`;
  else if (r) {
    const top = r.songs[0];
    body = `
      ${top ? section("Resultado destacado", `<div class="top-result" data-act="play-top">${img(core.artFor(top), "")}
          <div class="row-text"><h3>${esc(top.title)}</h3><p class="muted">Canción, ${esc(top.artist || "")}</p></div>
          <button class="icon-btn" data-act="top-more" aria-label="Más opciones de ${esc(top.title)}">${I.more}</button><span class="play-round">${I.play}</span></div>`) : ""}
      ${r.songs.length ? section("Canciones", rows(r.songs, "search")) : ""}
      ${r.artists?.length ? section("Artistas", `<div class="shelf">${r.artists.map((a) => tile({ href: `#/artista/${enc(a.browseId)}`, title: a.name, art: a.artworkUrl, round: true })).join("")}</div>`) : ""}
      ${r.albums?.length ? section("Álbumes", `<div class="shelf">${r.albums.map((a) => tile({ href: `#/album/${enc(a.browseId)}`, title: a.name, sub: [a.artist, a.year].filter(Boolean).join(", "), art: a.artworkUrl })).join("")}</div>`) : ""}
      ${r.local.length ? section("En tu biblioteca", rows(r.local, "search-local")) : ""}
      ${!r.songs.length && !r.local.length ? `<p class="notice">No hay resultados para «${esc(search.query)}».${core.canSearchOnline ? "" : " Sin catálogo ni clave de YouTube solo se busca en tu biblioteca."}</p>` : ""}`;
  }
  return `<div class="page"><h1>Buscar</h1>
    <form id="search-form" class="search-box" role="search">${svg('<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4 4"/>', 22)}
      <input id="search-input" type="search" placeholder="Canciones, artistas o álbumes" autocomplete="off" aria-label="Buscar" value="${esc(search.query)}"></form>
    ${body}</div>`;
}

async function runSearch(query) {
  search = { query, loading: true, error: "", results: null };
  if (route.name === "search") renderPage();
  try {
    const results = await core.searchAll(query);
    if (search.query !== query) return;
    search = { query, loading: false, error: "", results };
  } catch (e) {
    if (search.query !== query) return;
    search = { query, loading: false, error: e.message || "La búsqueda ha fallado", results: null };
  }
  if (route.name === "search") renderPage();
}

function renderSideList() {
  const items = core.sortedPlaylists();
  $("side-list").innerHTML = items.length
    ? items.map((pl) => `<li><a href="#/playlist/${enc(pl.id)}"${route.name === "playlist" && route.args[0] === pl.id ? ' class="active"' : ""}>
        ${img(pl.artworkUrl || core.artFor(pl.tracks[0]))}<div class="side-text"><div class="side-title">${esc(pl.title)}</div><div class="side-sub">${songs(pl.tracks.length)}</div></div></a></li>`).join("")
    : '<li class="muted small" style="padding:8px 10px">Tus playlists aparecerán aquí.</li>';
}

// ── Lo que suena, la barra y la cola ────────────────────────────────────
function setCover(imgId, emptyId, url) {
  const el = $(imgId);
  if (url) { if (el.getAttribute("src") !== url) el.src = url; el.hidden = false; $(emptyId).hidden = true; }
  else { el.hidden = true; $(emptyId).hidden = false; }
}

ui.render = () => {
  if (!S.user) return;
  const track = core.currentTrack();
  $("now-name").textContent = track?.title || "Nada sonando";
  $("now-artist").textContent = track?.artist || "Busca algo o pon una emisora";
  $("bar-title").textContent = track?.title || "Nada sonando";
  $("bar-artist").textContent = track?.artist || "";
  document.title = track ? `${track.title}, ${track.artist || ""}` : "SpotiAisexs";
  setCover("now-cover", "now-empty", core.artFor(track));
  setCover("bar-cover", "bar-cover-empty", core.artFor(track));
  const liked = core.isLiked(track);
  ["now-like", "bar-like"].forEach((id) => {
    $(id).setAttribute("aria-pressed", String(liked));
    $(id).setAttribute("aria-label", liked ? "Quitar de Me gusta" : "Añadir a Me gusta");
    $(id).disabled = !track?.videoId;
  });
  const playing = core.isPlayingNow();
  $("ic-play").toggleAttribute("hidden", playing);
  $("ic-pause").toggleAttribute("hidden", !playing);
  $("play").setAttribute("aria-label", playing ? "Pausar" : "Reproducir");
  $("shuffle").setAttribute("aria-pressed", String(S.shuffle));

  const isRemote = core.isRemote();
  $("device-label").textContent = isRemote ? `Suena en ${S.playback.activeDeviceName || "otro dispositivo"}` : "Este navegador";
  $("device-btn").classList.toggle("on", isRemote);

  const info = core.radioInfo();
  $("dial-box").hidden = !info;
  if (info) {
    $("dial-station").textContent = info.loading ? "Buscando más canciones…" : info.label || "Radio";
    if (document.activeElement !== $("dial")) $("dial").value = info.distance;
    $("dial-zone").textContent = core.distanceLabel(Number($("dial").value));
  }
  renderQueue();
  renderDevices();
  markPlaying();
  renderProgress();
};

function renderQueue() {
  const queue = core.visibleQueue();
  $("queue-empty").hidden = queue.length > 0;
  $("queue-list").innerHTML = queue.map((t, i) => row(t, i, reg("queue", queue), { time: false, current: i === 0 })).join("");
}

function markPlaying() {
  const id = core.currentTrack()?.videoId;
  document.querySelectorAll("#main .row[data-vid]").forEach((li) => li.classList.toggle("playing", !!id && li.dataset.vid === id));
}

let dragFraction = null;
function renderProgress() {
  const duration = core.currentDuration();
  const position = dragFraction != null ? dragFraction * duration : core.currentPosition();
  const fraction = duration > 0 ? Math.min(1, position / duration) : 0;
  $("ribbon-fill").style.width = `${fraction * 100}%`;
  $("t-pos").textContent = fmt(position);
  $("t-left").textContent = duration ? `-${fmt(duration - position)}` : "";
  $("t-left").style.visibility = fraction > 0.86 ? "hidden" : "visible";
  $("ribbon").setAttribute("aria-valuenow", String(Math.round(fraction * 100)));
}
setInterval(() => { if (S.user) { renderProgress(); core.tick(); } }, 500);

function seekFraction(fraction) {
  const duration = core.currentDuration();
  if (!duration) return;
  const ms = Math.round(Math.max(0, Math.min(1, fraction)) * duration);
  if (core.isActiveHere()) core.seekTo(ms);
  else if (core.isRemote()) core.sendCommand("seek", { positionMs: ms });
}
{
  const ribbon = $("ribbon");
  const at = (e) => { const r = ribbon.getBoundingClientRect(); return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)); };
  ribbon.addEventListener("pointerdown", (e) => { ribbon.setPointerCapture(e.pointerId); dragFraction = at(e); ribbon.classList.add("dragging"); renderProgress(); });
  ribbon.addEventListener("pointermove", (e) => { if (dragFraction != null) { dragFraction = at(e); renderProgress(); } });
  const end = (commit) => () => { if (dragFraction == null) return; const f = dragFraction; dragFraction = null; ribbon.classList.remove("dragging"); if (commit) seekFraction(f); renderProgress(); };
  ribbon.addEventListener("pointerup", end(true));
  ribbon.addEventListener("pointercancel", end(false));
  ribbon.addEventListener("keydown", (e) => {
    const duration = core.currentDuration();
    if (!duration || !["ArrowLeft", "ArrowRight"].includes(e.key)) return;
    e.preventDefault();
    seekFraction((core.currentPosition() + (e.key === "ArrowRight" ? 5000 : -5000)) / duration);
  });
}

ui.video = (visible) => {
  $("yt-holder").hidden = !visible;
  $("app").classList.toggle("video-on", visible);
};
ui.needsTap = (show) => { $("tap-to-play").hidden = !show; };
$("tap-to-play").addEventListener("click", () => { core.resumePlayer(); $("tap-to-play").hidden = true; });

// ── Dispositivos y Jam ──────────────────────────────────────────────────
function renderDevices() {
  const menu = $("device-menu");
  if (menu.hidden) return;
  const others = Object.entries(S.devices).filter(([id]) => id !== core.deviceId && core.isDeviceOnline(id));
  const entries = [[core.deviceId, { name: core.deviceName, type: "web" }], ...others];
  const activeId = core.isActiveHere() ? core.deviceId : core.isRemote() ? S.playback.activeDevice : null;
  menu.innerHTML = "<h3>¿Dónde suena?</h3>" + entries.map(([id, d]) => `
    <button class="device${id === activeId ? " active" : ""}" data-device="${esc(id)}">${d.type === "phone" ? I.phone : I.screen}
      <span>${esc(id === core.deviceId ? "Este navegador" : d.name)}<small>${id === activeId ? "Sonando aquí" : "Pulsa para escuchar aquí"}</small></span></button>`).join("")
    + (others.length ? "" : '<p class="muted small">Abre la app en el teléfono, con la web enlazada en Ajustes, para verlo aquí.</p>');
}
$("device-menu").addEventListener("click", (e) => {
  const b = e.target.closest("[data-device]");
  if (!b) return;
  $("device-menu").hidden = true;
  core.transferTo(b.dataset.device);
});

ui.jam = () => { $("jam-btn").classList.toggle("on", !!S.jam?.open); renderJam(); };
function renderJam() {
  const panel = $("jam-panel");
  if (panel.hidden) return;
  if (!S.jam?.open) {
    const name = core.store.get("spotiaisexs-jam-host", "") || (S.user?.email || "").split("@")[0];
    panel.innerHTML = `<h3>Jam</h3><p class="muted small">Tus amigos añaden canciones a tu cola desde un enlace, sin cuenta y aunque usen Spotify.</p>
      <label class="small muted">Tu nombre<input id="jam-host" class="field" maxlength="30" value="${esc(name)}" style="width:100%;margin-top:4px"></label>
      <button id="jam-start" class="btn-fill">Empezar Jam</button>`;
    $("jam-start").addEventListener("click", async () => {
      const host = $("jam-host").value.trim();
      core.store.set("spotiaisexs-jam-host", host);
      $("jam-start").disabled = true;
      try { await core.startJam(host); } catch (e) { ui.toast(`No se pudo empezar la Jam (${e.code || e.message})`); }
    });
    return;
  }
  const link = core.jamLink();
  const now = core.serverNow();
  const guests = [...new Set(Object.values(S.jam.guests || {}).filter((g) => g?.lastSeen && now - g.lastSeen < 90_000).map((g) => g.name))];
  panel.innerHTML = `<h3>Jam en directo</h3><div class="jam-qr" id="jam-qr" aria-label="Código QR para unirse"></div>
    <p class="muted small">Que lo escaneen con la cámara, o mándales el enlace.</p>
    <div class="jam-link"><input id="jam-link" class="field" readonly value="${esc(link)}"><button id="jam-copy" class="btn-line">Copiar</button></div>
    <p class="small">${guests.length ? `En la Jam: ${guests.map(esc).join(", ")}` : "Aún no se ha unido nadie"}</p>
    <button id="jam-end" class="btn-line">Terminar Jam</button>`;
  if (window.qrcode) { const qr = window.qrcode(0, "M"); qr.addData(link); qr.make(); $("jam-qr").innerHTML = qr.createSvgTag({ cellSize: 6, margin: 2, scalable: true }); }
  $("jam-copy").addEventListener("click", async () => { try { await navigator.clipboard.writeText(link); ui.toast("Enlace copiado"); } catch { $("jam-link").select(); } });
  $("jam-end").addEventListener("click", () => core.endJam());
}
$("jam-btn").addEventListener("click", () => { $("jam-panel").hidden = !$("jam-panel").hidden; $("device-menu").hidden = true; renderJam(); });
$("device-btn").addEventListener("click", () => { $("device-menu").hidden = !$("device-menu").hidden; $("jam-panel").hidden = true; renderDevices(); });
document.addEventListener("click", (e) => {
  if (!e.target.closest(".pop-anchor")) { $("device-menu").hidden = true; $("jam-panel").hidden = true; }
});

// ── Controles ───────────────────────────────────────────────────────────
$("play").addEventListener("click", () => {
  if (core.isActiveHere()) return core.togglePlay();
  if (core.isRemote()) return core.sendCommand("toggle");
  // Nadie reproduce (o quien lo hacía se desconectó): se sigue aquí.
  if (S.playback?.track) return core.playHere();
  core.playStation("mix");
});
$("next").addEventListener("click", () => (core.isActiveHere() ? core.skip(1) : core.isRemote() ? core.sendCommand("next") : null));
$("prev").addEventListener("click", () => (core.isActiveHere() ? core.skip(-1) : core.isRemote() ? core.sendCommand("previous") : null));
$("shuffle").addEventListener("click", () => core.toggleShuffle());
$("volume").value = core.store.get("spotiaisexs-volume", 80);
$("volume").addEventListener("input", (e) => core.setVolume(e.target.value));
["now-like", "bar-like"].forEach((id) => $(id).addEventListener("click", () => { core.toggleLike(core.currentTrack()); ui.render(); }));
$("bar-track").addEventListener("click", () => $("app").classList.toggle("now-open"));
$("now-close").addEventListener("click", () => $("app").classList.remove("now-open"));
$("logout").addEventListener("click", () => core.logout());

// El dial: mientras se mueve cambia el texto; al soltar se aplica.
document.addEventListener("input", (e) => {
  if (!e.target.matches(".dial")) return;
  const zone = e.target.closest(".dial-box")?.querySelector(".dial-zone");
  if (zone) zone.textContent = core.distanceLabel(Number(e.target.value));
});
document.addEventListener("change", (e) => {
  if (e.target.matches(".dial")) core.setDistance(Number(e.target.value));
});

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !$("menu").hidden) return closeMenu();
  if (e.target.closest("input, textarea")) return;
  if (e.code === "Space" && !e.target.closest("button, a")) { e.preventDefault(); $("play").click(); }
  if (e.code === "ArrowRight" && e.shiftKey) $("next").click();
  if (e.code === "ArrowLeft" && e.shiftKey) $("prev").click();
});
if ("mediaSession" in navigator) {
  navigator.mediaSession.setActionHandler("play", () => $("play").click());
  navigator.mediaSession.setActionHandler("pause", () => $("play").click());
  navigator.mediaSession.setActionHandler("nexttrack", () => $("next").click());
  navigator.mediaSession.setActionHandler("previoustrack", () => $("prev").click());
}

// En pantallas estrechas, Jam y dispositivos van dentro del panel de lo que suena.
{
  const narrow = matchMedia("(max-width: 760px)");
  const right = document.querySelector(".bar-right");
  const home = right.parentElement;
  const place = () => (narrow.matches ? $("now").insertBefore(right, $("dial-box")) : home.appendChild(right));
  narrow.addEventListener("change", place);
  place();
}

// ── Pulsaciones en las páginas ──────────────────────────────────────────
document.addEventListener("submit", (e) => {
  if (e.target.id !== "search-form") return;
  e.preventDefault();
  const q = $("search-input").value.trim();
  if (!q) return;
  if (location.hash === `#/buscar/${enc(q)}`) runSearch(q); else location.hash = `#/buscar/${enc(q)}`;
});

function playList(listId, index) {
  const tracks = lists.get(listId);
  if (!tracks?.length) return;
  if (listId === "queue") {
    if (index === 0) return;
    if (core.isActiveHere()) return core.jumpTo(S.index + index);
    if (core.isRemote()) return core.sendCommand("playIndex", { index });
    return core.playTracks(tracks, index, 0, { here: true });
  }
  // Una canción buscada arranca su radio; el resto de listas suenan tal cual.
  if (listId === "search" || listId === "search-local") return core.playTracks([tracks[index]], 0);
  core.playTracks(tracks, index);
}

document.addEventListener("click", (e) => {
  const actEl = e.target.closest("[data-act]");
  const rowEl = e.target.closest(".row[data-list]");
  const act = actEl?.dataset.act;
  const rowTrack = rowEl ? lists.get(rowEl.dataset.list)?.[Number(rowEl.dataset.i)] : null;
  if (act === "more" && rowTrack) return openTrackMenu(rowTrack, actEl);
  if (act === "like" && rowTrack) { core.toggleLike(rowTrack); renderPage(); ui.render(); return; }
  if (rowEl && !act) return playList(rowEl.dataset.list, Number(rowEl.dataset.i));
  if (!act) return;
  const page = lists.get("page") || [];
  const data = pageCache.get(pageKey())?.data;
  switch (act) {
    case "back": history.length > 1 ? history.back() : (location.hash = "#/"); break;
    case "station": core.playStation(actEl.dataset.id); break;
    case "resume": core.playHere(); break;
    case "play-recent": { const t = S.recents[Number(actEl.dataset.i)]; if (t) core.playTracks([t], 0); break; }
    case "play-list": core.playTracks(lists.get(actEl.dataset.list) || [], 0); break;
    case "shuffle-list": core.playTracks(core.shuffled(lists.get(actEl.dataset.list) || []), 0); break;
    case "play-top": if (search.results?.songs[0]) core.playTracks([search.results.songs[0]], 0); break;
    case "top-more": openTrackMenu(search.results?.songs[0], actEl); break;
    case "artist-radio": { const seed = page[Math.floor(Math.random() * Math.min(5, page.length))]; if (seed) core.startRadio(seed, { label: `Emisora de ${data?.name || seed.artist}` }); break; }
    case "show-all": pageCache.get(pageKey()).all = true; renderPage(); break;
    case "save-list": if (data) core.createPlaylist(route.name === "thisis" ? `Esto es ${data.artist.name}` : data.title, data.tracks, route.name === "thisis" ? data.artist.artworkUrl : data.artworkUrl); break;
    case "new-playlist": openNewPlaylist(actEl); break;
  }
});
$("new-playlist").addEventListener("click", (e) => openNewPlaylist(e.currentTarget));
document.addEventListener("contextmenu", (e) => {
  const rowEl = e.target.closest(".row[data-list]");
  const t = rowEl ? lists.get(rowEl.dataset.list)?.[Number(rowEl.dataset.i)] : null;
  if (!t) return;
  e.preventDefault();
  openTrackMenu(t, { getBoundingClientRect: () => ({ left: e.clientX, right: e.clientX, top: e.clientY, bottom: e.clientY }) });
});

// ── Menú de una canción ─────────────────────────────────────────────────
let menuAnchor = null;
function openTrackMenu(track, anchor) {
  if (!track) return;
  menuAnchor = anchor;
  renderMenu(track, "main");
}
function showMenu(html) {
  const menu = $("menu");
  menu.innerHTML = html;
  menu.hidden = false;
  $("menu-scrim").hidden = false;
  const r = menuAnchor?.getBoundingClientRect?.() || { left: innerWidth / 2, right: innerWidth / 2, top: innerHeight / 2, bottom: innerHeight / 2 };
  const w = menu.offsetWidth, h = menu.offsetHeight;
  let top = r.bottom + 6;
  if (top + h > innerHeight - 8) top = Math.max(8, r.top - h - 6);
  menu.style.left = `${Math.min(innerWidth - w - 8, Math.max(8, r.right - w))}px`;
  menu.style.top = `${top}px`;
  menu.querySelector("button, input")?.focus();
}
function closeMenu() { $("menu").hidden = true; $("menu-scrim").hidden = true; menuAnchor = null; }
$("menu-scrim").addEventListener("click", closeMenu);

function renderMenu(track, page) {
  const head = `<div class="menu-head">${img(core.artFor(track))}<div class="row-text"><div class="row-title"><span>${esc(track.title)}</span></div><div class="row-sub">${esc(track.artist || "")}</div></div></div>`;
  const item = (act, icon, text, extra = "") => `<button class="menu-item${extra}" data-menu="${act}" role="menuitem">${icon}${esc(text)}</button>`;
  if (page === "main") {
    const liked = core.isLiked(track);
    showMenu(head + item("queue", I.queue, "Añadir a la cola") + item("radio", I.radio, "Iniciar radio")
      + item("like", I.heart, liked ? "Quitar de Me gusta" : "Añadir a Me gusta", liked ? " on" : "") + item("playlists", I.list, "Añadir a una playlist")
      + (catalogReady && track.artist ? item("artist", I.person, "Ver artista") : "") + (catalogReady && track.albumId ? item("album", I.album, "Ver álbum") : ""));
  } else if (page === "playlists") {
    const pls = core.sortedPlaylists().filter((pl) => pl.mode !== "liked" && pl.mode !== "liked_songs");
    showMenu(head + item("back", I.back, "Volver") + item("new", I.plus, "Nueva playlist")
      + pls.map((pl) => `<button class="menu-item" data-menu="add" data-id="${esc(pl.id)}" role="menuitem">${I.list}${esc(pl.title)}</button>`).join(""));
  } else {
    showMenu(head + `<form id="new-pl-form" class="menu-form"><input id="new-pl-name" class="field" maxlength="100" placeholder="Nombre de la playlist" aria-label="Nombre de la playlist" required>
      <button class="btn-fill" type="submit">Crear y añadir</button></form>`);
    $("new-pl-form").addEventListener("submit", (e) => { e.preventDefault(); core.createPlaylist($("new-pl-name").value, [track]); closeMenu(); });
  }
  $("menu").querySelectorAll("[data-menu]").forEach((b) => b.addEventListener("click", () => {
    const act = b.dataset.menu;
    if (act === "playlists" || act === "new") return renderMenu(track, act);
    if (act === "back") return renderMenu(track, "main");
    if (act === "queue") core.addToQueue(track);
    else if (act === "radio") core.startRadio(track);
    else if (act === "like") { core.toggleLike(track); renderPage(); ui.render(); }
    else if (act === "add") core.addToPlaylist(b.dataset.id, track);
    else if (act === "artist") location.hash = track.artistId ? `#/artista/${enc(track.artistId)}` : `#/artista/n/${enc(String(track.artist).split(/,|&| feat\.? | ft\.? /i)[0].trim())}`;
    else if (act === "album") location.hash = `#/album/${enc(track.albumId)}`;
    closeMenu();
  }));
}

function openNewPlaylist(anchor) {
  menuAnchor = anchor;
  showMenu(`<form id="new-pl-form" class="menu-form"><input id="new-pl-name" class="field" maxlength="100" placeholder="Nombre de la playlist" aria-label="Nombre de la playlist" required>
    <button class="btn-fill" type="submit">Crear playlist</button></form>`);
  $("new-pl-form").addEventListener("submit", (e) => { e.preventDefault(); const id = core.createPlaylist($("new-pl-name").value); closeMenu(); if (id) location.hash = `#/playlist/${enc(id)}`; });
}

// ── Sesión ──────────────────────────────────────────────────────────────
ui.session = (signedIn) => {
  $("login").hidden = signedIn;
  $("app").hidden = !signedIn;
  if (signedIn) { navigate(); ui.render(); }
};
ui.library = () => { renderPage(); ui.render(); };

$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("login-error").textContent = "";
  $("login-error").textContent = await core.login($("login-email").value, $("login-password").value);
});

if (!S.configured) $("setup").hidden = false;
else { core.loadYouTubeApi(); core.boot(); }
