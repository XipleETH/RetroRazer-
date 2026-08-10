/*
 * RetroRazer — reemplazo de doVibrate() para el frontend Android de RetroArch.
 *
 * Pega este método dentro de la clase RetroActivityCommon
 * (pkg/android/phoenix/src/com/retroarch/browser/retroactivity/RetroActivityCommon.java),
 * reemplazando el doVibrate() existente, y añade los imports indicados en
 * patches/README.md.
 *
 * Objetivo: entregar la vibración al CONTROL conectado (su vibrador estándar),
 * no al vibrador del teléfono. Esto sólo funciona si el control expone un
 * vibrador estándar (Camino A). Compruébalo antes con la app rumble-bridge.
 *
 * Firma esperada por el puente JNI (input/drivers/android_input.c):
 *     void doVibrate(int id, int effect, int strength, int oneShot)
 *   - id:       device id del InputDevice a vibrar, o -1 para "resolver el mejor".
 *   - effect:   sin usar aquí (compatibilidad con la firma original).
 *   - strength: intensidad de libretro en el rango 0..65535.
 *   - oneShot:  !=0 → pulso corto; 0 → vibración sostenida.
 */

// Poner a true SOLO si quieres que, cuando el control no tenga vibrador,
// vibre el teléfono como respaldo. Por defecto false para no dar falsos positivos.
private static final boolean RETRORAZER_FALLBACK_PHONE = false;

// Duración (ms) de la vibración sostenida antes de re-emitirse. libretro
// re-llama a doVibrate periódicamente mientras el motor deba seguir activo.
private static final long RETRORAZER_SUSTAIN_MS = 260L;
private static final long RETRORAZER_ONESHOT_MS = 24L;

public void doVibrate(int id, int effect, int strength, int oneShot)
{
   // 1) strength de libretro (0..0xFFFF) -> amplitud Android (1..255)
   int amplitude = (strength <= 0) ? 0 : Math.max(1, Math.min(255, strength >> 8));
   long duration = (oneShot != 0) ? RETRORAZER_ONESHOT_MS : RETRORAZER_SUSTAIN_MS;

   // 2) Resolver el InputDevice del control
   android.view.InputDevice device = null;
   if (id >= 0)
      device = android.view.InputDevice.getDevice(id);
   if (device == null)
      device = retrorazerFindGamepadWithVibrator();

   // 3) Vibrador del control (API 31+ VibratorManager, API 26-30 getVibrator)
   android.os.Vibrator controllerVib = retrorazerControllerVibrator(device);

   if (controllerVib != null && controllerVib.hasVibrator())
   {
      if (amplitude == 0)
      {
         controllerVib.cancel();
         return;
      }
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
      {
         controllerVib.vibrate(
            android.os.VibrationEffect.createOneShot(duration, amplitude));
      }
      else
      {
         controllerVib.vibrate(duration);
      }
      return;
   }

   // 4) Respaldo opcional al teléfono
   if (RETRORAZER_FALLBACK_PHONE)
   {
      android.os.Vibrator phone =
         (android.os.Vibrator) getApplicationContext()
            .getSystemService(android.content.Context.VIBRATOR_SERVICE);
      if (phone != null && phone.hasVibrator())
      {
         if (amplitude == 0) { phone.cancel(); return; }
         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            phone.vibrate(android.os.VibrationEffect.createOneShot(duration, amplitude));
         else
            phone.vibrate(duration);
      }
   }
   // Si no hay vibrador de control y no hay respaldo: no hacer nada
   // (probablemente estás en el Camino B; ver docs/ANALISIS-TECNICO.md).
}

/** Devuelve el vibrador del InputDevice dado, o null si no expone ninguno. */
private android.os.Vibrator retrorazerControllerVibrator(android.view.InputDevice device)
{
   if (device == null)
      return null;
   if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
   {
      android.os.VibratorManager vm = device.getVibratorManager();
      if (vm != null)
      {
         int[] ids = vm.getVibratorIds();
         if (ids != null && ids.length > 0)
            return vm.getVibrator(ids[0]);   // primer motor del mando
      }
      return null;
   }
   // API 26-30
   return device.getVibrator();
}

/** Busca el primer mando (gamepad/joystick) conectado que tenga vibrador. */
private android.view.InputDevice retrorazerFindGamepadWithVibrator()
{
   int[] ids = android.view.InputDevice.getDeviceIds();
   for (int devId : ids)
   {
      android.view.InputDevice dev = android.view.InputDevice.getDevice(devId);
      if (dev == null)
         continue;
      int sources = dev.getSources();
      boolean isGamepad =
         (sources & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ||
         (sources & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
      if (!isGamepad)
         continue;
      android.os.Vibrator v = retrorazerControllerVibrator(dev);
      if (v != null && v.hasVibrator())
         return dev;
   }
   return null;
}
