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
- **Búsqueda:** busca en tu biblioteca y, si añades la clave de YouTube (abajo),
  en todo YouTube. Botón **+** de cada canción = añadir a continuación.
- **Al acabar la cola** sigue con más canciones del mismo artista.
- Atajos: espacio (play/pausa), Mayús + ← / → (anterior/siguiente) y las teclas
  multimedia del teclado.

Y sincronizada con el teléfono:

- **Lo que suena en el teléfono** aparece al momento en la web y se controla
  desde ella (pausa, saltar, barra, elegir de la cola).
- **Cualquier canción que pongas en la web suena en el navegador** y el
  teléfono se pausa solo.
- **Pasar la música al teléfono:** botón de dispositivos → tu teléfono; o en el
  teléfono, barra "Escuchando en Navegador" → **Aquí**, o darle a play.
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

## Datos que se guardan (users/{tu uid}/…)

| Ruta | Contenido |
|---|---|
| `playback` | Dispositivo activo, canción, posición, estado y próximas 50 canciones |
| `devices/{id}` | Nombre, tipo y último latido de cada dispositivo conectado |
| `commands/{id}` | Órdenes pendientes para ese dispositivo (se borran al ejecutarse) |
| `library/playlists` | Tus playlists del teléfono (solo título, portada y canciones) |
