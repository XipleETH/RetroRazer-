package com.retrorazer.rumblebridge

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Laboratorio háptico: busca la señal de audio que hace vibrar mejor el Kishi
 * vía Audio Haptics. Empieza reproduciendo PULSOS graves (lo que más dispara el
 * háptico). El ajuste ganador se llevará luego a RetroArch.
 *
 * REQUISITOS: Razer Nexus con Audio Haptics ON (Alta), volumen de multimedia
 * arriba, Kishi conectado, y abrir esta app DESDE Nexus.
 */
class HapticLabActivity : Activity() {

    private val engine = HapticEngine()

    private var freqHz = 60
    private var ampPct = 90
    private var continuous = true   // arranca sonando para feedback inmediato

    private lateinit var freqLabel: TextView
    private lateinit var ampLabel: TextView
    private lateinit var waveBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var outBtn: Button
    private lateinit var contBtn: Button
    private lateinit var readout: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Permite que otras apps (Razer Nexus) capturen nuestro audio para
        // convertirlo en háptica.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.allowedCapturePolicy = AudioAttributes.ALLOW_CAPTURE_BY_ALL
        }

        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        col.addView(title("🔬 Laboratorio háptico"))
        col.addView(body(
            "Al abrir ya está reproduciendo PULSOS graves. Con Razer Nexus " +
            "(Audio Haptics = Alta) y el volumen arriba, busca la señal que haga " +
            "vibrar el Kishi. Prueba MODO = PULSO y RUIDO (disparan mejor que un " +
            "tono puro). Anota el ajuste que funcione y me lo pasas."
        ))

        // Prueba de audio audible
        col.addView(button("🔊 Sonido de prueba (¿lo oyes?)") {
            engine.playAudibleBeep()
        })

        // Modo de señal
        modeBtn = button("Modo: PULSO (recomendado)") {
            val next = when (engine.currentMode) {
                HapticEngine.Mode.PULSE -> HapticEngine.Mode.NOISE
                HapticEngine.Mode.NOISE -> HapticEngine.Mode.TONE
                HapticEngine.Mode.TONE -> HapticEngine.Mode.PULSE
            }
            engine.setMode(next)
            modeBtn.text = "Modo: ${modeName(next)}"
            updateLabels()
        }
        col.addView(modeBtn)

        // Frecuencia
        freqLabel = body("")
        col.addView(freqLabel)
        col.addView(seek(10, 200, freqHz) { v ->
            freqHz = v
            engine.setFreq(v.toDouble())
            updateLabels()
        })

        // Amplitud
        ampLabel = body("")
        col.addView(ampLabel)
        col.addView(seek(0, 100, ampPct) { v ->
            ampPct = v
            if (continuous) engine.setAmplitude(v / 100.0)
            updateLabels()
        })

        // Forma de onda (para PULSO/TONO)
        waveBtn = button("Forma de onda: SINE") {
            val next = when (engine.currentWave) {
                HapticEngine.Wave.SINE -> HapticEngine.Wave.SQUARE
                HapticEngine.Wave.SQUARE -> HapticEngine.Wave.TRIANGLE
                HapticEngine.Wave.TRIANGLE -> HapticEngine.Wave.SINE
            }
            engine.setWave(next)
            waveBtn.text = "Forma de onda: ${next.name}"
            updateLabels()
        }
        col.addView(waveBtn)

        // Salida MEDIA/GAME
        outBtn = button("Salida de audio: MEDIA") {
            val next = if (engine.currentOut == HapticEngine.Out.MEDIA)
                HapticEngine.Out.GAME else HapticEngine.Out.MEDIA
            engine.setOut(next)
            outBtn.text = "Salida de audio: ${next.name}"
            updateLabels()
        }
        col.addView(outBtn)

        // Reproducir ON/OFF
        contBtn = button("⏸ Reproduciendo: ON") {
            continuous = !continuous
            engine.setAmplitude(if (continuous) ampPct / 100.0 else 0.0)
            contBtn.text = if (continuous) "⏸ Reproduciendo: ON" else "▶ Reproduciendo: OFF"
        }
        col.addView(contBtn)

        // Golpe único
        col.addView(button("💥 Golpe (simular rumble PS1)") {
            engine.playRumblePattern()
        })

        col.addView(title("Ajuste actual (repórtame esto)"))
        readout = mono("")
        col.addView(readout)

        setContentView(root)
        updateLabels()
    }

    override fun onResume() {
        super.onResume()
        engine.start()
        engine.setAmplitude(if (continuous) ampPct / 100.0 else 0.0)
    }

    override fun onPause() {
        super.onPause()
        engine.stop()
    }

    private fun modeName(m: HapticEngine.Mode) = when (m) {
        HapticEngine.Mode.PULSE -> "PULSO (recomendado)"
        HapticEngine.Mode.NOISE -> "RUIDO (explosión)"
        HapticEngine.Mode.TONE -> "TONO continuo"
    }

    private fun updateLabels() {
        freqLabel.text = "Frecuencia: $freqHz Hz"
        ampLabel.text = "Amplitud: $ampPct %"
        readout.text = "modo=${engine.currentMode}   freq=$freqHz Hz   amp=$ampPct %   " +
                "onda=${engine.currentWave.name}   salida=${engine.currentOut.name}"
    }

    // ---- helpers de UI ----

    private fun seek(min: Int, max: Int, value: Int, onChange: (Int) -> Unit) =
        SeekBar(this).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = onChange(p + min)
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
        }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setAllCaps(false)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setPadding(0, dp(2), 0, dp(4))
    }

    private fun mono(t: String) = TextView(this).apply {
        text = t; textSize = 15f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(0xFF111111.toInt())
        setTextColor(0xFF33FF66.toInt())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
