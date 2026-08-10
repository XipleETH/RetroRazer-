# Parche del frontend Android de RetroArch (eslabón 3)

**Qué arregla:** que la vibración se entregue al **control conectado** (su
`InputDevice` vibrator) en lugar del vibrador del teléfono, con respaldo opcional.

**Cuándo aplicarlo:** solo si `rumble-bridge` confirma **Camino A**
(`hasVibrator = true` y *Probar vibración estándar* mueve los motores) pero
RetroArch sigue sin vibrar tras configurar el núcleo (`docs/RETROARCH-CONFIG.md`).

## Archivo objetivo

```
RetroArch/pkg/android/phoenix/src/com/retroarch/browser/retroactivity/RetroActivityCommon.java
```

Ahí existe el método `doVibrate(int id, int effect, int strength, int oneShot)`,
invocado por JNI desde `input/drivers/android_input.c`
(`android_input_set_rumble`). Sustituye ese método por la implementación de
[`RetroActivityCommon.doVibrate.java`](RetroActivityCommon.doVibrate.java) y añade
los `import` indicados al principio de la clase.

No se entrega como parche por número de línea a propósito: las líneas cambian
entre versiones/forks de RetroArch. Es un **reemplazo del método** para que
aplique en cualquier versión razonablemente reciente.

## Imports necesarios (parte superior del archivo)

```java
import android.view.InputDevice;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.os.VibrationEffect;
import android.os.Build;
import android.os.CombinedVibration;
```

## Cómo recompilar RetroArch (Android)

```bash
git clone https://github.com/libretro/RetroArch
cd RetroArch/pkg/android/phoenix
# aplica el reemplazo del método en RetroActivityCommon.java
./gradlew assembleNormalRelease   # o abre la carpeta 'phoenix' en Android Studio
```

El APK queda en `pkg/android/phoenix/build/outputs/apk/`.

## Comportamiento del nuevo `doVibrate`

1. Resuelve el `InputDevice` a partir del `id` recibido; si no, recorre los
   dispositivos conectados buscando un mando (gamepad/joystick) con vibrador.
2. Si el control expone un vibrador (API 31+ vía `VibratorManager`, API 26–30 vía
   `InputDevice.getVibrator()`), **le manda la vibración a él**.
3. `strength` (0..0xFFFF de libretro) se escala a amplitud 1..255.
4. Respaldo al vibrador del teléfono **solo** si `RETRORAZER_FALLBACK_PHONE` es
   `true` (por defecto `false`, para no "engañar" haciendo vibrar el móvil).
5. `strength == 0` cancela la vibración.
