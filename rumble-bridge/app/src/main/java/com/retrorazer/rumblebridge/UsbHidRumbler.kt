package com.retrorazer.rumblebridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * Habla con el control por USB HID (Camino B).
 *
 * IMPORTANTE — honestidad técnica: el formato exacto del reporte de rumble del
 * Razer Kishi Ultra NO es público. Esta clase NO afirma conocerlo: ofrece las
 * primitivas para (a) inspeccionar el dispositivo y (b) ENVIAR reportes de salida
 * candidatos para DESCUBRIR, con tu hardware, cuál mueve los motores. Cuando uno
 * funcione, ese patrón es el protocolo, y se documenta en docs/ANALISIS-TECNICO.md.
 */
class UsbHidRumbler(private val usbManager: UsbManager) {

    data class Endpoints(
        val iface: UsbInterface,
        val outEndpoint: UsbEndpoint?   // interrupt OUT si existe
    )

    /** Descripción legible de un dispositivo USB y sus interfaces/endpoints. */
    fun describe(device: UsbDevice): String = buildString {
        appendLine("USB: ${device.deviceName}")
        appendLine("  VID=0x${device.vendorId.toString(16).padStart(4, '0')}" +
                "  PID=0x${device.productId.toString(16).padStart(4, '0')}" +
                if (device.vendorId == ControllerInfo.RAZER_VENDOR_ID) "  (RAZER)" else "")
        appendLine("  producto=${device.productName ?: "?"}  fabricante=${device.manufacturerName ?: "?"}")
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            val cls = if (itf.interfaceClass == UsbConstants.USB_CLASS_HID) "HID" else "clase ${itf.interfaceClass}"
            appendLine("  iface #${itf.id}: $cls  endpoints=${itf.endpointCount}")
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_OUT) "OUT" else "IN"
                val type = when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                    UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                    UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                    else -> "tipo${ep.type}"
                }
                appendLine("      ep addr=0x${ep.address.toString(16)} $dir $type maxPkt=${ep.maxPacketSize}")
            }
        }
    }

    /** Primera interfaz HID del dispositivo y su endpoint interrupt OUT (si hay). */
    fun findHidEndpoints(device: UsbDevice): Endpoints? {
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            var out: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_OUT &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    out = ep
                }
            }
            return Endpoints(itf, out)
        }
        return null
    }

    /**
     * Envía un reporte de salida HID crudo. Intenta primero el endpoint
     * interrupt OUT; si no existe, usa SET_REPORT por el endpoint de control.
     * @return número de bytes enviados (>=0) o -1 si falló.
     */
    fun sendOutputReport(device: UsbDevice, reportId: Int, payload: ByteArray, log: (String) -> Unit): Int {
        val eps = findHidEndpoints(device) ?: run {
            log("No se encontró interfaz HID."); return -1
        }
        val conn: UsbDeviceConnection = usbManager.openDevice(device) ?: run {
            log("No se pudo abrir el dispositivo (¿permiso concedido?)."); return -1
        }
        try {
            if (!conn.claimInterface(eps.iface, true)) {
                log("No se pudo reclamar la interfaz HID #${eps.iface.id}."); return -1
            }
            // Report ID como primer byte solo si es distinto de 0
            val data = if (reportId != 0) byteArrayOf(reportId.toByte(), *payload) else payload

            val out = eps.outEndpoint
            if (out != null) {
                val n = conn.bulkTransfer(out, data, data.size, 250)
                log("interrupt OUT reportId=$reportId len=${data.size} -> $n")
                if (n >= 0) return n
                // si falla, cae a control transfer
            }
            // SET_REPORT (clase, interfaz): bmRequestType=0x21, bRequest=0x09,
            // wValue=(reportType<<8)|reportId con reportType=2 (Output)
            val requestType = 0x21
            val request = 0x09
            val value = (0x02 shl 8) or (reportId and 0xFF)
            val index = eps.iface.id
            val n = conn.controlTransfer(requestType, request, value, index, data, data.size, 250)
            log("SET_REPORT reportId=$reportId len=${data.size} -> $n")
            return n
        } finally {
            conn.releaseInterface(eps.iface)
            conn.close()
        }
    }

    /**
     * Barrido de descubrimiento: prueba reportes de salida candidatos con distintos
     * report IDs y patrones de "motores al máximo". Registra el resultado de cada uno.
     * Observa el control: si vibra en alguno, ANOTA ese reportId + patrón.
     *
     * No es fuerza bruta agresiva: usa un conjunto pequeño y ordenado de candidatos
     * inspirados en cómo suelen mapearse dos motores (grande/pequeño) en HID.
     */
    fun discoverySweep(device: UsbDevice, log: (String) -> Unit) {
        log("== Inicio del barrido de descubrimiento HID ==")
        log(describe(device))
        // Patrones candidatos: dos bytes de intensidad (motor grande, pequeño) al máximo,
        // con relleno. Se prueban con varios report IDs frecuentes.
        val full = 0xFF.toByte()
        val patterns: List<ByteArray> = listOf(
            byteArrayOf(full, full),                                  // 2 bytes
            byteArrayOf(full, full, 0, 0, 0, 0, 0, 0),               // 8 bytes
            byteArrayOf(0x00, full, full, 0, 0, 0, 0, 0),            // desplazado 1
            byteArrayOf(full, 0x00, full, 0x00, 0, 0, 0, 0)          // grande/pequeño separados
        )
        val reportIds = intArrayOf(0x00, 0x01, 0x02, 0x03, 0x05)
        for (rid in reportIds) {
            for ((pi, p) in patterns.withIndex()) {
                val n = sendOutputReport(device, rid, p, log)
                log("  -> reportId=$rid patrón#$pi bytes=${p.size} resultado=$n  (¿vibró? anótalo)")
                // pequeña pausa lógica: el usuario observa entre envíos
                Thread.sleep(400)
                // apaga (todo a cero) para separar pruebas
                sendOutputReport(device, rid, ByteArray(p.size), log)
                Thread.sleep(200)
            }
        }
        log("== Fin del barrido. Si algún combo hizo vibrar, ese es el protocolo. ==")
    }

    // ---------------------------------------------------------------------
    // Camino B mejorado: apuntar a los endpoints OUT REALES (el Kishi V2 Pro
    // los expone en interfaces separadas, no en la primera HID). Estos métodos
    // permiten un descubrimiento dirigido y disparable desde adb (broadcast).
    // ---------------------------------------------------------------------

    data class OutTarget(val ifaceId: Int, val epAddr: Int, val maxPkt: Int)

    /** Todos los endpoints interrupt OUT del dispositivo, con su interfaz. */
    fun findAllOutEndpoints(device: UsbDevice): List<OutTarget> {
        val res = ArrayList<OutTarget>()
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    res.add(OutTarget(itf.id, ep.address, ep.maxPacketSize))
                }
            }
        }
        return res
    }

    /**
     * Escribe un payload en el endpoint OUT de UNA interfaz concreta (por id) de
     * forma SOSTENIDA durante holdMs, reenviándolo cada ~10 ms (la háptica de
     * banda ancha suele necesitar un stream continuo, no un disparo único).
     * Si la interfaz no tiene endpoint OUT, cae a SET_REPORT por control.
     * @return número de escrituras con éxito (>=0), o -1 si no se pudo abrir/reclamar.
     */
    fun blast(
        device: UsbDevice,
        ifaceId: Int,
        reportId: Int,
        payload: ByteArray,
        holdMs: Long,
        log: (String) -> Unit
    ): Int {
        var iface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.id == ifaceId) { iface = itf; break }
        }
        if (iface == null) { log("blast: no existe la interfaz #$ifaceId."); return -1 }

        var out: UsbEndpoint? = null
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep
        }

        val conn: UsbDeviceConnection = usbManager.openDevice(device) ?: run {
            log("blast: no se pudo abrir el dispositivo (¿permiso concedido?)."); return -1
        }
        try {
            if (!conn.claimInterface(iface, true)) {
                log("blast: no se pudo reclamar la interfaz #$ifaceId."); return -1
            }
            val data = if (reportId != 0) byteArrayOf(reportId.toByte(), *payload) else payload
            var ok = 0
            var firstN = Int.MIN_VALUE
            val endT = System.currentTimeMillis() + holdMs
            while (System.currentTimeMillis() < endT) {
                val n = if (out != null) {
                    conn.bulkTransfer(out, data, data.size, 100)
                } else {
                    val value = (0x02 shl 8) or (reportId and 0xFF)
                    conn.controlTransfer(0x21, 0x09, value, iface.id, data, data.size, 100)
                }
                if (firstN == Int.MIN_VALUE) firstN = n
                if (n >= 0) ok++
                try { Thread.sleep(10) } catch (_: InterruptedException) {}
            }
            val epTxt = if (out != null) "0x${out.address.toString(16)}" else "ctrl/SET_REPORT"
            log("BLAST iface#$ifaceId ep=$epTxt rid=$reportId len=${data.size} holdMs=$holdMs -> escriturasOK=$ok (n0=$firstN)")
            return ok
        } finally {
            conn.releaseInterface(iface)
            conn.close()
        }
    }

    /**
     * Vuelca el HID Report Descriptor de una interfaz (GET_DESCRIPTOR, tipo 0x22).
     * Es el mapa del formato de reportes que ESPERA el dispositivo: report IDs,
     * usages (vendor), tamaños y OUTPUT items. Sin esto, cualquier reporte de
     * rumble es adivinar. Se vuelca en hex para analizarlo.
     */
    fun dumpReportDescriptor(device: UsbDevice, ifaceId: Int, log: (String) -> Unit) {
        var iface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.id == ifaceId) { iface = itf; break }
        }
        if (iface == null) { log("descriptor: no existe la interfaz #$ifaceId."); return }

        val conn: UsbDeviceConnection = usbManager.openDevice(device) ?: run {
            log("descriptor: no se pudo abrir el dispositivo (¿permiso?)."); return
        }
        try {
            conn.claimInterface(iface, true)
            val buf = ByteArray(1024)
            // bmRequestType=0x81 (IN, Standard, Interface), bRequest=0x06 GET_DESCRIPTOR
            // wValue = (0x22 << 8) | index  (0x22 = HID Report descriptor)
            val n = conn.controlTransfer(0x81, 0x06, (0x22 shl 8), ifaceId, buf, buf.size, 500)
            if (n < 0) {
                log("HID report descriptor iface#$ifaceId: GET_DESCRIPTOR falló (n=$n).")
                return
            }
            val sb = StringBuilder()
            for (i in 0 until n) {
                sb.append("%02x".format(buf[i].toInt() and 0xFF))
                sb.append(if ((i + 1) % 16 == 0) "\n" else " ")
            }
            log("HID report descriptor iface#$ifaceId ($n bytes):\n$sb")
        } finally {
            conn.releaseInterface(iface)
            conn.close()
        }
    }

    // ---------------------------------------------------------------------
    // Rumble DIRECTO real — protocolo descubierto capturando Razer Nexus:
    //   ENABLE  = reportes Razer de 90B (SET_REPORT Feature) a iface#3 + CRC XOR.
    //   STREAM  = frames de 64B a ep 0x03 (iface#4), forma de onda háptica.
    // ---------------------------------------------------------------------

    /** Construye un reporte Razer de 90 bytes con su CRC (XOR de bytes 2..87). */
    fun buildRazerReport(cmdClass: Int, cmdId: Int, args: IntArray): ByteArray {
        val b = ByteArray(90)
        b[5] = args.size.toByte()          // data_size
        b[6] = cmdClass.toByte()           // command_class
        b[7] = cmdId.toByte()              // command_id
        for (i in args.indices) b[8 + i] = args[i].toByte()
        var crc = 0
        for (i in 2..87) crc = crc xor (b[i].toInt() and 0xFF)
        b[88] = crc.toByte()               // crc
        return b
    }

    /** Los 6 comandos de "enable háptico" capturados del toggle ON de Nexus. */
    fun hapticEnableSequence(): List<ByteArray> = listOf(
        buildRazerReport(0x08, 0x06, intArrayOf(0x00, 0x21)),
        buildRazerReport(0x05, 0x84, intArrayOf(0x00)),
        buildRazerReport(0x00, 0x8a, intArrayOf(0x00, 0x00)),
        buildRazerReport(0x0c, 0x8a, intArrayOf(0x00)),
        buildRazerReport(0x0c, 0x8b, intArrayOf(0x00)),
        buildRazerReport(0x0c, 0x8a, intArrayOf(0x00))
    )

    private fun ifaceById(device: UsbDevice, id: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.id == id) return itf
        }
        return null
    }

    /**
     * Rumble DIRECTO: manda el enable a iface#3 y luego streamea `frames` a ep 0x03
     * durante holdMs (en bucle), todo en UNA conexión (como hace Nexus).
     */
    fun directRumble(
        device: UsbDevice,
        enable: List<ByteArray>,
        frames: List<ByteArray>,
        holdMs: Long,
        log: (String) -> Unit
    ): String {
        val conn: UsbDeviceConnection = usbManager.openDevice(device)
            ?: return "no se pudo abrir (¿permiso?)"
        try {
            // --- ENABLE en iface#3 (SET_REPORT Feature) ---
            val if3 = ifaceById(device, 3)
            if (if3 != null && conn.claimInterface(if3, true)) {
                var okE = 0
                for (cmd in enable) {
                    // bmRequestType=0x21, bRequest=0x09 (SET_REPORT), wValue=0x0300 (Feature,id0), wIndex=3
                    val n = conn.controlTransfer(0x21, 0x09, 0x0300, 3, cmd, cmd.size, 500)
                    if (n >= 0) okE++
                    try { Thread.sleep(15) } catch (_: InterruptedException) {}
                }
                conn.releaseInterface(if3)
                log("enable: $okE/${enable.size} comandos OK")
            } else log("no pude reclamar iface#3 para enable")

            // --- STREAM en iface#4 / ep 0x03 ---
            val if4 = ifaceById(device, 4) ?: return "no existe iface#4"
            if (!conn.claimInterface(if4, true)) return "no pude reclamar iface#4"
            var out: UsbEndpoint? = null
            for (e in 0 until if4.endpointCount) {
                val ep = if4.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep
            }
            if (out == null) { conn.releaseInterface(if4); return "iface#4 sin endpoint OUT" }

            if (frames.isEmpty()) { conn.releaseInterface(if4); return "sin frames" }
            var writes = 0; var idx = 0; var firstN = Int.MIN_VALUE
            val end = System.currentTimeMillis() + holdMs
            while (System.currentTimeMillis() < end) {
                val f = frames[idx % frames.size]
                val n = conn.bulkTransfer(out, f, f.size, 100)
                if (firstN == Int.MIN_VALUE) firstN = n
                if (n >= 0) writes++
                idx++
            }
            conn.releaseInterface(if4)
            return "STREAM: $writes escrituras (n0=$firstN) de ${frames.size} frames en ${holdMs}ms"
        } finally {
            conn.close()
        }
    }

    /** Cabecera fija del frame háptico (10 bytes). */
    private val FRAME_HEADER = byteArrayOf(
        0x55, 0xaa.toByte(), 0, 0, 0, 0, 0, 0x30, 0xfe.toByte(), 0x79
    )

    /** CRC8 (poly=0x01, init=0x00) sobre data[start until end]. Es el byte62 del frame. */
    private fun crc8(data: ByteArray, start: Int, end: Int): Int {
        var crc = 0x00
        for (i in start until end) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (b in 0 until 8) {
                crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x01) and 0xFF
                      else (crc shl 1) and 0xFF
            }
        }
        return crc and 0xFF
    }

    /**
     * Rumble DIRECTO SINTETIZADO: enable + stream de una onda cuadrada de amplitud
     * `amp` (0..32767) y frecuencia `freqHz`, con byte62=`cksum` (0 = probar si importa).
     * Prueba si podemos GENERAR frames (no solo replay) → clave para mapear la fuerza
     * real del rumble de RetroArch.
     */
    fun streamSynthRumble(
        device: UsbDevice,
        enable: List<ByteArray>,
        amp: Int,
        freqHz: Int,
        cksumMode: Int,   // 0 = byte62=0 ; 1 = contador rodante
        holdMs: Long,
        log: (String) -> Unit
    ): String {
        val conn = usbManager.openDevice(device) ?: return "no se pudo abrir (¿permiso?)"
        try {
            val if3 = ifaceById(device, 3)
            if (if3 != null && conn.claimInterface(if3, true)) {
                var okE = 0
                for (cmd in enable) {
                    if (conn.controlTransfer(0x21, 0x09, 0x0300, 3, cmd, cmd.size, 500) >= 0) okE++
                    try { Thread.sleep(15) } catch (_: InterruptedException) {}
                }
                conn.releaseInterface(if3)
                log("enable: $okE/${enable.size} OK")
            }
            val if4 = ifaceById(device, 4) ?: return "no existe iface#4"
            if (!conn.claimInterface(if4, true)) return "no pude reclamar iface#4"
            var out: UsbEndpoint? = null
            for (e in 0 until if4.endpointCount) {
                val ep = if4.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep
            }
            if (out == null) { conn.releaseInterface(if4); return "iface#4 sin OUT" }

            val a = amp.coerceIn(0, 32767)
            val half = (12000.0 / (2.0 * freqHz.coerceAtLeast(1))).toInt().coerceAtLeast(1)
            val frame = ByteArray(64)
            System.arraycopy(FRAME_HEADER, 0, frame, 0, FRAME_HEADER.size)
            var t = 0; var writes = 0; var seq = 0; var firstN = Int.MIN_VALUE
            val end = System.currentTimeMillis() + holdMs
            while (System.currentTimeMillis() < end) {
                // 12 valores (par duplicado) = 24 int16 en bytes 10..57
                for (p in 0 until 12) {
                    val v = if ((t / half) % 2 == 0) a else -a
                    val lo = (v and 0xff).toByte()
                    val hi = ((v shr 8) and 0xff).toByte()
                    val off = 10 + p * 4
                    frame[off] = lo; frame[off + 1] = hi
                    frame[off + 2] = lo; frame[off + 3] = hi
                    t++
                }
                frame[58] = 0; frame[59] = 0; frame[60] = 0; frame[61] = 0
                frame[63] = 0
                frame[62] = when (cksumMode) {
                    2 -> crc8(frame, 2, 62).toByte()   // CRC8 real (poly 0x01)
                    1 -> (seq and 0xff).toByte()        // contador rodante
                    else -> 0                           // cero (test relevancia)
                }
                val n = conn.bulkTransfer(out, frame, 64, 100)
                if (firstN == Int.MIN_VALUE) firstN = n
                if (n >= 0) writes++
                seq++
            }
            conn.releaseInterface(if4)
            return "SYNTH amp=$a freq=$freqHz cksum=$cksumMode: $writes escrituras (n0=$firstN) en ${holdMs}ms"
        } finally {
            conn.close()
        }
    }

    // ---------------------------------------------------------------------
    // Streaming CONTINUO para el servicio: mantiene el enable + escribe frames
    // a ep0x3 sin parar, con amplitud actualizable en vivo (RetroArch rumble).
    // ---------------------------------------------------------------------
    @Volatile private var streaming = false
    @Volatile private var curAmp = 0
    @Volatile private var freqHz = 130      // frecuencia de la onda
    @Volatile private var fps = 1500        // frames/seg objetivo (≈ tasa de Nexus)
    private var streamThread: Thread? = null

    private fun buildFrame(frame: ByteArray, tStart: Int, amp: Int, half: Int): Int {
        var t = tStart
        for (p in 0 until 12) {
            val v = if ((t / half) % 2 == 0) amp else -amp
            val lo = (v and 0xff).toByte(); val hi = ((v shr 8) and 0xff).toByte()
            val off = 10 + p * 4
            frame[off] = lo; frame[off + 1] = hi
            frame[off + 2] = lo; frame[off + 3] = hi
            t++
        }
        frame[58] = 0; frame[59] = 0; frame[60] = 0; frame[61] = 0; frame[63] = 0
        frame[62] = crc8(frame, 2, 62).toByte()
        return t
    }

    /**
     * Arranca el stream Sensa: enable(iface#3) + bucle de frames a ep0x3.
     * ANTI-LAG: en reposo (amp=0) NO llena el buffer (solo keepalive esporádico),
     * y en activo hace pacing a `fps` con nanoTime para mantener la cola corta.
     */
    fun startSensaStream(device: UsbDevice, enable: List<ByteArray>, freq: Int, log: (String) -> Unit) {
        if (streaming) return
        freqHz = if (freq > 0) freq else 130
        streaming = true
        streamThread = Thread {
            // Prioridad de hilo de audio en tiempo real: evita que el emulador
            // (CPU al 100%) mate de hambre al streamer → esa era la causa del lag.
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {}
            val conn = usbManager.openDevice(device) ?: run {
                log("sensa: no se pudo abrir (¿permiso?)"); streaming = false; return@Thread
            }
            var claimed4: UsbInterface? = null
            try {
                val if3 = ifaceById(device, 3)
                if (if3 != null && conn.claimInterface(if3, true)) {
                    for (cmd in enable) {
                        conn.controlTransfer(0x21, 0x09, 0x0300, 3, cmd, cmd.size, 500)
                        try { Thread.sleep(15) } catch (_: InterruptedException) {}
                    }
                    conn.releaseInterface(if3)
                }
                val if4 = ifaceById(device, 4) ?: run { log("sensa: sin iface#4"); return@Thread }
                if (!conn.claimInterface(if4, true)) { log("sensa: no reclamo iface#4"); return@Thread }
                claimed4 = if4
                var out: UsbEndpoint? = null
                for (e in 0 until if4.endpointCount) {
                    val ep = if4.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_OUT) out = ep
                }
                if (out == null) { log("sensa: iface#4 sin OUT"); return@Thread }

                val frame = ByteArray(64)
                System.arraycopy(FRAME_HEADER, 0, frame, 0, FRAME_HEADER.size)
                val silence = ByteArray(64)
                System.arraycopy(FRAME_HEADER, 0, silence, 0, FRAME_HEADER.size)
                silence[62] = crc8(silence, 2, 62).toByte()   // frame de amplitud 0
                var t = 0
                var lastKeepalive = System.nanoTime()
                var nextTs = System.nanoTime()
                log("sensa: stream ACTIVO (freq=${freqHz}Hz, fps=$fps)")
                while (streaming) {
                    val a = curAmp
                    if (a == 0) {
                        // Reposo: no encolar; solo un keepalive de silencio de vez en cuando.
                        val now = System.nanoTime()
                        if (now - lastKeepalive > 35_000_000L) {
                            conn.bulkTransfer(out, silence, 64, 20)
                            lastKeepalive = now
                        }
                        try { Thread.sleep(3) } catch (_: InterruptedException) {}
                        nextTs = System.nanoTime()
                        continue
                    }
                    // Activo: construir y enviar un frame a la amplitud actual.
                    val half = (12 * fps / (2.0 * freqHz.coerceAtLeast(1))).toInt().coerceAtLeast(1)
                    t = buildFrame(frame, t, a, half)
                    conn.bulkTransfer(out, frame, 64, 20)
                    lastKeepalive = System.nanoTime()
                    // Pacing: mantener el ritmo objetivo (cola corta = baja latencia).
                    nextTs += 1_000_000_000L / fps.coerceIn(200, 6000)
                    val sleepNs = nextTs - System.nanoTime()
                    if (sleepNs > 0) java.util.concurrent.locks.LockSupport.parkNanos(sleepNs)
                    else nextTs = System.nanoTime()
                }
            } catch (e: Exception) {
                log("sensa: error ${e.message}")
            } finally {
                try { if (claimed4 != null) conn.releaseInterface(claimed4) } catch (_: Exception) {}
                conn.close()
            }
        }.also { it.start() }
    }

    /** Amplitud en vivo (0..32767). */
    fun setSensaAmp(a: Int) { curAmp = a.coerceIn(0, 32767) }

    /** Ajuste EN VIVO de frecuencia y tasa de frames (para afinar latencia). */
    fun setSensaParams(freq: Int, fpsTarget: Int) {
        if (freq > 0) freqHz = freq
        if (fpsTarget > 0) fps = fpsTarget
    }

    fun sensaInfo(): String = "freq=${freqHz}Hz fps=$fps"

    /** Detiene el stream. */
    fun stopSensaStream() {
        streaming = false
        try { streamThread?.join(600) } catch (_: Exception) {}
        streamThread = null
    }

    fun isStreaming(): Boolean = streaming
}
