package com.retrorazer.rumblebridge

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pantalla única de diagnóstico:
 *  - Enumera controles y dice si exponen vibrador estándar (Camino A vs B).
 *  - Permite PROBAR la vibración estándar de cada control.
 *  - Sección USB para inspeccionar y (Camino B) descubrir el reporte HID de rumble.
 */
class MainActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var logView: TextView
    private lateinit var usbManager: UsbManager
    private lateinit var rumbler: UsbHidRumbler

    private val ACTION_USB_PERMISSION = "com.retrorazer.rumblebridge.USB_PERMISSION"
    private var pendingSweepDevice: UsbDevice? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = pendingSweepDevice
            if (granted && device != null) {
                log("Permiso USB concedido para ${device.deviceName}.")
                runSweepAsync(device)
            } else {
                log("Permiso USB denegado.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        rumbler = UsbHidRumbler(usbManager)

        val root = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(container, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        logView = TextView(this).apply {
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        setContentView(root)
        registerUsbReceiver()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
    }

    /** Reconstruye toda la vista con el estado actual. */
    private fun refresh() {
        container.removeAllViews()

        addTitle("RetroRazer Rumble Bridge")
        addText("Diagnóstico de rumble para RetroArch → control Razer Sensa.\n" +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        addButton("↻ Refrescar") { refresh() }

        // --- Controles de entrada (Camino A) ---
        addTitle("1) Controles detectados (vibrador estándar)")
        val controllers = ControllerScanner.scan()
        if (controllers.isEmpty()) {
            addText("No hay controles conectados. Conecta el Razer y pulsa Refrescar.")
        } else {
            var anyStd = false
            for (c in controllers) {
                addMono(c.describe())
                if (c.hasStandardVibrator) {
                    anyStd = true
                    addButton("▶ Probar motores de: ${c.name}") {
                        val ok = ControllerScanner.testVibrate(c.deviceId, amplitude = 255, durationMs = 600)
                        log(if (ok) "Enviada vibración estándar a '${c.name}'. ¿La sentiste?"
                            else "No se pudo vibrar '${c.name}' (sin vibrador estándar).")
                    }
                }
            }
            addText(
                if (anyStd)
                    "→ CAMINO A: al menos un control expone vibrador estándar. Si 'Probar' " +
                    "mueve los motores, el arreglo es config de RetroArch + parche (ver docs)."
                else
                    "→ CAMINO B probable: ningún control expone vibrador estándar. Usa la " +
                    "sección USB de abajo para descubrir el reporte HID de Razer."
            )
        }

        // --- USB (Camino B / descubrimiento) ---
        addTitle("2) Dispositivos USB (Camino B / descubrimiento HID)")
        val usbDevices = usbManager.deviceList.values.toList()
        if (usbDevices.isEmpty()) {
            addText("No hay dispositivos USB. (Algunos controles se ven solo como InputDevice, no como USB host.)")
        } else {
            for (d in usbDevices) {
                val razer = if (d.vendorId == ControllerInfo.RAZER_VENDOR_ID) "  (RAZER)" else ""
                addMono("USB ${d.deviceName}  VID=0x${d.vendorId.toString(16)}  PID=0x${d.productId.toString(16)}$razer")
                addButton("🔍 Ver detalles HID: ${d.productName ?: d.deviceName}") {
                    log(rumbler.describe(d))
                }
                addButton("⚡ Barrido de descubrimiento de rumble (${d.productName ?: d.deviceName})") {
                    startSweep(d)
                }
            }
        }

        // --- Log ---
        addTitle("Registro")
        addButton("Limpiar registro") { logView.text = "" }
        // logView es único y persiste entre refrescos: lo despegamos de su
        // contenedor anterior antes de re-insertarlo.
        (logView.parent as? ViewGroup)?.removeView(logView)
        val logBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
        }
        logView.setTextColor(0xFF33FF66.toInt())
        logBox.addView(logView, LinearLayout.LayoutParams(MATCH_PARENT, dp(240)))
        container.addView(logBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun startSweep(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            runSweepAsync(device)
            return
        }
        pendingSweepDevice = device
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        log("Solicitando permiso USB para ${device.deviceName}…")
        usbManager.requestPermission(device, pi)
    }

    private fun runSweepAsync(device: UsbDevice) {
        log("Iniciando barrido en segundo plano. Observa el control…")
        Thread {
            try {
                rumbler.discoverySweep(device) { line -> runOnUiThread { log(line) } }
            } catch (e: Exception) {
                runOnUiThread { log("Error en barrido: ${e.message}") }
            }
        }.start()
    }

    // ---- helpers de UI ----

    private fun log(msg: String) {
        logView.append(msg + "\n")
    }

    private fun addTitle(t: String) {
        container.addView(TextView(this).apply {
            text = t
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        })
    }

    private fun addText(t: String) {
        container.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun addMono(t: String) {
        container.addView(TextView(this).apply {
            text = t
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, dp(2), 0, dp(2))
        })
    }

    private fun addButton(label: String, onClick: () -> Unit) {
        container.addView(Button(this).apply {
            text = label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setAllCaps(false)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
