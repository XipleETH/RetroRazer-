# Análisis técnico: rumble de RetroArch → Razer Sensa (Kishi Ultra)

Este documento explica la cadena completa de la vibración, dónde se rompe, y cómo
la app `rumble-bridge` decide entre los dos caminos posibles.

## 1. La cadena de la vibración

```
Juego PS1
  │  (el juego escribe en el puerto del mando pidiendo "motor pequeño / motor grande")
  ▼
Núcleo libretro (Beetle PSX / PCSX-ReARMed / SwanStation)
  │  RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE → retro_set_rumble_state(port, RETRO_RUMBLE_STRONG/WEAK, fuerza)
  ▼
input_driver de RetroArch  (input/input_driver.c → current_input->set_rumble)
  │  en Android: android_input_set_rumble()  (input/drivers/android_input.c)
  ▼
Puente JNI  → llama al método Java doVibrate(...)
  │
  ▼
Frontend Android (RetroActivityCommon.java :: doVibrate)
  │  AQUÍ se decide a QUÉ vibrador va: ¿el del teléfono, o el del control?
  ▼
API de Android
  ├─ Camino A: InputDevice.getVibrator() / VibratorManager  → el control (si lo expone)
  └─ (teléfono) getSystemService(VIBRATOR_SERVICE)          → vibrador del móvil
  ▼
Motores físicos
```

### Eslabón 2 — el núcleo debe *pedir* rumble
Un juego de PS1 solo pide vibración si el emulador le presenta un **DualShock/mando
analógico** y la opción de **Rumble** del núcleo está activada. Con un mando
"digital" el juego nunca activará los motores. Configuración exacta en
`RETROARCH-CONFIG.md`.

### Eslabón 3 — el frontend debe enrutar al control
`android_input_set_rumble()` recibe el *puerto* (0,1,2…) y busca el `InputDevice`
correspondiente. El método Java `doVibrate` es quien materializa la vibración.
Históricamente RetroArch en Android vibraba el **teléfono** o no resolvía bien el
vibrador del mando conectado (issue libretro/RetroArch #10338). El parche de
`patches/` fuerza que se use el vibrador del **control** cuando existe, con
respaldo (fallback) opcional al teléfono. Ver `patches/README.md`.

### Eslabón 4 — Android/USB debe poder mover los motores del Razer
Este es el punto que **no se puede asumir** para el Kishi Ultra.

## 2. Por qué el Kishi Ultra es un caso especial

Las **Razer Sensa HD Haptics** no son un motor ERM/LRA "de rumble" corriente:
son **bobinas hápticas de banda ancha** pensadas para ser gobernadas por el
**SDK Interhaptics** y la app **Razer Nexus**, incluyendo conversión
*audio-to-haptics* en tiempo real. Requieren **Android 12+**.

Consecuencia práctica: es perfectamente posible que Android **no publique** esos
motores a través de la API estándar `InputDevice.getVibrator()`. Si
`hasVibrator()` devuelve `false`, entonces:

- Ni RetroArch ni ninguna otra app pueden moverlos con `vibrator.vibrate(...)`.
- Los motores solo responden a **reportes HID de salida** (output reports) con el
  formato propietario de Razer, enviados por USB — que es justo lo que hacen
  Nexus/Interhaptics por dentro.

Existen además reportes de la comunidad de que el rumble **estándar** del Kishi
Ultra no llega en algunos Android (moonlight-android #1365), lo que refuerza la
hipótesis del **Camino B**.

## 3. Los dos caminos

### Camino A — el control expone un vibrador estándar
Condición: en `rumble-bridge`, el control aparece con **`hasVibrator = true`** y
al pulsar *Probar vibración estándar* **se sienten los motores**.

Solución (100% software, sin USB):
1. Eslabón 2: activar Rumble + tipo DualShock en el núcleo (`RETROARCH-CONFIG.md`).
2. Eslabón 3: usar un RetroArch que enrute al control. Si tu build no lo hace,
   aplica el parche de `patches/` y recompila, o ajusta la opción
   *Ajustes → Entrada → Vibración del dispositivo / Vibrar en los mandos*.
3. Listo: los juegos harán vibrar el control.

### Camino B — el control NO expone vibrador estándar
Condición: `hasVibrator = false` (o `true` pero *Probar* no mueve nada), y el
control aparece en la lista **USB** con VID `0x1532` (Razer).

Realidad honesta: **no hay un atajo de configuración**. Hay que hablarle al control
por **USB HID** con su formato de reporte de rumble, que Razer **no documenta
públicamente**. El trabajo se divide en:

1. **Descubrimiento del protocolo [REQUIERE DISPOSITIVO].**
   `rumble-bridge` incluye un modo *USB HID* que:
   - Abre el control con la **USB Host API** (`UsbManager`), reclama la interfaz HID.
   - Lista interfaces, endpoints y descriptores de reporte.
   - Permite **enviar reportes de salida candidatos** y barrer variantes para
     encontrar cuál hace vibrar los motores (SET_REPORT vía `controlTransfer` y/o
     endpoint interrupt OUT).
   - Cuando un reporte funcione, anota **ese** patrón: ese es el protocolo.
   > No se incluye un "protocolo Razer" inventado; se descubre con tu hardware.
2. **Puente en tiempo real (una vez conocido el formato).**
   RetroArch no puede, por sí solo, mandar bytes USB a un tercero. Opciones:
   - **a)** Extender el frontend Android de RetroArch para que, además de llamar a
     `doVibrate`, reenvíe la intensidad a un `Service` que mantenga abierto el
     canal USB HID y escriba los reportes descubiertos. (Requiere recompilar
     RetroArch; el `Service` y el escritor HID ya viven en `rumble-bridge`.)
   - **b)** Si el control soporta un modo "XInput/estándar" por firmware (algunos
     Kishi lo tienen), forzarlo puede reactivar el Camino A. La app también lo
     detecta al mostrar el VID/PID y las interfaces.

## 4. Qué está verificado y qué no

| Afirmación | Estado |
|---|---|
| La cadena 2→3→4 y dónde se rompe | Verificado por diseño de RetroArch/libretro |
| RetroArch en Android históricamente no enruta bien al control | Documentado (issue #10338) |
| Sensa usa Interhaptics/Nexus y Android 12+ | Confirmado (Razer) |
| Si el Kishi Ultra expone o no `getVibrator()` estándar | **No verificado** — lo resuelve `rumble-bridge` en tu dispositivo |
| Formato exacto del reporte HID de rumble de Razer | **No público** — se descubre con `rumble-bridge` |

Todo lo que dependa del hardware está marcado **[REQUIERE DISPOSITIVO]** y lo
ejecuta la app; no se afirma como resuelto hasta probarlo con el control real.
