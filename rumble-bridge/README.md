# RetroRazer Rumble Bridge (app de diagnóstico)

App Android nativa (Kotlin, sin AndroidX) que averigua **cómo** expone sus motores
tu control Razer y **los hace vibrar** para comprobarlo. Es el punto de partida de
todo el proyecto: decide si estás en el **Camino A** o **B** (ver
`../docs/ANALISIS-TECNICO.md`).

## Qué hace

1. **Escanea los controles** (`InputDevice`) y muestra VID/PID y si exponen un
   **vibrador estándar** (`getVibrator` / `VibratorManager`).
2. Botón **Probar** por control: dispara los motores por la API estándar.
   - Si vibra → **Camino A** (arreglo por config de RetroArch + parche).
   - Si no hay vibrador o no vibra → **Camino B**.
3. **Sección USB**: inspecciona interfaces/endpoints HID y ofrece un **barrido de
   descubrimiento** que envía reportes de salida candidatos para encontrar cuál
   mueve los motores del Razer (protocolo no documentado por Razer).

## Compilar

Requiere Android SDK. La forma más simple es **Android Studio**: `File → Open` →
carpeta `rumble-bridge` → Run.

Por línea de comandos:

```bash
cd rumble-bridge
gradle wrapper                 # genera ./gradlew (solo la 1ª vez; necesita Gradle instalado)
./gradlew installDebug         # compila e instala en el dispositivo conectado (adb)
```

- `compileSdk 34`, `minSdk 26`, `targetSdk 34`, JDK 17.
- No usa AndroidX ni dependencias externas: solo el framework de Android.
- El `.jar` del wrapper no se versiona (ver `.gitignore`); Android Studio o
  `gradle wrapper` lo regeneran.

## Permisos

- `VIBRATE` — para la prueba de vibración.
- USB Host — para el modo de descubrimiento HID (se pide permiso en tiempo de
  ejecución al pulsar el barrido).

## Uso típico

1. Conecta el Kishi Ultra, abre la app.
2. Mira la sección **1)**: ¿algún control dice `vibrador estándar: SÍ`?
   - Pulsa **Probar**. ¿Sientes los motores?
3. Según el resultado, sigue `../docs/RETROARCH-CONFIG.md` (A) o la sección
   **2) USB** + `../docs/ANALISIS-TECNICO.md` §Camino B.
