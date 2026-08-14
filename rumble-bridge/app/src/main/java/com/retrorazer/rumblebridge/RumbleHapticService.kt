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
import android.os.Build
import android.os.IBinder

/**
 * Servicio en primer plano que es la mitad "receptora" del puente de rumble.
 *
 * RetroArch (parcheado) emite un broadcast con la fuerza del rumble del juego;
 * aquí lo recibimos y lo convertimos en vibración usando HapticEngine (que el
 * Audio Haptics del Kishi transforma en movimiento de los motores).
 *
 * Broadcast esperado:
 *   acción : com.retrorazer.rumblebridge.RUMBLE
 *   extra  : "s" (Int 0..65535) fuerza del motor
 *   extra  : "e" (Int) 0 = motor fuerte (STRONG), 1 = motor débil (WEAK)
 */
class RumbleHapticService : Service() {

    private val engine = HapticEngine()

    @Volatile private var strong = 0.0
    @Volatile private var weak = 0.0

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RUMBLE) return
            val s = intent.getIntExtra("s", 0).coerceIn(0, 65535) / 65535.0
            val e = intent.getIntExtra("e", 0)
            if (e == 1) weak = s else strong = s
            // Un solo motor háptico: combinamos ambos motores del DualShock.
            engine.setAmplitude(maxOf(strong, weak))
        }
    }

    override fun onCreate() {
        super.onCreate()

        engine.setMode(HapticEngine.Mode.PULSE)
        engine.setFreq(DEFAULT_FREQ)
        engine.setWave(HapticEngine.Wave.SINE)
        engine.start()
        engine.setAmplitude(0.0)

        val filter = IntentFilter(ACTION_RUMBLE)
        // Debe ser EXPORTED: el broadcast viene de otra app (RetroArch).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }

        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        engine.stop()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        // minSdk 26 (O): NotificationChannel y Notification.Builder(ctx, channel) siempre disponibles.
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Puente de rumble", NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)
        nm.createNotificationChannel(channel)

        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("RetroRazer — puente de rumble activo")
            .setContentText("Convirtiendo el rumble del juego en vibración del Kishi")
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
        private const val CHANNEL_ID = "rumble_bridge"
        private const val NOTIF_ID = 1
        private const val DEFAULT_FREQ = 55.0
    }
}
