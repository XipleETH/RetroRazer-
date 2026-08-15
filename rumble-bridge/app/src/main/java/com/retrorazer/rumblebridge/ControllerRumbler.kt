package com.retrorazer.rumblebridge

import android.os.Build
import android.os.VibrationEffect
import android.view.InputDevice

/**
 * Vibración DIRECTA de los motores del control por la API estándar de Android
 * (VibratorManager / Vibrator). Es el camino simple: no usa audio ni Nexus.
 *
 * Nota: NO filtramos por hasVibrator(), porque algunos controles (Kishi) exponen
 * motores por VibratorManager aunque hasVibrator() devuelva false; probamos a
 * vibrar directamente los ids de vibrador que reporte el mando.
 */
class ControllerRumbler {

    /** true si hay un mando conectado con al menos un motor direccionable. */
    fun available(): Boolean = findDeviceId() != null

    private fun findDeviceId(): Int? {
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            val s = dev.sources
            val isPad = (s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!isPad) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = dev.vibratorManager
                if (vm != null && vm.vibratorIds.isNotEmpty()) return id
            } else {
                @Suppress("DEPRECATION")
                val v = dev.vibrator
                if (v != null && v.hasVibrator()) return id
            }
        }
        return null
    }

    /** Nombre del mando con motores (para mostrarlo), o null. */
    fun deviceName(): String? {
        val id = findDeviceId() ?: return null
        return InputDevice.getDevice(id)?.name
    }

    /**
     * Vibra un motor. effect: 0 = fuerte (STRONG), 1 = débil (WEAK).
     * amplitude 1..255, durationMs. Devuelve true si se envió al control.
     */
    fun vibrate(effect: Int, amplitude: Int, durationMs: Long): Boolean {
        val id = findDeviceId() ?: return false
        val dev = InputDevice.getDevice(id) ?: return false
        val amp = amplitude.coerceIn(1, 255)
        val eff = VibrationEffect.createOneShot(durationMs, amp)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = dev.vibratorManager ?: return false
            val ids = vm.vibratorIds
            if (ids.isEmpty()) return false
            // Por motor: fuerte -> primer motor, débil -> segundo (si existe).
            val target = if (effect == 1 && ids.size > 1) ids[1] else ids[0]
            vm.getVibrator(target).vibrate(eff)
            return true
        }

        @Suppress("DEPRECATION")
        val v = dev.vibrator ?: return false
        v.vibrate(eff)
        return true
    }

    /** Detiene todos los motores del control. */
    fun cancel() {
        val id = findDeviceId() ?: return
        val dev = InputDevice.getDevice(id) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dev.vibratorManager?.cancel()
        } else {
            @Suppress("DEPRECATION")
            dev.vibrator?.cancel()
        }
    }
}
