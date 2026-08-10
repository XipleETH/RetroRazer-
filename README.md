# RetroRazer

**Objetivo:** hacer que las vibraciones (rumble) de los juegos originales —PlayStation 1, Game Boy Advance, etc.— emulados en **RetroArch para Android** lleguen a los motores del control **Razer** con tecnología **Sensa HD Haptics** (Razer Kishi Ultra), que hoy no vibran aunque la caja diga que deberían.

Este repositorio contiene:

1. **`rumble-bridge/`** — una app Android nativa de **diagnóstico y prueba de rumble**. Es la pieza clave: detecta el control, dice exactamente *cómo* expone (o no) sus motores a Android, y **hace vibrar los motores** para comprobarlo. Sin esto trabajaríamos a ciegas.
2. **`patches/`** — el parche para el frontend Android de RetroArch que enruta el rumble al **control conectado** en vez de al teléfono.
3. **`docs/`** — el análisis técnico completo y la guía de configuración de RetroArch.

---

## El problema, en corto

Para que un juego de PS1 haga vibrar tu control tienen que cumplirse **tres eslabones**, y basta que uno falle para que no sientas nada:

```
[Juego PS1]  →  [Núcleo libretro emite rumble]  →  [Frontend RetroArch reenvía la vibración]  →  [Android/USB entrega la vibración al control]  →  [Motores del Razer]
     (1)                    (2)                                 (3)                                         (4)
```

- **(2) El núcleo debe emitir rumble.** Muchos núcleos de PS1 (Beetle PSX, PCSX-ReARMed, SwanStation) solo generan rumble si activas la opción de núcleo *Rumble* y usas un **DualShock/analog** como tipo de mando. Si el juego cree que tiene un mando digital, nunca pide vibración.  → Ver `docs/RETROARCH-CONFIG.md`.
- **(3) El frontend debe reenviar la vibración al control**, no al vibrador del teléfono. Aquí está el fallo histórico de RetroArch en Android. → Ver `patches/`.
- **(4) Android/USB debe poder entregar la vibración a los motores del Razer.** Y aquí está la gran incógnita del hardware ↓

## La incógnita del hardware (por eso existe `rumble-bridge`)

Las **Sensa HD Haptics** del Kishi Ultra usan bobinas hápticas gobernadas por el **SDK Interhaptics / app Razer Nexus** (con conversión *audio-to-haptics*). **No está garantizado** que Android exponga esos motores por la API estándar de vibración de mandos (`InputDevice.getVibrator()` / `VibratorManager`).

Hay dos escenarios posibles:

- **Camino A — El control SÍ expone un vibrador estándar.**
  Entonces el arreglo es: activar el rumble en el núcleo (2) + parchear/configurar RetroArch para que enrute al control (3). La app `rumble-bridge` lo confirma en 10 segundos y lo hace vibrar.
- **Camino B — El control NO expone vibrador estándar** (los motores solo responden a reportes HID propietarios de Razer sobre USB).
  Entonces ninguna configuración de RetroArch bastará: hay que **enviar reportes HID por USB** al control. `rumble-bridge` incluye un modo de **descubrimiento HID por USB Host** para hacer mover los motores y aprender el formato de reporte de Razer, que es el requisito previo para cualquier puente real.

**No inventamos un protocolo que no podemos verificar.** La app mide la realidad de *tu* control y de ahí decidimos el camino. Los pasos que requieren el hardware están marcados como **[REQUIERE DISPOSITIVO]** en la documentación.

---

## Empezar aquí

1. Compila e instala la app de diagnóstico:
   ```bash
   cd rumble-bridge
   # Abre la carpeta en Android Studio (recomendado) y pulsa Run,
   # o por línea de comandos:
   gradle wrapper        # genera ./gradlew la primera vez
   ./gradlew installDebug
   ```
2. Conecta el Kishi Ultra, abre **RetroRazer Rumble Bridge** y lee el informe. Te dirá si estás en el **Camino A** o **B** y te dejará pulsar *Probar* para sentir los motores.
3. Según el resultado, sigue `docs/RETROARCH-CONFIG.md` (Camino A) o `docs/ANALISIS-TECNICO.md` §Camino B.

Detalles completos del razonamiento y de cada eslabón en **`docs/ANALISIS-TECNICO.md`**.
