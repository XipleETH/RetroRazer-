package com.retrorazer.rumblebridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.sin

/**
 * Motor háptico por AUDIO. Genera señal por AudioTrack cuya forma se ajusta en
 * vivo. El Audio Haptics del Kishi (con Razer Nexus) la convierte en vibración.
 *
 * IMPORTANTE: el audio-háptico de Razer reacciona sobre todo a GOLPES graves y
 * TRANSITORIOS (como explosiones/disparos), no a un tono puro sostenido. Por eso
 * hay 3 modos:
 *   - TONE : tono continuo (referencia; puede NO disparar el háptico).
 *   - PULSE: golpes graves repetidos con ataque brusco (lo que más se parece a un
 *            rumble y mejor dispara el háptico).
 *   - NOISE: ráfagas de ruido de banda ancha (tipo "explosión").
 *
 * setAmplitude(0..1) es el gancho que luego usará el rumble real del juego.
 */
class HapticEngine {

    enum class Wave { SINE, SQUARE, TRIANGLE }
    enum class Out { MEDIA, GAME }
    enum class Mode { TONE, PULSE, NOISE }

    private val sampleRate = 48000

    @Volatile private var freq = 60.0
    @Volatile private var amp = 0.0
    @Volatile private var wave = Wave.SINE
    @Volatile private var out = Out.MEDIA
    @Volatile private var mode = Mode.PULSE
    @Volatile private var running = false

    // Override temporal para el "beep" audible de prueba
    @Volatile private var beepUntil = 0L
    @Volatile private var beepSamples = 0L

    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var phase = 0.0
    private var n = 0L
    private val rnd = Random()

    val currentFreq get() = freq
    val currentWave get() = wave
    val currentOut get() = out
    val currentMode get() = mode
    val isRunning get() = running

    @Synchronized
    fun start() {
        if (running) return
        running = true

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, sampleRate / 5)

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
            val pulsePeriod = (sampleRate * 0.12).toLong()   // golpe cada 120 ms (~8/s)
            val attack = sampleRate * 0.005                   // 5 ms de ataque
            val decayTau = sampleRate * 0.040                 // cola ~40 ms
            while (running) {
                val a = amp
                val w = wave
                val m = mode
                val inc = 2.0 * PI * freq / sampleRate
                for (i in buf.indices) {
                    val base = when (w) {
                        Wave.SINE -> sin(phase)
                        Wave.SQUARE -> if (sin(phase) >= 0.0) 1.0 else -1.0
                        Wave.TRIANGLE -> (2.0 / PI) * asin(sin(phase))
                    }
                    phase += inc
                    if (phase > 2.0 * PI) phase -= 2.0 * PI

                    // Beep audible de prueba (tiene prioridad): tono 440 Hz claro
                    val beeping = beepSamples > 0
                    val sample: Double = if (beeping) {
                        beepSamples--
                        sin(2.0 * PI * 440.0 * n / sampleRate) * 0.8
                    } else when (m) {
                        Mode.TONE -> base * a
                        Mode.PULSE -> {
                            val pos = (n % pulsePeriod).toDouble()
                            val env = if (pos < attack) pos / attack
                                      else exp(-(pos - attack) / decayTau)
                            base * a * env
                        }
                        Mode.NOISE -> {
                            val pos = (n % pulsePeriod).toDouble()
                            val env = if (pos < attack) pos / attack
                                      else exp(-(pos - attack) / decayTau)
                            (rnd.nextDouble() * 2.0 - 1.0) * a * env
                        }
                    }
                    n++
                    buf[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                }
                at.write(buf, 0, buf.size)
            }
            try { at.stop() } catch (_: Exception) {}
            at.release()
        }
        thread = t
        t.start()
    }

    fun setAmplitude(a: Double) { amp = a.coerceIn(0.0, 1.0) }
    fun setFreq(f: Double) { freq = f.coerceIn(10.0, 300.0) }
    fun setWave(w: Wave) { wave = w }
    fun setMode(m: Mode) { mode = m }

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
        beepSamples = 0
    }

    /** Reproduce ~700 ms de un tono 440 Hz claramente audible para confirmar audio. */
    fun playAudibleBeep() {
        if (!running) return
        beepSamples = (sampleRate * 0.7).toLong()
    }

    /** Simula un rumble tipo PS1: golpe fuerte + cola que decae. */
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
