package com.retrorazer.rumblebridge

import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice

/**
 * Información resumida de un control de entrada (InputDevice) y de si expone
 * un vibrador ESTÁNDAR a Android. Esto es lo que decide Camino A vs Camino B.
 */
data class ControllerInfo(
    val deviceId: Int,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val isGamepad: Boolean,
    val hasStandardVibrator: Boolean,
    val vibratorIds: List<Int>
) {
    val isRazer: Boolean get() = vendorId == RAZER_VENDOR_ID

    fun describe(): String = buildString {
        appendLine("• $name")
        appendLine("    deviceId=$deviceId  VID=0x${vendorId.toString(16).padStart(4, '0')}" +
                "  PID=0x${productId.toString(16).padStart(4, '0')}${if (isRazer) "  (RAZER)" else ""}")
        appendLine("    gamepad/joystick: ${yn(isGamepad)}")
        append("    vibrador estándar (getVibrator/VibratorManager): ${yn(hasStandardVibrator)}")
        if (hasStandardVibrator) append("  motores=$vibratorIds")
    }

    private fun yn(b: Boolean) = if (b) "SÍ" else "NO"

    companion object {
        const val RAZER_VENDOR_ID = 0x1532
    }
}

object ControllerScanner {

    /** Recorre todos los InputDevice conectados y resume su capacidad de vibrar. */
    fun scan(): List<ControllerInfo> {
        val result = ArrayList<ControllerInfo>()
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            val sources = dev.sources
            val isGamepad =
                (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

            val ids = vibratorIdsOf(dev)
            result.add(
                ControllerInfo(
                    deviceId = id,
                    name = dev.name ?: "(sin nombre)",
                    vendorId = dev.vendorId,
                    productId = dev.productId,
                    isGamepad = isGamepad,
                    hasStandardVibrator = ids.isNotEmpty(),
                    vibratorIds = ids
                )
            )
        }
        return result
    }

    /** Volcado CRUDO de todos los InputDevice y sus vibradores (para diagnóstico). */
    fun rawDump(): String = buildString {
        val ids = InputDevice.getDeviceIds()
        appendLine("InputDevices: ${ids.size}")
        for (id in ids) {
            val dev = InputDevice.getDevice(id) ?: continue
            appendLine("• id=$id  '${dev.name}'  vid=0x${dev.vendorId.toString(16)} pid=0x${dev.productId.toString(16)}  sources=0x${dev.sources.toString(16)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = dev.vibratorManager
                val vids = vm?.vibratorIds?.toList()
                appendLine("    vibratorManager ids=$vids")
                vids?.forEach { append("      vib[$it].hasVibrator=${vm.getVibrator(it).hasVibrator()}  ") }
                if (!vids.isNullOrEmpty()) appendLine()
            }
            @Suppress("DEPRECATION")
            val v = dev.vibrator
            appendLine("    legacy getVibrator: ${if (v != null) "hasVibrator=${v.hasVibrator()}" else "null"}")
        }
    }

    /** Ids de motores que Android publica para el control, o vacío si ninguno. */
    private fun vibratorIdsOf(dev: InputDevice): List<Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = dev.vibratorManager ?: return emptyList()
            // NO filtramos por hasVibrator(): algunos controles (Kishi) exponen
            // motores por VibratorManager aunque hasVibrator() sea false.
            return vm.vibratorIds.toList()
        }
        @Suppress("DEPRECATION")
        val v = dev.vibrator
        return if (v != null && v.hasVibrator()) listOf(0) else emptyList()
    }

    /**
     * Hace vibrar el vibrador ESTÁNDAR del control indicado.
     * Devuelve true si se emitió a un vibrador real del control.
     *
     * @param amplitude 1..255 (intensidad)
     * @param durationMs duración del pulso
     */
    fun testVibrate(deviceId: Int, amplitude: Int, durationMs: Long): Boolean {
        val dev = InputDevice.getDevice(deviceId) ?: return false
        val amp = amplitude.coerceIn(1, 255)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm: VibratorManager = dev.vibratorManager ?: return false
            if (vm.vibratorIds.isEmpty()) return false
            val effect = VibrationEffect.createOneShot(durationMs, amp)
            // Emite a todos los motores del mando a la vez
            val combined = CombinedVibration.createParallel(effect)
            vm.vibrate(combined)
            return true
        }

        @Suppress("DEPRECATION")
        val v: Vibrator = dev.vibrator ?: return false
        if (!v.hasVibrator()) return false
        v.vibrate(VibrationEffect.createOneShot(durationMs, amp))
        return true
    }
}
