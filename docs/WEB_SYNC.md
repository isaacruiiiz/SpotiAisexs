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

## Cómo se usa

La web funciona **sola**, sin el teléfono:

- **Biblioteca:** cuando el teléfono está enlazado, sube tus playlists (Me gusta
  incluida) a Firebase. Quedan guardadas, así que la web las muestra y las
  reproduce aunque el teléfono esté apagado.
- **Me gusta y playlists desde la web:** corazón en la barra y en cada canción;
  menú **⋯** (o clic derecho) → *Añadir a la cola*, *Iniciar radio*,
  *Añadir a Me gusta*, *Añadir a una playlist* o crear una nueva. Se ven al
  momento y el teléfono los aplica a su biblioteca cuando se conecta.
- **Radio como en el teléfono:** al poner una canción suelta (buscada) la cola
  sigue con canciones de su entorno; **Radio infinita** (en Inicio) mezcla
  según tus gustos. Usa la "Mix" de YouTube de cada canción y la clave de
  YouTube (1 unidad de cuota por cada 50 canciones, casi nada). Al acabarse
  una playlist sigue con radio de su última canción.
- Atajos: espacio (play/pausa), Mayús + ← / → (anterior/siguiente) y las teclas
  multimedia del teclado.

Y sincronizada con el teléfono (como Spotify Connect):

- **Lo que suena en el teléfono** aparece al momento en la web y se controla
  desde ella (pausa, saltar, barra, elegir de la cola, añadir a la cola,
  iniciar radio).
- **Si el teléfono está sonando, lo que pongas en la web suena en el
  teléfono.** Para que suene en el navegador, elige *Este navegador* en el
  botón de dispositivos.
- **Pasar la música de un sitio a otro:** botón de dispositivos; o en el
  teléfono, barra "Escuchando en Navegador" → **Aquí**, o darle a play. Sigue
  en el segundo exacto.
- Si el dispositivo que reproducía se desconecta (pestaña cerrada, teléfono sin
  red), la web lo detecta y te ofrece **seguir aquí** desde donde se quedó.

En el navegador el audio sale del reproductor oficial de YouTube incrustado,
que tiene que estar visible mientras suena (lo exigen sus condiciones) y puede
mostrar anuncios. Algunas canciones no permiten reproducirse incrustadas; en ese
caso la web salta a la siguiente.

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
| `playback` | Dispositivo activo, canción, posición, estado y próximas 50 canciones |
| `devices/{id}` | Nombre, tipo y último latido de cada dispositivo conectado |
| `commands/{id}` | Órdenes pendientes para ese dispositivo (se borran al ejecutarse) |
| `library/playlists` | Tus playlists del teléfono (solo título, portada y canciones) |
| `jam` | Jam abierta: código, invitados, peticiones pendientes y quién añadió cada canción |
| `libraryInbox` | Cambios hechos en la web (Me gusta, playlists) pendientes de aplicar en el teléfono |
