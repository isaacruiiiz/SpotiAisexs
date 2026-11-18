# SpotiAisexs web · sincronización con el teléfono

La web (`web/`) se publica en GitHub Pages y se sincroniza en tiempo real con la
app del teléfono, al estilo de Spotify Connect: un dispositivo reproduce y los
demás lo ven y lo controlan. "Reproducir aquí" pasa la música de uno a otro.

Como GitHub Pages solo sirve archivos estáticos, el "puente" entre teléfono y
web es una base de datos de **Firebase Realtime Database** de tu propia cuenta
de Google (plan gratuito Spark, de sobra para uso personal).

## 1. Crear el proyecto de Firebase

1. Entra en <https://console.firebase.google.com> → **Crear un proyecto**
   (Google Analytics no hace falta).
2. **Compilación → Authentication → Comenzar → Correo electrónico/contraseña →
   Habilitar**.
3. En **Authentication → Usuarios → Agregar usuario**, crea tu cuenta (correo y
   contraseña). Es la que usarás en la web y en el teléfono.
4. **Compilación → Realtime Database → Crear base de datos** → ubicación
   `europe-west1` → **modo bloqueado**.
5. En la pestaña **Reglas** de la base de datos, pega el contenido de
   `web/database.rules.json` y pulsa **Publicar**. Así solo tu cuenta puede
   leer y escribir tus datos.
6. **Configuración del proyecto (⚙️) → Tus apps → Web (`</>`)** → registra una
   app (sin Hosting). Copia el bloque `firebaseConfig`.

## 2. Configurar la web

Abre `web/config.js` y sustituye los valores por los de tu `firebaseConfig`
(sobre todo `apiKey`, `authDomain`, `databaseURL`, `projectId` y `appId`).
Si `databaseURL` no aparece en el bloque, cópiala de la cabecera de Realtime
Database (termina en `firebasedatabase.app` o `firebaseio.com`).

Estas claves son públicas por diseño; la protección la dan las reglas del paso 5.

## 3. Publicar en GitHub Pages

1. En GitHub: **Settings → Pages → Build and deployment → Source: GitHub Actions**.
2. Sube los cambios. El workflow **Publicar web (GitHub Pages)** publica la
   carpeta `web/` en `https://isaacruiiiz.github.io/SpotiAisexs/`.

GitHub Pages gratis solo funciona con repositorios **públicos**. No hay nada
secreto en el repo: las claves de firma van en los secrets de Actions y la
config de Firebase es pública por diseño.

Opcional: en Firebase, **Authentication → Configuración → Dominios autorizados**,
añade `isaacruiiiz.github.io`.

## 4. Enlazar el teléfono

En la app: **Ajustes → Importar → Conectar con la web**.

1. Copia el bloque `firebaseConfig` y pulsa **Pegar configuración** (rellena la
   API key y la URL de la base de datos), o escríbelas a mano.
2. Pon el mismo correo y contraseña que en la web y pulsa **Conectar**.

El teléfono solo guarda un token de sesión, nunca la contraseña.

## Catálogo completo en la web

Para que la web tenga lo mismo que la app sin depender del teléfono (buscar sin
límite, páginas de artista y de álbum, "Esto es", emisoras) necesita un pequeño
puente: un navegador no puede hablar directamente con YouTube Music. El puente
es un Worker gratuito de Cloudflare (100 000 consultas al día).

1. Entra en <https://dash.cloudflare.com> → **Workers y Pages → Crear → Worker**.
   Ponle un nombre (por ejemplo `spotiaisexs-catalogo`) y pulsa **Implementar**.
2. Pulsa **Editar código**, borra lo que haya, pega el contenido de
   `web/worker/catalog-worker.js` y pulsa **Implementar**.
3. Copia la dirección del Worker (termina en `.workers.dev`) y ponla en
   `web/config.js`:

   ```js
   export const catalogUrl = "https://spotiaisexs-catalogo.TU-USUARIO.workers.dev";
   ```

4. Sube el cambio. Al abrir la web desaparece el aviso "Falta el catálogo
   completo" y aparecen "Esto es" y "Artistas para ti".

El Worker solo acepta consultas desde `https://isaacruiiiz.github.io`; si
publicas la web en otra dirección, añádela a `ALLOWED_ORIGINS` en el archivo.
No guarda nada ni usa tu cuenta. Sin él la web sigue funcionando con tu
biblioteca y con la clave de YouTube de más abajo.

## Cómo se usa

La web funciona **sola**, sin el teléfono:

- **Radio (inicio):** "Tu mezcla" con el dial (de pegada a lo que conoces a
  todo nuevo), las emisoras Descubrir, Redescubrir y Me gusta, lo último que
  sonó, tus playlists, "Esto es" de tus artistas y artistas para ti.
- **Buscar:** canciones, artistas y álbumes. Una canción buscada arranca su
  radio; un álbum o una playlist suenan tal cual y al acabar siguen con radio.
- **Artista, álbum y "Esto es":** con la portada grande, como en la app.
  "Guardar" los añade a tu biblioteca.
- **Biblioteca:** tus playlists y Me gusta. Lo que cambies en la web se ve al
  momento y el teléfono lo aplica a su biblioteca cuando se conecta; lo que
  cambies en el teléfono llega a la web igual.
- **Lo que viene:** la cola, con el dial cuando suena una radio.
- Atajos: espacio (play/pausa), Mayús + ← / → (anterior/siguiente) y las teclas
  multimedia del teclado.

Y sincronizada con el teléfono (como Spotify Connect):

- **Lo que suena en el teléfono** aparece al momento en la web y se controla
  desde ella: pausa, saltar, la barra, elegir de la cola, añadir a la cola,
  iniciar una radio o una emisora y mover el dial. Si el teléfono no contesta
  en unos segundos, la web lo dice en vez de quedarse callada.
- **Si el teléfono está sonando, lo que pongas en la web suena en el
  teléfono.**
- **Pasar la música de un sitio a otro, en el mismo segundo:** en la web,
  botón de dispositivos → *Este navegador* o el teléfono. En el teléfono,
  botón de dispositivos del reproductor → el navegador o *Este teléfono*.
- Si el dispositivo que reproducía se desconecta, la web te ofrece **seguir
  aquí** desde donde se quedó.

En el navegador el audio sale del reproductor oficial de YouTube incrustado,
que tiene que verse mientras suena (lo exigen sus condiciones) y puede mostrar
anuncios. Si YouTube no deja incrustar una canción, la web prueba sola con otra
versión del mismo tema (el vídeo oficial, por ejemplo) y, si no hay ninguna,
pasa a la siguiente. Y si mandas la música al navegador desde el teléfono sin
haber tocado antes esa pestaña, el navegador puede pedir un toque para
arrancar el sonido: aparece el botón "Toca para que suene aquí".

No están en la web: descargas para escuchar sin conexión, la letra, las
estadísticas, las emisoras Coche y Tranquilo (necesitan datos de tempo que
solo calcula la app) y los ajustes de la app.

## Búsqueda en YouTube (opcional)

1. Entra en <https://console.cloud.google.com> y elige el **mismo proyecto** que
   creó Firebase.
2. **APIs y servicios → Biblioteca →** busca **YouTube Data API v3 → Habilitar**.
3. **APIs y servicios → Credenciales → Crear credenciales → Clave de API.**
4. Edita la clave: **Restricciones de aplicación → Sitios web** →
   `https://isaacruiiiz.github.io/*`; **Restricciones de API →** solo
   *YouTube Data API v3*. Guarda.
5. Añade al final de `web/config.js`:

   ```js
   export const youtubeApiKey = "TU_CLAVE";
   ```

La cuota gratuita da para unas **100 búsquedas al día**. Buscar dentro de tu
biblioteca no gasta cuota.

## Jam con amigos

Tus amigos añaden canciones a tu cola desde un enlace o un QR: sin instalar
nada, sin cuenta y aunque usen Spotify. Las canciones que añaden entran después
de la que suena y en el orden en que las pidieron, como en la Jam de Spotify.

**Activarla (una vez):**

1. Firebase → **Authentication → Método de acceso → Anónimo → Habilitar**
   (los invitados entran como usuarios anónimos).
2. Firebase → **Realtime Database → Reglas**: pega el contenido actual de
   `web/database.rules.json` y pulsa **Publicar**.

**Usarla:**

- En la web: botón **Jam** de la barra inferior → **Empezar Jam**.
- En el teléfono: **Ajustes → Web y Jam → Empezar Jam**.
- En el coche (Android Auto): pestaña **Jam → Empezar Jam**; el QR aparece en
  grande para que lo escaneen desde el asiento de al lado.
- Enseña el QR o comparte el enlace. Cada amigo pone su nombre y ve la cola:
  lo que suena y lo que viene, con quién añadió cada canción.
- Para añadir, buscan la canción y tocan **⋯ → Añadir a la cola**, o la
  **deslizan hacia la derecha** (como en Spotify). También pueden pegar en el
  buscador el enlace de una canción de Spotify.
- **Terminar Jam** cierra el acceso al momento.

## Datos que se guardan (users/{tu uid}/…)

| Ruta | Contenido |
|---|---|
| `playback` | Dispositivo activo, canción, posición, estado, próximas 50 canciones y, si suena una radio, el dial |
| `devices/{id}` | Nombre, tipo y último latido de cada dispositivo conectado |
| `commands/{id}` | Órdenes pendientes para ese dispositivo (se borran al ejecutarse) |
| `library/playlists` | Tus playlists del teléfono (solo título, portada y canciones) |
| `jam` | Jam abierta: código, invitados, peticiones pendientes y quién añadió cada canción |
| `libraryInbox` | Cambios hechos en la web (Me gusta, playlists) pendientes de aplicar en el teléfono |
