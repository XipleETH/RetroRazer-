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
}
