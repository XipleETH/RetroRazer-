package com.retrorazer.rumblebridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.sin

/**
 * Motor háptico por AUDIO. Genera un tono grave continuo por AudioTrack cuya
 * frecuencia/amplitud/forma se pueden cambiar en vivo. El sistema Audio Haptics
 * del Kishi (con Razer Nexus activo) convierte ese tono en vibración de los
 * motores.
 *
 * ESTE es el componente que luego irá dentro de RetroArch: el comando de rumble
 * del juego (fuerza 0..1) se mapea a la amplitud de este tono.
 */
class HapticEngine {

    enum class Wave { SINE, SQUARE, TRIANGLE }
    enum class Out { MEDIA, GAME }

    private val sampleRate = 48000

    @Volatile private var freq = 50.0        // Hz
    @Volatile private var amp = 0.0          // 0..1 (amplitud actual del tono)
    @Volatile private var wave = Wave.SINE
    @Volatile private var out = Out.MEDIA
    @Volatile private var running = false

    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var phase = 0.0

    val currentFreq get() = freq
    val currentWave get() = wave
    val currentOut get() = out
    val isRunning get() = running

    /** Arranca el AudioTrack y el hilo generador (idempotente). */
    @Synchronized
    fun start() {
        if (running) return
        running = true

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate / 5) // ~200 ms de colchón

        val usage = if (out == Out.GAME) AudioAttributes.USAGE_GAME
                    else AudioAttributes.USAGE_MEDIA

        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        at.setVolume(1.0f)
        at.play()
        track = at

        val t = Thread {
            val buf = ShortArray(1024)
            while (running) {
                val f = freq
                val a = amp
                val w = wave
                val inc = 2.0 * PI * f / sampleRate
                for (i in buf.indices) {
                    val s = when (w) {
                        Wave.SINE -> sin(phase)
                        Wave.SQUARE -> if (sin(phase) >= 0.0) 1.0 else -1.0
                        Wave.TRIANGLE -> (2.0 / PI) * asin(sin(phase))
                    }
                    phase += inc
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                    buf[i] = (s * a * Short.MAX_VALUE).toInt().toShort()
                }
                at.write(buf, 0, buf.size)
            }
            try { at.stop() } catch (_: Exception) {}
            at.release()
        }
        thread = t
        t.start()
    }

    /** Cambia frecuencia (Hz), amplitud (0..1) y forma de onda en vivo. */
    fun setParams(freqHz: Double, amplitude: Double, waveform: Wave) {
        freq = freqHz.coerceIn(10.0, 300.0)
        amp = amplitude.coerceIn(0.0, 1.0)
        wave = waveform
    }

    /** Ajusta solo la amplitud (esto es lo que hará el rumble del juego). */
    fun setAmplitude(a: Double) { amp = a.coerceIn(0.0, 1.0) }

    fun setFreq(f: Double) { freq = f.coerceIn(10.0, 300.0) }
    fun setWave(w: Wave) { wave = w }

    /** Cambia la ruta de salida (MEDIA/GAME); reinicia el AudioTrack. */
    @Synchronized
    fun setOut(o: Out) {
        if (o == out) return
        val wasRunning = running
        stop()
        out = o
        if (wasRunning) start()
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { thread?.join(300) } catch (_: Exception) {}
        thread = null
        track = null
        amp = 0.0
    }

    /**
     * Simula un rumble tipo PS1: golpe fuerte + cola que decae. Sirve para
     * sentir cómo respondería a un evento de juego. Requiere start() previo.
     */
    fun playRumblePattern() {
        Thread {
            if (!running) return@Thread
            amp = 1.0
            sleep(170)
            var a = 0.9
            while (a > 0.05 && running) {
                amp = a
                a *= 0.82
                sleep(55)
            }
            amp = 0.0
        }.start()
    }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
}
