// Configuración de tu proyecto de Firebase (consola de Firebase → Configuración
// del proyecto → Tus apps → app web → "Configuración del SDK").
// Estas claves son públicas por diseño: la seguridad la ponen las reglas de la
// base de datos (database.rules.json), que solo dejan entrar a tu cuenta.
export const firebaseConfig = {
  apiKey: "PEGA_AQUI_TU_API_KEY",
  authDomain: "tu-proyecto.firebaseapp.com",
  databaseURL: "https://tu-proyecto-default-rtdb.europe-west1.firebasedatabase.app",
  projectId: "tu-proyecto",
  appId: "PEGA_AQUI_TU_APP_ID",
};
