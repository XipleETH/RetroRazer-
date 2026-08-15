#!/usr/bin/env python3
"""
RetroRazer — sonda HID USB para el control (Razer Kishi).

Sirve para averiguar, con el control conectado por CABLE al PC, si sus motores se
pueden mover por reportes HID directos (sin audio / sin Nexus).

Requisitos:
    pip install hidapi

Uso:
    1) Enumerar (SEGURO, no envía nada):
        python hid_probe.py list
       -> copia y pega TODO lo que imprima.

    2) Probar señales a una interfaz (EXPERIMENTAL, envía reportes de salida):
        python hid_probe.py probe            # prueba TODAS las interfaces Razer
        python hid_probe.py probe <path>     # una interfaz concreta (el 'path' del list)
       -> observa el control: si vibra en algún envío, ANOTA la línea exacta.

Notas:
    - Enviar reportes de salida a un gamepad es de bajo riesgo (lo peor: no pasa nada).
    - Si los motores son puramente audio-hápticos, NINGÚN reporte los moverá: eso
      también es una respuesta (confirma que la única vía es el audio).
"""
import sys
import time

try:
    import hid  # hidapi
except ImportError:
    print("Falta 'hidapi'. Instala con:  pip install hidapi")
    sys.exit(1)

RAZER_VID = 0x1532


def _s(x):
    return x.decode("utf-8", "replace") if isinstance(x, (bytes, bytearray)) else x


def list_devices():
    print("== Dispositivos HID detectados ==")
    razer = []
    for d in hid.enumerate():
        vid, pid = d["vendor_id"], d["product_id"]
        mark = "   <<< RAZER" if vid == RAZER_VID else ""
        print(
            f"VID=0x{vid:04x} PID=0x{pid:04x} "
            f"usage_page=0x{d.get('usage_page', 0):04x} usage=0x{d.get('usage', 0):04x} "
            f"iface={d.get('interface_number')} "
            f"product={_s(d.get('product_string'))!r} "
            f"manuf={_s(d.get('manufacturer_string'))!r}{mark}"
        )
        print(f"    path={_s(d['path'])!r}")
        if vid == RAZER_VID:
            razer.append(d)
    print()
    if razer:
        print(f"Se encontraron {len(razer)} interfaz(es) Razer (VID 0x1532).")
    else:
        print("No se encontró ningún dispositivo Razer. ¿El control está por cable y encendido?")
    return razer


# Patrones candidatos: dos bytes de intensidad (motor grande/pequeño) al máximo,
# con distintos report IDs y tamaños de reporte.
PATTERNS = [
    [0xFF, 0xFF],
    [0x00, 0xFF, 0xFF],
    [0xFF, 0x00, 0xFF, 0x00],
    [0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF],
]
REPORT_IDS = [0x00, 0x01, 0x02, 0x03, 0x04, 0x05]
SIZES = [64, 32, 16]


def _write(dev, data):
    try:
        return dev.write(bytes(data))
    except Exception as e:
        return f"ERR({e})"


def probe_path(path):
    print(f"\n== Probando interfaz {path!r} ==")
    dev = hid.device()
    try:
        dev.open_path(path if isinstance(path, bytes) else path.encode())
    except Exception as e:
        print(f"  No se pudo abrir: {e}")
        return
    try:
        dev.set_nonblocking(1)
        for size in SIZES:
            for rid in REPORT_IDS:
                for pi, pat in enumerate(PATTERNS):
                    body = [rid] + pat
                    body += [0] * (size - len(body))
                    r = _write(dev, body)
                    print(f"  size={size:2d} reportId={rid} patrón#{pi} -> {r}   (¿vibró?)")
                    time.sleep(0.5)
                    # apagar
                    _write(dev, [rid] + [0] * (size - 1))
                    time.sleep(0.2)
    finally:
        dev.close()
    print("== Fin de la interfaz. Si vibró en alguna línea, ese es el reporte. ==")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "list"
    if cmd == "list":
        list_devices()
    elif cmd == "probe":
        if len(sys.argv) > 2:
            probe_path(sys.argv[2])
        else:
            razer = list_devices()
            if not razer:
                return
            print("\nProbando TODAS las interfaces Razer…\n")
            for d in razer:
                probe_path(d["path"])
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
