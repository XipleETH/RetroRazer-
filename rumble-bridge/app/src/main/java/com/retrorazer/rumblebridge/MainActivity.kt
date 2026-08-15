package com.retrorazer.rumblebridge

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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

    // Camino B dirigido: disparable desde adb con
    //   am broadcast -a com.retrorazer.rumblebridge.HIDTEST -p com.retrorazer.rumblebridge \
    //       --ei iface 3 --ei rid 0 --el hold 1500 --es data ffffffffffffffffffffffffffffffff
    private val ACTION_HID_TEST = "com.retrorazer.rumblebridge.HIDTEST"
    private var pendingHidTest: Bundle? = null

    private val hidTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_HID_TEST) return
            val dev = findRazer() ?: run { log("HIDTEST: no encuentro el Razer por USB."); return }
            val b = Bundle().apply {
                putString("op", intent.getStringExtra("op") ?: "blast")
                putInt("iface", intent.getIntExtra("iface", 3))
                putInt("rid", intent.getIntExtra("rid", 0))
                putLong("hold", intent.getLongExtra("hold", 1500L))
                putInt("amp", intent.getIntExtra("amp", 20000))
                putInt("freq", intent.getIntExtra("freq", 150))
                putInt("cks", intent.getIntExtra("cks", 2))
                putString("data", intent.getStringExtra("data")
                    ?: "ffffffffffffffffffffffffffffffff")
            }
            if (!usbManager.hasPermission(dev)) {
                pendingHidTest = b
                log("HIDTEST: pidiendo permiso USB… (acepta el diálogo)")
                requestUsbPermission(dev)
                return
            }
            runHidOp(dev, b)
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted) {
                pendingHidTest = null
                pendingSweepDevice = null
                log("Permiso USB denegado.")
                return
            }
            val hid = pendingHidTest
            val sweepDev = pendingSweepDevice
            when {
                hid != null -> {
                    pendingHidTest = null
                    val dev = findRazer()
                    if (dev != null) {
                        log("Permiso USB concedido. Ejecutando HIDTEST…")
                        runHidOp(dev, hid)
                    } else log("Permiso concedido pero ya no encuentro el Razer.")
                }
                sweepDev != null -> {
                    log("Permiso USB concedido para ${sweepDev.deviceName}.")
                    runSweepAsync(sweepDev)
                }
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
        try { unregisterReceiver(hidTestReceiver) } catch (_: Exception) {}
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }
        // El disparador HID SÍ debe ser EXPORTED para poder lanzarlo desde adb.
        val hidFilter = IntentFilter(ACTION_HID_TEST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hidTestReceiver, hidFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(hidTestReceiver, hidFilter)
        }
    }

    private fun findRazer(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == ControllerInfo.RAZER_VENDOR_ID }

    /** Convierte "ffaa01" (o "ff aa 01") en bytes. */
    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filter { it.isLetterOrDigit() }
        if (clean.length < 2) return ByteArray(0)
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            out[i] = (((hi and 0xF) shl 4) or (lo and 0xF)).toByte()
        }
        return out
    }

    private fun runHidOp(dev: UsbDevice, b: Bundle) {
        val op = b.getString("op") ?: "blast"
        val iface = b.getInt("iface")
        if (op == "direct") {
            val hold = b.getLong("hold")
            log("RUMBLE DIRECTO → enable(iface#3) + stream(ep0x3), hold=${hold}ms")
            Thread {
                try {
                    val file = java.io.File(getExternalFilesDir(null), "frames.bin")
                    val frames = ArrayList<ByteArray>()
                    if (file.exists()) {
                        val all = file.readBytes()
                        var i = 0
                        while (i + 64 <= all.size) { frames.add(all.copyOfRange(i, i + 64)); i += 64 }
                    }
                    runOnUiThread { log("frames.bin: ${frames.size} frames (${file.absolutePath})") }
                    val res = rumbler.directRumble(dev, rumbler.hapticEnableSequence(), frames, hold) { line ->
                        runOnUiThread { log(line) }
                    }
                    runOnUiThread { log(res) }
                } catch (e: Exception) {
                    runOnUiThread { log("direct error: ${e.message}") }
                }
            }.start()
            return
        }
        if (op == "synth") {
            val hold = b.getLong("hold")
            val amp = b.getInt("amp")
            val freq = b.getInt("freq")
            val cks = b.getInt("cks")
            log("SYNTH → enable + onda amp=$amp freq=${freq}Hz cks=$cks hold=${hold}ms")
            Thread {
                try {
                    val res = rumbler.streamSynthRumble(
                        dev, rumbler.hapticEnableSequence(), amp, freq, cks, hold
                    ) { line -> runOnUiThread { log(line) } }
                    runOnUiThread { log(res) }
                } catch (e: Exception) { runOnUiThread { log("synth error: ${e.message}") } }
            }.start()
            return
        }
        if (op == "desc") {
            log("HID descriptor → iface#$iface")
            Thread {
                try {
                    rumbler.dumpReportDescriptor(dev, iface) { line -> runOnUiThread { log(line) } }
                } catch (e: Exception) {
                    runOnUiThread { log("descriptor error: ${e.message}") }
                }
            }.start()
            return
        }
        val rid = b.getInt("rid")
        val hold = b.getLong("hold")
        val hex = b.getString("data") ?: ""
        val payload = hexToBytes(hex)
        log("HIDTEST → iface#$iface rid=$rid hold=${hold}ms data=$hex (${payload.size}B)")
        Thread {
            try {
                rumbler.blast(dev, iface, rid, payload, hold) { line -> runOnUiThread { log(line) } }
            } catch (e: Exception) {
                runOnUiThread { log("HIDTEST error: ${e.message}") }
            }
        }.start()
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        usbManager.requestPermission(device, pi)
    }

    /** Reconstruye toda la vista con el estado actual. */
    private fun refresh() {
        container.removeAllViews()

        addTitle("RetroRazer Rumble Bridge")
        addText("Diagnóstico de rumble para RetroArch → control Razer Sensa.\n" +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        addButton("↻ Refrescar") { refresh() }
        addButton("🔬 Laboratorio háptico (audio → vibración)") {
            startActivity(Intent(this, HapticLabActivity::class.java))
        }
        addButton("🟢 Iniciar puente de rumble (servicio)") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            val svc = Intent(this, RumbleHapticService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            log("Puente de rumble INICIADO. Deja esto abierto y prueba el simulador o RetroArch.")
        }
        addButton("🔴 Detener puente de rumble") {
            stopService(Intent(this, RumbleHapticService::class.java))
            log("Puente de rumble detenido.")
        }
        addButton("🔋 Excluir de optimización de batería (evita que el puente se muera)") {
            requestBatteryExclusion()
        }
        addButton("🎮 Simular rumble entrante (prueba del puente)") {
            simulateIncomingRumble()
        }
        addButton("🔎 Volcado de vibradores (diagnóstico modo directo)") {
            log(ControllerScanner.rawDump())
        }
        addButton("📊 Ver rumble recibido de RetroArch") {
            log(
                "Broadcasts de rumble recibidos: ${RumbleHapticService.rxCount}" +
                "  | última fuerza=${RumbleHapticService.lastStrength}" +
                "  motor=${RumbleHapticService.lastEffect}" +
                "  | modo=${RumbleHapticService.lastMode}"
            )
            if (RumbleHapticService.rxCount == 0) {
                log("→ 0 recibidos: RetroArch NO está emitiendo rumble. Revisa: Puerto 1 " +
                    "= DualShock/Analog + Rumble ON en el núcleo. ¿Es el RetroArch PARCHEADO?")
            }
        }

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
        log("Solicitando permiso USB para ${device.deviceName}…")
        requestUsbPermission(device)
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

    /** Pide excluir la app de la optimización de batería (para que el servicio no muera). */
    private fun requestBatteryExclusion() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            log("Ya está excluida de la optimización de batería. 👍")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // Respaldo: abre la lista de optimización de batería
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                log("Busca 'RetroRazer' en la lista y quítale la optimización.")
            } catch (e2: Exception) {
                log("No se pudo abrir el ajuste de batería: ${e2.message}")
            }
        }
    }

    /** Envía un broadcast de rumble a nuestro propio servicio (formato de RetroArch). */
    private fun sendRumble(strength: Int, effect: Int) {
        val i = Intent(RumbleHapticService.ACTION_RUMBLE).setPackage(packageName)
        i.putExtra("s", strength)
        i.putExtra("e", effect)
        sendBroadcast(i)
    }

    /** Simula un rumble de juego (golpe fuerte que decae) para probar el puente. */
    private fun simulateIncomingRumble() {
        log("Simulando rumble entrante… (inicia el puente primero, con Nexus/Audio Haptics ON)")
        Thread {
            var s = 60000
            while (s > 800) {
                sendRumble(s, 0)
                try { Thread.sleep(60) } catch (_: InterruptedException) {}
                s = (s * 0.85).toInt()
            }
            sendRumble(0, 0)
        }.start()
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
