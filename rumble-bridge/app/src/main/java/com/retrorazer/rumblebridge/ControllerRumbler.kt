package com.retrorazer.rumblebridge

import android.os.Build
import android.os.VibrationEffect
import android.view.InputDevice

/**
 * Vibración DIRECTA de los motores del control por la API estándar de Android
 * (VibratorManager / Vibrator). Es el camino simple: no usa audio ni Nexus.
 *
 * Detección permisiva (como los "controller tester"):
 *  - Recorre TODOS los InputDevice (NO filtra por gamepad: en el Kishi el motor
 *    puede estar en un dispositivo de entrada aparte).
 *  - NO filtra por hasVibrator() (el Kishi puede devolver false aunque vibre):
 *    basta que VibratorManager reporte ids de vibrador, o que exista el Vibrator
 *    legacy.
 */
class ControllerRumbler {

    /** true si hay algún dispositivo con motor direccionable. */
    fun available(): Boolean = findDeviceId() != null

    /** Nombre del dispositivo con motor (para mostrarlo), o null. */
    fun deviceName(): String? {
        val id = findDeviceId() ?: return null
        return InputDevice.getDevice(id)?.name
    }

    private fun hasVibrator(dev: InputDevice): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = dev.vibratorManager
            if (vm != null && vm.vibratorIds.isNotEmpty()) return true
        }
        @Suppress("DEPRECATION")
        val v = dev.vibrator
        return v != null && v.hasVibrator()
    }

    private fun findDeviceId(): Int? {
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (hasVibrator(dev)) return id
        }
        return null
    }

    /**
     * Vibra un motor. effect: 0 = fuerte (STRONG), 1 = débil (WEAK).
     * amplitude 1..255, durationMs. Devuelve true si se envió al dispositivo.
     */
    fun vibrate(effect: Int, amplitude: Int, durationMs: Long): Boolean {
        val id = findDeviceId() ?: return false
        val dev = InputDevice.getDevice(id) ?: return false
        val amp = amplitude.coerceIn(1, 255)
        val eff = VibrationEffect.createOneShot(durationMs, amp)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = dev.vibratorManager
            if (vm != null) {
                val ids = vm.vibratorIds
                if (ids.isNotEmpty()) {
                    // Por motor: fuerte -> primer motor, débil -> segundo (si existe).
                    val target = if (effect == 1 && ids.size > 1) ids[1] else ids[0]
                    vm.getVibrator(target).vibrate(eff)
                    return true
                }
            }
        }

        @Suppress("DEPRECATION")
        val v = dev.vibrator
        if (v != null) {
            v.vibrate(eff)
            return true
        }
        return false
    }

    /** Detiene los motores. */
    fun cancel() {
        val id = findDeviceId() ?: return
        val dev = InputDevice.getDevice(id) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = dev.vibratorManager
            if (vm != null && vm.vibratorIds.isNotEmpty()) { vm.cancel(); return }
        }
        @Suppress("DEPRECATION")
        dev.vibrator?.cancel()
    }
}
