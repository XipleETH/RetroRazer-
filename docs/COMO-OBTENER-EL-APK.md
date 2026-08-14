# Cómo obtener e instalar el APK (sin Android Studio)

El APK se compila **en la nube** con GitHub Actions cada vez que hay cambios en
`rumble-bridge/`. Tú solo lo descargas e instalas. Dos formas:

## Opción 1 — Enlace directo (ideal para el celular)

Cada build actualiza una *release* fija llamada **`apk-latest`** con el APK
adjunto. El enlace directo es:

```
https://github.com/XipleETH/RetroRazer-/releases/download/apk-latest/RetroRazer-RumbleBridge-debug.apk
```

También puedes ir a la pestaña **Releases** del repo y bajar el asset
`RetroRazer-RumbleBridge-debug.apk` de la release *APK más reciente*.

1. Abre el enlace en el navegador del **celular** (si el repo es privado,
   inicia sesión en GitHub en el navegador primero).
2. Descarga el `.apk`.
3. Al instalar, Android pedirá permitir **"instalar apps desconocidas"** para el
   navegador o la app de Archivos → acéptalo.
4. Abre **RetroRazer Rumble Bridge**.

## Opción 2 — Artefacto del build (desde el PC)

1. Ve a la pestaña **Actions** del repo en el PC.
2. Entra al último run de **Build APK** (verde ✓).
3. Abajo, en **Artifacts**, descarga `RetroRazer-RumbleBridge-debug`
   (viene en `.zip`; descomprímelo para sacar el `.apk`).
4. Pásalo al celular por cable e instálalo, o instálalo por adb:
   ```bash
   adb install -r RetroRazer-RumbleBridge-debug.apk
   ```

## Ver el estado del build

Pestaña **Actions** → workflow **Build APK**. Si sale en rojo, el log dice qué
falló en la compilación (yo lo reviso y lo corrijo).

## Notas

- El APK es de **depuración** (debug): se instala directo, no necesita firma de
  Play Store. Perfecto para probar el rumble.
- Cada push a la rama de desarrollo regenera el APK y actualiza el enlace de la
  Opción 1 automáticamente.
