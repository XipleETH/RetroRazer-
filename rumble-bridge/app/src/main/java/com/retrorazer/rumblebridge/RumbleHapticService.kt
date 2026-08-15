package com.retrorazer.rumblebridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import kotlin.math.sqrt

/**
 * Servicio en primer plano: recibe el rumble real de RetroArch (broadcast) y lo
 * convierte en vibración del control.
 *
 * Dos modos, elegidos automáticamente al arrancar:
 *   - DIRECTO: si el control expone motores por la API estándar (VibratorManager),
 *     vibra los motores directamente. Sin audio, sin Nexus. (Preferido.)
 *   - AUDIO: si no hay vibrador estándar, genera un pulso de audio que el Audio
 *     Haptics del Kishi convierte en vibración (requiere Nexus). (Respaldo.)
 *
 * Broadcast esperado:
 *   acción : com.retrorazer.rumblebridge.RUMBLE
 *   extra  : "s" (Int 0..65535) fuerza del motor
 *   extra  : "e" (Int) 0 = motor fuerte (STRONG), 1 = motor débil (WEAK)
 */
class RumbleHapticService : Service() {

    private val engine = HapticEngine()          // respaldo por audio
    private val rumbler = ControllerRumbler()     // vibración directa (vibrador estándar)
    private var directMode = false

    // Modo SENSA DIRECTO: rumble directo del Kishi V2 Pro por USB (protocolo
    // Interhaptics descifrado). Prioritario si el Kishi está conectado + con permiso.
    private lateinit var usbManager: UsbManager
    private lateinit var sensa: UsbHidRumbler
    private var sensaMode = false

    @Volatile private var strongS = 0            // 0..65535
    @Volatile private var weakS = 0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_TUNE) {
                if (sensaMode) {
                    sensa.setSensaParams(intent.getIntExtra("freq", 0), intent.getIntExtra("fps", 0))
                    lastMode = "SENSA DIRECTO (${sensa.sensaInfo()})"
                }
                return
            }
            if (intent.action != ACTION_RUMBLE) return
            val s = intent.getIntExtra("s", 0).coerceIn(0, 65535)
            val e = intent.getIntExtra("e", 0)
            if (e == 1) weakS = s else strongS = s

            // Diagnóstico: contamos cada broadcast recibido de RetroArch.
            rxCount++
            lastStrength = s
            lastEffect = e

            when {
                sensaMode -> sensa.setSensaAmp(strengthToAmp(maxOf(strongS, weakS)))
                directMode -> {
                    if (s <= 0) {
                        if (strongS == 0 && weakS == 0) rumbler.cancel()
                    } else {
                        rumbler.vibrate(e, amp255(s), SUSTAIN_MS)
                    }
                }
                else -> engine.setAmplitude(amp255(maxOf(strongS, weakS)) / 255.0)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        sensa = UsbHidRumbler(usbManager)
        val kishi = usbManager.deviceList.values.firstOrNull {
            it.vendorId == ControllerInfo.RAZER_VENDOR_ID && usbManager.hasPermission(it)
        }

        when {
            kishi != null -> {
                sensaMode = true
                lastMode = "SENSA DIRECTO (Kishi 0x${kishi.productId.toString(16)})"
                sensa.startSensaStream(kishi, sensa.hapticEnableSequence(), 130) {}
            }
            rumbler.available() -> {
                directMode = true
                lastMode = "DIRECTO (${rumbler.deviceName() ?: "?"})"
            }
            else -> {
                lastMode = "AUDIO"
                engine.setMode(HapticEngine.Mode.PULSE)
                engine.setFreq(55.0)
                engine.start()
                engine.setAmplitude(0.0)
            }
        }

        val filter = IntentFilter(ACTION_RUMBLE)
        filter.addAction(ACTION_TUNE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }

        startForegroundCompat()
    }

    /**
     * Mapea la fuerza del juego (0..65535) a amplitud 1..255 con una curva de
     * refuerzo: incluso rumbles débiles (p.ej. 11%) se sienten fuertes.
     * s=0 -> 0. s>0 -> entre ~115 y 255.
     */
    private fun amp255(s: Int): Int {
        if (s <= 0) return 0
        val norm = (s / 65535.0).coerceIn(0.0, 1.0)
        val boosted = 0.45 + 0.55 * sqrt(norm)   // 0.45..1.0
        return (boosted * 255.0).toInt().coerceIn(1, 255)
    }

    /** Mapea la fuerza del juego (0..65535) a amplitud de onda Sensa (0..~28000). */
    private fun strengthToAmp(s: Int): Int {
        if (s <= 0) return 0
        val norm = (s / 65535.0).coerceIn(0.0, 1.0)
        return ((0.45 + 0.55 * sqrt(norm)) * 28000.0).toInt().coerceIn(1, 28000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        when {
            sensaMode -> sensa.stopSensaStream()
            directMode -> rumbler.cancel()
            else -> engine.stop()
        }
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Puente de rumble", NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)
        nm.createNotificationChannel(channel)

        val modeText = when {
            sensaMode -> "Modo SENSA DIRECTO — Kishi por USB, sin Nexus"
            directMode -> "Modo DIRECTO: ${rumbler.deviceName() ?: "control"} — sin Nexus"
            else -> "Modo AUDIO (HyperSense) — requiere Nexus"
        }

        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RetroRazer — puente de rumble activo")
            .setContentText(modeText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        const val ACTION_RUMBLE = "com.retrorazer.rumblebridge.RUMBLE"
        const val ACTION_TUNE = "com.retrorazer.rumblebridge.TUNE"
        private const val CHANNEL_ID = "rumble_bridge"
        private const val NOTIF_ID = 1
        private const val SUSTAIN_MS = 300L

        // Diagnóstico visible desde MainActivity
        @Volatile var rxCount = 0
        @Volatile var lastStrength = 0
        @Volatile var lastEffect = -1
        @Volatile var lastMode = "—"
    }
}
