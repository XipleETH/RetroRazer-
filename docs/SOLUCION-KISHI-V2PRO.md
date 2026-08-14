# Solución para Razer Kishi V2 Pro (haptics por audio)

## Diagnóstico CONFIRMADO con el hardware real

La app `rumble-bridge` reportó, con un **Razer Kishi V2 Pro** conectado:

- El control se reconoce como mando de entrada.
- **`vibrador estándar: NO`** en todos los casos.

Esto NO es un fallo: es la naturaleza del hardware. Los motores del Kishi V2 Pro
usan **HyperSense / "Audio Haptics"**: la vibración se genera a partir del **audio
del juego** (explosiones, disparos, motores → vibración), gestionada por la app
**Razer Nexus**. No son motores de "rumble" gobernados por comandos; por eso:

- Android **no los expone** por `InputDevice.getVibrator()` (de ahí el `NO`).
- Ningún parche de rumble en RetroArch los haría vibrar por la vía estándar.
- No existe (probablemente) un comando HID discreto de "motor ON" que descubrir:
  el motor está cableado al motor de audio-háptica, no a un canal de rumble.

Conclusión: en este hardware, **el camino correcto es el audio-háptico**, no el
rumble tradicional.

## Solución inmediata (sin desarrollo): Razer Nexus Audio Haptics

1. Instala **Razer Nexus** desde Google Play (si no lo tienes).
2. Abre Nexus → **Settings → Controller Options → Audio Haptics Sensitivity**
   → ponlo en **High** (alto).
3. **Añade RetroArch a la librería de Nexus** y **lánzalo DESDE Nexus** (Nexus
   permite lanzar apps/juegos que no son suyos y aplicarles Audio Haptics).
4. Activa **Audio Haptics** para RetroArch (se habilita juego a juego dentro de
   Nexus).
5. Juega un PS1 **con el audio encendido** (no en silencio). El control vibrará
   siguiendo el sonido del juego.

Requisitos: Android reciente, audio activo. Sin audio no hay vibración.

### Qué esperar (honestidad)

- La vibración **sigue el sonido**, no el comando exacto de rumble del DualShock.
  Sentirás explosiones, disparos y motores; a veces "seguirá" la música en vez
  del rumble clásico de consola. Es la forma en que este control cumple lo que
  promete la caja.
- Juegos muy silenciosos o con rumble que no coincide con un sonido fuerte
  vibrarán menos.

## Mejora "rumble real" (con desarrollo): puente rumble→audio-háptico

Como el control vibra con el audio, se puede lograr que vibre con el **rumble
real del juego** (no solo con el sonido) así:

1. Capturar el evento de rumble del núcleo de PS1 en el frontend de RetroArch
   (el método `doVibrate` que ya está en `patches/`, que recibe la intensidad
   0..0xFFFF por motor).
2. En vez de mandar la vibración a un vibrador inexistente, **sintetizar un tono
   háptico de baja frecuencia (~60 Hz)** con amplitud proporcional a la fuerza
   del rumble, y **mezclarlo en la salida de audio**.
3. El sistema **Audio Haptics** del Kishi convierte ese tono en vibración de los
   motores — dando un rumble que **coincide exactamente** con los comandos del
   juego, aprovechando el diseño audio-háptico del control.

Ventajas: rumble fiel al juego, sin USB ni reversear protocolos.
Límite honesto: el tono háptico puede oírse como un zumbido grave leve mezclado
con el audio; se puede atenuar/afinar la frecuencia y la sensibilidad de Nexus
para minimizarlo. Requiere recompilar RetroArch (build en la nube ya montado).

## ¿Y el "Camino B" (USB HID) del análisis técnico?

Sigue disponible en la app, pero para el Kishi V2 Pro es un **callejón probable**:
al ser los motores audio-hápticos, es muy posible que **no exista** un reporte HID
de rumble discreto que descubrir. Por eso se recomienda el camino audio-háptico
(Nexus directo, o el puente rumble→audio) antes que el barrido USB.
