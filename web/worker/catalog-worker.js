// SpotiAisexs · puente de catálogo (Cloudflare Worker)
//
// Un navegador no puede hablar directo con YouTube Music (lo impide CORS), así
// que la web le pide a este Worker lo que necesita y él lo reenvía tal cual.
// Solo deja pasar búsquedas, páginas (artista, álbum, playlist) y "siguientes"
// (la radio). No guarda nada y no toca tu cuenta: son las mismas consultas
// anónimas que hace la app del teléfono.
//
// Cómo ponerlo en marcha: docs/WEB_SYNC.md → "Catálogo completo en la web".

const ALLOWED_ORIGINS = [
  "https://isaacruiiiz.github.io",
  "http://localhost:8080",
  "http://127.0.0.1:8080",
];
const ENDPOINTS = new Set(["search", "browse", "next", "music/get_search_suggestions"]);
const UPSTREAM = "https://music.youtube.com/youtubei/v1/";
const CLIENT_VERSION = "1.20260707.12.00";
const USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
const MAX_BODY = 4096;

function cors(origin) {
  return {
    "Access-Control-Allow-Origin": ALLOWED_ORIGINS.includes(origin) ? origin : ALLOWED_ORIGINS[0],
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

const reply = (status, body, origin) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json; charset=utf-8", ...cors(origin) },
});

export default {
  async fetch(request) {
    const origin = request.headers.get("Origin") || "";
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(origin) });
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") return reply(200, { ok: true, service: "spotiaisexs-catalog" }, origin);
    if (request.method !== "POST") return reply(405, { error: "Solo POST" }, origin);
    if (!ALLOWED_ORIGINS.includes(origin)) return reply(403, { error: "Origen no permitido" }, origin);

    const endpoint = url.pathname.replace(/^\/+/, "");
    if (!ENDPOINTS.has(endpoint)) return reply(404, { error: "Consulta no permitida" }, origin);

    const text = await request.text();
    if (text.length > MAX_BODY) return reply(413, { error: "Consulta demasiado larga" }, origin);
    let query;
    try { query = JSON.parse(text); } catch { return reply(400, { error: "JSON no válido" }, origin); }

    // El contexto del cliente lo pone el Worker: la web solo manda la consulta.
    const body = {
      ...query,
      context: { client: { clientName: "WEB_REMIX", clientVersion: CLIENT_VERSION, hl: "en", gl: query.gl || "ES" } },
    };
    delete body.gl;

    let upstream;
    try {
      upstream = await fetch(`${UPSTREAM}${endpoint}?prettyPrint=false`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "User-Agent": USER_AGENT,
          "Origin": "https://music.youtube.com",
          "Referer": "https://music.youtube.com/",
          "X-YouTube-Client-Name": "67",
          "X-YouTube-Client-Version": CLIENT_VERSION,
        },
        body: JSON.stringify(body),
      });
    } catch {
      return reply(502, { error: "YouTube Music no responde" }, origin);
    }
    return new Response(upstream.body, {
      status: upstream.status,
      headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "private, max-age=300", ...cors(origin) },
    });
  },
};
