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
import android.util.Log;
import java.util.concurrent.locks.LockSupport;

/**
 * RetroRazer SENSA — rumble DIRECTO del Kishi V2 Pro dentro de RetroArch (en-proceso).
 * v6: DOS motores separados (fuerte=effect0, débil=effect1) en los dos canales del frame;
 * mapeo re-escalado al rango real del juego (~0..scaleMax) para que decaiga bien.
 * v6.1: defaults afinados en hardware (barrido EXP-1) — ONDA SENOIDAL a 240 (punto dulce de
 * resonancia del actuador), amplitud 16000 (menos brusco) y piso 0.04 (mata el "golpe al final").
 * Ajuste en vivo (com.retrorazer.RRSENSA_TUNE):
 *   --ei maxamp --ei scalep --ei floorp --ei curvep --ei freq --ei wave(0/1)
 *   --ei weakgain(%) --ei swap(0/1) --ei testamp
 */
public final class RRSensa {
    private static final int RAZER_VID = 0x1532;
    private static final String ACTION_PERM = "com.retrorazer.RRSENSA_PERM";
    private static final String ACTION_TUNE = "com.retrorazer.RRSENSA_TUNE";
    private static final byte[] HEADER = {0x55, (byte) 0xaa, 0, 0, 0, 0, 0, 0x30, (byte) 0xfe, 0x79};
    private static final int FPS = 1500;

    private static volatile boolean streaming = false;
    private static volatile boolean receiversDone = false;
    private static volatile long lastPermReq = 0;
    private static volatile int strongS = 0;   // effect 0
    private static volatile int weakS = 0;      // effect 1
    private static volatile int forceAmp = -1;
    private static volatile int rumbleN = 0;
    // --- feel (ajustable en vivo; defaults afinados en hardware con EXP-1) ---
    private static volatile int maxAmp = 16000;      // menos brusco (era 24000)
    private static volatile int scaleMax = 300;      // rango real de fuerza del juego
    private static volatile int freqHz = 240;        // punto dulce de resonancia del Kishi (era 150)
    private static volatile double curve = 0.6;
    private static volatile double floorFrac = 0.04; // mata el "golpe al final" (era 0.18)
    private static volatile boolean sine = true;     // seno = más suave/limpio (era cuadrada)
    private static volatile int weakGainPct = 100;   // ganancia del motor débil (izq)
    private static volatile boolean swap = false;     // intercambia canales L/R
    private static Context appCtx;
    private static UsbManager usb;

    public static void rumble(Context ctx, int effect, int strength) {
        if (effect == 1) weakS = strength; else strongS = strength;
        if ((rumbleN++ % 8) == 0)
            Log.i("RRSensa", "rumble eff=" + effect + " s=" + strength + " strong=" + strongS + " weak=" + weakS);
        if (!streaming) ensureStream(ctx);
    }

    public static void init(Context ctx) { ensureStream(ctx); }

    private static synchronized void ensureStream(Context ctx) {
        try {
            if (streaming) return;
            if (appCtx == null) appCtx = ctx.getApplicationContext();
            if (usb == null) usb = (UsbManager) appCtx.getSystemService(Context.USB_SERVICE);
            if (!receiversDone) { registerReceivers(appCtx); receiversDone = true; }
            UsbDevice dev = findKishi();
            Log.i("RRSensa", "ensureStream: kishi=" + (dev != null) + " perm=" + (dev != null && usb.hasPermission(dev)));
            if (dev == null) return;
            if (usb.hasPermission(dev)) {
                startStream(dev);
            } else {
                long now = System.currentTimeMillis();
                if (now - lastPermReq > 4000) {
                    lastPermReq = now;
                    int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                    PendingIntent pi = PendingIntent.getBroadcast(appCtx, 0,
                            new Intent(ACTION_PERM).setPackage(appCtx.getPackageName()), flags);
                    usb.requestPermission(dev, pi);
                }
            }
        } catch (Throwable t) {}
    }

    private static void registerReceivers(Context app) {
        BroadcastReceiver perm = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                UsbDevice d = findKishi();
                if (d != null && usb.hasPermission(d)) startStream(d);
            }
        };
        BroadcastReceiver tune = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                int v;
                if ((v = i.getIntExtra("maxamp", -1)) >= 0) maxAmp = Math.min(32767, v);
                if ((v = i.getIntExtra("scalep", -1)) > 0) scaleMax = v;
                if ((v = i.getIntExtra("freq", -1)) > 0) freqHz = v;
                if ((v = i.getIntExtra("curvep", -1)) > 0) curve = v / 100.0;
                if ((v = i.getIntExtra("floorp", -1)) >= 0) floorFrac = v / 100.0;
                if ((v = i.getIntExtra("wave", -1)) >= 0) sine = (v != 0);
                if ((v = i.getIntExtra("weakgain", -1)) >= 0) weakGainPct = v;
                if ((v = i.getIntExtra("swap", -1)) >= 0) swap = (v != 0);
                if ((v = i.getIntExtra("testamp", -2)) >= -1) forceAmp = v;
                Log.i("RRSensa", "TUNE max=" + maxAmp + " scale=" + scaleMax + " floor=" + floorFrac +
                        " curve=" + curve + " wave=" + (sine ? "sine" : "sq") + " weakGain=" + weakGainPct + " swap=" + swap);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(perm, new IntentFilter(ACTION_PERM), Context.RECEIVER_NOT_EXPORTED);
                app.registerReceiver(tune, new IntentFilter(ACTION_TUNE), Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(perm, new IntentFilter(ACTION_PERM));
                app.registerReceiver(tune, new IntentFilter(ACTION_TUNE));
            }
        } catch (Throwable t) {}
    }

    private static UsbDevice findKishi() {
        try {
            for (UsbDevice d : usb.getDeviceList().values())
                if (d.getVendorId() == RAZER_VID) return d;
        } catch (Throwable t) {}
        return null;
    }

    /** Fuerza (0..scaleMax) -> amplitud, con piso y curva. */
    private static int amp(int s) {
        if (s <= 0) return 0;
        double n = s / (double) scaleMax;
        if (n > 1.0) n = 1.0;
        double shaped = floorFrac + (1.0 - floorFrac) * Math.pow(n, curve);
        return (int) (shaped * maxAmp);
    }

    private static synchronized void startStream(final UsbDevice dev) {
        if (streaming) return;
        streaming = true;
        Thread t = new Thread(new Runnable() { public void run() { streamLoop(dev); } });
        t.setDaemon(true);
        t.start();
    }

    private static void streamLoop(UsbDevice dev) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable t) {}
        UsbDeviceConnection conn = null;
        UsbInterface if4 = null;
        try {
            conn = usb.openDevice(dev);
            Log.i("RRSensa", "openDevice=" + (conn != null));
            if (conn == null) { streaming = false; return; }
            UsbInterface if3 = ifaceById(dev, 3);
            if (if3 != null && conn.claimInterface(if3, true)) {
                byte[][] en = enableSeq();
                int okE = 0;
                for (int i = 0; i < en.length; i++)
                    if (conn.controlTransfer(0x21, 0x09, 0x0300, 3, en[i], en[i].length, 500) >= 0) okE++;
                conn.releaseInterface(if3);
                Log.i("RRSensa", "enable " + okE + "/" + en.length);
            }
            if4 = ifaceById(dev, 4);
            if (if4 == null || !conn.claimInterface(if4, true)) { Log.w("RRSensa", "no iface4"); streaming = false; return; }
            UsbEndpoint out = null;
            for (int e = 0; e < if4.getEndpointCount(); e++) {
                UsbEndpoint ep = if4.getEndpoint(e);
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep;
            }
            if (out == null) { streaming = false; return; }
            Log.i("RRSensa", "STREAM ACTIVE (2 motores)");

            byte[] frame = new byte[64];
            System.arraycopy(HEADER, 0, frame, 0, HEADER.length);
            byte[] silence = new byte[64];
            System.arraycopy(HEADER, 0, silence, 0, HEADER.length);
            silence[62] = (byte) crc8(silence, 2, 62);
            double sampleRate = 12.0 * FPS;
            double phase = 0.0;
            int t = 0;
            long lastKeep = System.nanoTime();
            long nextTs = System.nanoTime();
            while (streaming) {
                int aStrong = amp(strongS);
                int aWeak = (int) (amp(weakS) * (weakGainPct / 100.0));
                if (forceAmp >= 0) { aStrong = forceAmp; aWeak = forceAmp; }
                int aMax = Math.max(aStrong, aWeak);
                if (aMax == 0) {
                    long now = System.nanoTime();
                    if (now - lastKeep > 35000000L) { conn.bulkTransfer(out, silence, 64, 20); lastKeep = now; }
                    sleep(3); nextTs = System.nanoTime(); continue;
                }
                // canal A = fuerte (motor principal), canal B = débil; swap invierte L/R
                int chA = swap ? aWeak : aStrong;
                int chB = swap ? aStrong : aWeak;
                double inc = freqHz / sampleRate;
                int half = (int) (sampleRate / (2.0 * freqHz)); if (half < 1) half = 1;
                for (int p = 0; p < 12; p++) {
                    double w;
                    if (sine) { w = Math.sin(2.0 * Math.PI * phase); phase += inc; if (phase >= 1.0) phase -= 1.0; }
                    else { w = ((t / half) % 2 == 0) ? 1.0 : -1.0; t++; }
                    int v1 = (int) (w * chA);
                    int v2 = (int) (w * chB);
                    int off = 10 + p * 4;
                    frame[off] = (byte) (v1 & 0xff); frame[off + 1] = (byte) ((v1 >> 8) & 0xff);
                    frame[off + 2] = (byte) (v2 & 0xff); frame[off + 3] = (byte) ((v2 >> 8) & 0xff);
                }
                frame[58] = 0; frame[59] = 0; frame[60] = 0; frame[61] = 0; frame[63] = 0;
                frame[62] = (byte) crc8(frame, 2, 62);
                conn.bulkTransfer(out, frame, 64, 20);
                nextTs += 1000000000L / FPS;
                long sleepNs = nextTs - System.nanoTime();
                if (sleepNs > 0) LockSupport.parkNanos(sleepNs); else nextTs = System.nanoTime();
            }
        } catch (Throwable t) {
            Log.e("RRSensa", "streamLoop ERROR", t);
        } finally {
            try { if (if4 != null && conn != null) conn.releaseInterface(if4); } catch (Throwable t) {}
            try { if (conn != null) conn.close(); } catch (Throwable t) {}
            streaming = false;
        }
    }

    private static UsbInterface ifaceById(UsbDevice dev, int id) {
        for (int i = 0; i < dev.getInterfaceCount(); i++)
            if (dev.getInterface(i).getId() == id) return dev.getInterface(i);
        return null;
    }

    private static int crc8(byte[] d, int start, int end) {
        int crc = 0;
        for (int i = start; i < end; i++) {
            crc ^= (d[i] & 0xff);
            for (int b = 0; b < 8; b++)
                crc = ((crc & 0x80) != 0) ? (((crc << 1) ^ 0x01) & 0xff) : ((crc << 1) & 0xff);
        }
        return crc & 0xff;
    }

    private static byte[] razer(int cclass, int cid, int[] args) {
        byte[] b = new byte[90];
        b[5] = (byte) args.length; b[6] = (byte) cclass; b[7] = (byte) cid;
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

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
}
