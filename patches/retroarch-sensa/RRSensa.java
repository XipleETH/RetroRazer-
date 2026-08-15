package com.retrorazer;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Process;
import java.util.concurrent.locks.LockSupport;

/**
 * RetroRazer SENSA — rumble DIRECTO del Razer Kishi V2 Pro, integrado DENTRO de
 * RetroArch (mismo proceso, sin broadcast) para latencia mínima.
 *   init(ctx)                -> inyectado en onCreate: abre USB + pide permiso + arranca stream.
 *   rumble(ctx, eff, strength) -> inyectado en doVibrate: actualiza la amplitud en vivo.
 * Protocolo Interhaptics/Razer descifrado: enable (6 reportes Razer de 90B, SET_REPORT
 * a iface#3, CRC XOR) + stream de frames de 64B a ep 0x03 (CRC8 poly=0x01 en byte62).
 */
public final class RRSensa {
    private static final int RAZER_VID = 0x1532;
    private static final String ACTION_PERM = "com.retrorazer.RRSENSA_PERM";
    private static final byte[] HEADER = {0x55, (byte) 0xaa, 0, 0, 0, 0, 0, 0x30, (byte) 0xfe, 0x79};

    private static volatile boolean initialized = false;
    private static volatile boolean streaming = false;
    private static volatile int curAmp = 0;
    private static UsbManager usb;

    /** Desde doVibrate(strength): actualiza amplitud (0..65535 -> onda). */
    public static void rumble(Context ctx, int effect, int strength) {
        curAmp = strengthToAmp(strength);
        if (!initialized) init(ctx);
    }

    /** Desde onCreate: prepara USB + permiso temprano (evita diálogo en pleno juego). */
    public static synchronized void init(Context ctx) {
        if (initialized) return;
        initialized = true;
        try {
            final Context app = ctx.getApplicationContext();
            usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
            UsbDevice dev = findKishi();
            if (dev == null) return;
            if (usb.hasPermission(dev)) {
                startStream(dev);
            } else {
                BroadcastReceiver r = new BroadcastReceiver() {
                    public void onReceive(Context c, Intent i) {
                        try { c.unregisterReceiver(this); } catch (Throwable t) {}
                        UsbDevice d = findKishi();
                        if (d != null && usb.hasPermission(d)) startStream(d);
                    }
                };
                IntentFilter f = new IntentFilter(ACTION_PERM);
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    app.registerReceiver(r, f);
                }
                int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                PendingIntent pi = PendingIntent.getBroadcast(app, 0,
                        new Intent(ACTION_PERM).setPackage(app.getPackageName()), flags);
                usb.requestPermission(dev, pi);
            }
        } catch (Throwable t) {}
    }

    private static UsbDevice findKishi() {
        try {
            for (UsbDevice d : usb.getDeviceList().values()) {
                if (d.getVendorId() == RAZER_VID) return d;
            }
        } catch (Throwable t) {}
        return null;
    }

    private static int strengthToAmp(int s) {
        if (s <= 0) return 0;
        double n = s / 65535.0;
        if (n > 1.0) n = 1.0;
        int a = (int) ((0.45 + 0.55 * Math.sqrt(n)) * 28000.0);
        if (a < 1) a = 1;
        if (a > 28000) a = 28000;
        return a;
    }

    private static synchronized void startStream(final UsbDevice dev) {
        if (streaming) return;
        streaming = true;
        Thread t = new Thread(new Runnable() {
            public void run() { streamLoop(dev); }
        });
        t.setDaemon(true);
        t.start();
    }

    private static void streamLoop(UsbDevice dev) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable t) {}
        UsbDeviceConnection conn = null;
        UsbInterface if4 = null;
        try {
            conn = usb.openDevice(dev);
            if (conn == null) { streaming = false; return; }
            UsbInterface if3 = ifaceById(dev, 3);
            if (if3 != null && conn.claimInterface(if3, true)) {
                byte[][] en = enableSeq();
                for (int i = 0; i < en.length; i++) {
                    conn.controlTransfer(0x21, 0x09, 0x0300, 3, en[i], en[i].length, 500);
                    sleep(15);
                }
                conn.releaseInterface(if3);
            }
            if4 = ifaceById(dev, 4);
            if (if4 == null || !conn.claimInterface(if4, true)) { streaming = false; return; }
            UsbEndpoint out = null;
            for (int e = 0; e < if4.getEndpointCount(); e++) {
                UsbEndpoint ep = if4.getEndpoint(e);
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep;
            }
            if (out == null) { streaming = false; return; }

            byte[] frame = new byte[64];
            System.arraycopy(HEADER, 0, frame, 0, HEADER.length);
            byte[] silence = new byte[64];
            System.arraycopy(HEADER, 0, silence, 0, HEADER.length);
            silence[62] = (byte) crc8(silence, 2, 62);
            final int freq = 130, fps = 1500;
            int t = 0;
            long lastKeep = System.nanoTime();
            long nextTs = System.nanoTime();
            while (streaming) {
                int a = curAmp;
                if (a == 0) {
                    long now = System.nanoTime();
                    if (now - lastKeep > 35000000L) { conn.bulkTransfer(out, silence, 64, 20); lastKeep = now; }
                    sleep(3);
                    nextTs = System.nanoTime();
                    continue;
                }
                int half = (int) (12.0 * fps / (2.0 * freq));
                if (half < 1) half = 1;
                for (int p = 0; p < 12; p++) {
                    int v = ((t / half) % 2 == 0) ? a : -a;
                    byte lo = (byte) (v & 0xff);
                    byte hi = (byte) ((v >> 8) & 0xff);
                    int off = 10 + p * 4;
                    frame[off] = lo; frame[off + 1] = hi;
                    frame[off + 2] = lo; frame[off + 3] = hi;
                    t++;
                }
                frame[58] = 0; frame[59] = 0; frame[60] = 0; frame[61] = 0; frame[63] = 0;
                frame[62] = (byte) crc8(frame, 2, 62);
                conn.bulkTransfer(out, frame, 64, 20);
                nextTs += 1000000000L / fps;
                long sleepNs = nextTs - System.nanoTime();
                if (sleepNs > 0) LockSupport.parkNanos(sleepNs);
                else nextTs = System.nanoTime();
            }
        } catch (Throwable t) {
        } finally {
            try { if (if4 != null && conn != null) conn.releaseInterface(if4); } catch (Throwable t) {}
            try { if (conn != null) conn.close(); } catch (Throwable t) {}
            streaming = false;
        }
    }

    private static UsbInterface ifaceById(UsbDevice dev, int id) {
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            if (dev.getInterface(i).getId() == id) return dev.getInterface(i);
        }
        return null;
    }

    private static int crc8(byte[] d, int start, int end) {
        int crc = 0;
        for (int i = start; i < end; i++) {
            crc ^= (d[i] & 0xff);
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x80) != 0) ? (((crc << 1) ^ 0x01) & 0xff) : ((crc << 1) & 0xff);
            }
        }
        return crc & 0xff;
    }

    private static byte[] razer(int cclass, int cid, int[] args) {
        byte[] b = new byte[90];
        b[5] = (byte) args.length;
        b[6] = (byte) cclass;
        b[7] = (byte) cid;
        for (int i = 0; i < args.length; i++) b[8 + i] = (byte) args[i];
        int crc = 0;
        for (int i = 2; i <= 87; i++) crc ^= (b[i] & 0xff);
        b[88] = (byte) crc;
        return b;
    }

    private static byte[][] enableSeq() {
        return new byte[][]{
                razer(0x08, 0x06, new int[]{0x00, 0x21}),
                razer(0x05, 0x84, new int[]{0x00}),
                razer(0x00, 0x8a, new int[]{0x00, 0x00}),
                razer(0x0c, 0x8a, new int[]{0x00}),
                razer(0x0c, 0x8b, new int[]{0x00}),
                razer(0x0c, 0x8a, new int[]{0x00}),
        };
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {}
    }
}
