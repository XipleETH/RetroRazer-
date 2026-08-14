#!/usr/bin/env python3
"""
Inyecta el puente de rumble de RetroRazer en un APK de RetroArch ya decodificado
con apktool.

Uso:  python3 patch_retroarch.py <dir_apktool> <ruta_RRBridge.smali>

Inyecciones:
  1. doVibrate(...)  -> invoke-static RRBridge.rumble(p0=this, p2=effect, p3=strength)
     Reenvía el rumble real del juego a la app RetroRazer. (OBLIGATORIA)
  2. onCreate(Bundle) de las clases retroactivity -> RRBridge.blockCapture(p0=this)
     Marca el audio de RetroArch como no capturable para que Nexus no derive
     vibración del sonido del juego. (OPCIONAL: avisa si no encuentra)

Copia RRBridge.smali dentro del mismo smali root (com/retrorazer/).
Falla ruidosamente si no encuentra doVibrate.
"""
import os
import re
import sys
import glob
import shutil

INJ_RUMBLE = (
    "    invoke-static {p0, p2, p3}, "
    "Lcom/retrorazer/RRBridge;->rumble(Landroid/content/Context;II)V\n"
)
INJ_BLOCK = (
    "    invoke-static {p0}, "
    "Lcom/retrorazer/RRBridge;->blockCapture(Landroid/content/Context;)V\n"
)

DOVIBRATE_RE = re.compile(r"^\s*\.method\s+.*\bdoVibrate\(")
ONCREATE_RE = re.compile(r"^\s*\.method\s+.*\bonCreate\(Landroid/os/Bundle;\)V")


def smali_roots(base):
    roots = []
    for name in sorted(os.listdir(base)):
        full = os.path.join(base, name)
        if os.path.isdir(full) and (name == "smali" or re.match(r"smali_classes\d+$", name)):
            roots.append(full)
    return roots


def inject_after_header(lines, start, injection):
    """Copia la cabecera del método (que empieza en `start`) y coloca `injection`
    justo antes de la primera instrucción. Devuelve (segmento, indice_siguiente)."""
    out = [lines[start]]
    i = start + 1
    ann_depth = 0
    while i < len(lines):
        l = lines[i]
        s = l.strip()
        if s.startswith(".annotation"):
            ann_depth += 1
            out.append(l); i += 1; continue
        if s.startswith(".end annotation"):
            ann_depth -= 1
            out.append(l); i += 1; continue
        if ann_depth > 0:
            out.append(l); i += 1; continue
        if (s == "" or s.startswith("#") or s.startswith(".locals") or
                s.startswith(".registers") or s.startswith(".param") or
                s.startswith(".prologue") or s.startswith(".line")):
            out.append(l); i += 1; continue
        break
    out.append(injection)
    return out, i


def main():
    if len(sys.argv) != 3:
        print("uso: patch_retroarch.py <dir_apktool> <RRBridge.smali>", flush=True)
        sys.exit(64)
    base, helper = sys.argv[1], sys.argv[2]
    roots = smali_roots(base)
    if not roots:
        print("ERROR: no se encontraron carpetas smali en", base, flush=True)
        sys.exit(1)

    n_rumble = 0
    n_block = 0
    target_root = None

    for root in roots:
        for path in glob.glob(os.path.join(root, "**", "*.smali"), recursive=True):
            is_retroactivity = "retroactivity" in path.lower()
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()

            has_dov = any(DOVIBRATE_RE.match(l) for l in lines)
            has_oc = is_retroactivity and any(ONCREATE_RE.match(l) for l in lines)
            if not has_dov and not has_oc:
                continue

            out = []
            i = 0
            changed = False
            while i < len(lines):
                line = lines[i]
                if DOVIBRATE_RE.match(line):
                    print("doVibrate en %s: %s" % (path, line.strip()), flush=True)
                    seg, i = inject_after_header(lines, i, INJ_RUMBLE)
                    out.extend(seg)
                    n_rumble += 1
                    changed = True
                    continue
                if is_retroactivity and ONCREATE_RE.match(line):
                    print("onCreate en %s: %s" % (path, line.strip()), flush=True)
                    seg, i = inject_after_header(lines, i, INJ_BLOCK)
                    out.extend(seg)
                    n_block += 1
                    changed = True
                    continue
                out.append(line)
                i += 1

            if changed:
                with open(path, "w", encoding="utf-8") as f:
                    f.writelines(out)
                if target_root is None:
                    target_root = root

    if n_rumble == 0:
        print("ERROR: no se encontró doVibrate(...). ¿Cambió el nombre en RetroArch?", flush=True)
        sys.exit(2)
    if n_block == 0:
        print("AVISO: no se inyectó blockCapture (no se halló onCreate en retroactivity). "
              "El rumble funcionará, pero el audio del juego podría seguir generando "
              "vibración por Nexus.", flush=True)

    dst = os.path.join(target_root, "com", "retrorazer")
    os.makedirs(dst, exist_ok=True)
    shutil.copy(helper, os.path.join(dst, "RRBridge.smali"))
    print("OK: rumble x%d, blockCapture x%d. Helper en %s" % (n_rumble, n_block, dst), flush=True)


if __name__ == "__main__":
    main()
