package com.retrorazer.rumblebridge

import android.app.Activity
import android.graphics.Typeface
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
 * Laboratorio háptico: genera tonos graves por audio para encontrar el ajuste
 * (frecuencia / forma de onda / amplitud / salida) que hace vibrar mejor los
 * motores del Kishi vía Audio Haptics. El ajuste ganador se llevará luego a
 * RetroArch.
 *
 * REQUISITOS para sentir algo:
 *  - Razer Nexus con Audio Haptics ENCENDIDO (sensibilidad Alta).
 *  - Volumen de multimedia arriba.
 *  - Kishi V2 Pro conectado.
 */
class HapticLabActivity : Activity() {

    private val engine = HapticEngine()

    private var freqHz = 50
    private var ampPct = 80
    private var continuous = false

    private lateinit var freqLabel: TextView
    private lateinit var ampLabel: TextView
    private lateinit var waveBtn: Button
    private lateinit var outBtn: Button
    private lateinit var contBtn: Button
    private lateinit var readout: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(col, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        col.addView(title("🔬 Laboratorio háptico"))
        col.addView(body(
            "Genera un tono grave por audio. Con Razer Nexus (Audio Haptics = Alta) " +
            "y el volumen de multimedia arriba, ajusta hasta que el Kishi vibre fuerte " +
            "y limpio. Anota el ajuste ganador y me lo pasas."
        ))

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

        // Forma de onda
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

        // Salida MEDIA/GAME (por si el audio-háptico solo capta una)
        outBtn = button("Salida de audio: MEDIA") {
            val next = if (engine.currentOut == HapticEngine.Out.MEDIA)
                HapticEngine.Out.GAME else HapticEngine.Out.MEDIA
            engine.setOut(next)
            outBtn.text = "Salida de audio: ${next.name}"
            updateLabels()
        }
        col.addView(outBtn)

        // Tono continuo ON/OFF
        contBtn = button("▶ Tono continuo: OFF") {
            continuous = !continuous
            engine.setAmplitude(if (continuous) ampPct / 100.0 else 0.0)
            contBtn.text = if (continuous) "⏸ Tono continuo: ON" else "▶ Tono continuo: OFF"
        }
        col.addView(contBtn)

        // Golpe / simular rumble
        col.addView(button("💥 Golpe (simular rumble PS1)") {
            engine.playRumblePattern()
        })

        // Mantener para vibrar (pulsar y sostener)
        col.addView(holdButton())

        col.addView(title("Ajuste actual (repórtame esto)"))
        readout = mono("")
        col.addView(readout)

        setContentView(root)
        updateLabels()
    }

    override fun onResume() {
        super.onResume()
        engine.start()
        if (continuous) engine.setAmplitude(ampPct / 100.0)
    }

    override fun onPause() {
        super.onPause()
        engine.stop()
    }

    private fun updateLabels() {
        freqLabel.text = "Frecuencia: $freqHz Hz"
        ampLabel.text = "Amplitud: $ampPct %"
        readout.text = "freq=$freqHz Hz   amp=$ampPct %   onda=${engine.currentWave.name}   " +
                "salida=${engine.currentOut.name}"
    }

    // ---- helpers de UI ----

    private fun holdButton(): Button = Button(this).apply {
        text = "👉 Mantener pulsado para vibrar"
        setAllCaps(false)
        gravity = Gravity.CENTER
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    engine.setAmplitude(ampPct / 100.0); true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!continuous) engine.setAmplitude(0.0)
                    v.performClick(); true
                }
                else -> false
            }
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(64)).apply { topMargin = dp(8) }
    }

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
