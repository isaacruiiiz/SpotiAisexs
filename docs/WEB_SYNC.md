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

- **Lo que suena en el teléfono** aparece al momento en la web (canción,
  posición, cola). Desde la web puedes pausar, saltar, buscar en la barra o
  elegir otra canción de la cola.
- **Reproducir en el navegador:** botón de dispositivos → *Este navegador*.
  La web sigue desde el mismo segundo y el teléfono se pausa.
- **Volver al teléfono:** en la web, botón de dispositivos → tu teléfono; o en
  el teléfono, la barra "Escuchando en Navegador" → **Aquí**. También vale con
  darle a play en el teléfono.

En el navegador el audio sale del reproductor oficial de YouTube incrustado,
que tiene que estar visible mientras suena (lo exigen sus condiciones) y puede
mostrar anuncios. Algunas canciones no permiten reproducirse incrustadas; en ese
caso la web salta a la siguiente.

## Datos que se guardan (users/{tu uid}/…)

| Ruta | Contenido |
|---|---|
| `playback` | Dispositivo activo, canción, posición, estado y próximas 50 canciones |
| `devices/{id}` | Nombre, tipo y último latido de cada dispositivo conectado |
| `commands/{id}` | Órdenes pendientes para ese dispositivo (se borran al ejecutarse) |
