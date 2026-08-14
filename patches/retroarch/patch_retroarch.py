#!/usr/bin/env python3
"""
Inyecta el puente de rumble de RetroRazer en un APK de RetroArch ya decodificado
con apktool.

Uso:  python3 patch_retroarch.py <dir_apktool> <ruta_RRBridge.smali>

Qué hace:
  1. Busca en todo el árbol smali el método doVibrate(...) de RetroArch.
  2. Inserta al inicio del método (antes de la primera instrucción, respetando
     .locals/.registers/.param/.prologue/.annotation) una llamada:
         invoke-static {p0, p2, p3}, Lcom/retrorazer/RRBridge;->rumble(Landroid/content/Context;II)V
     donde p0=this(Context), p2=effect, p3=strength.
  3. Copia RRBridge.smali dentro del mismo smali root (com/retrorazer/).

Falla ruidosamente si no encuentra doVibrate (para que el CI lo reporte).
"""
import os
import re
import sys
import glob
import shutil

INJECTION = (
    "    invoke-static {p0, p2, p3}, "
    "Lcom/retrorazer/RRBridge;->rumble(Landroid/content/Context;II)V\n"
)

METHOD_RE = re.compile(r"^\s*\.method\s+.*\bdoVibrate\(")


def smali_roots(base):
    roots = []
    for name in sorted(os.listdir(base)):
        full = os.path.join(base, name)
        if os.path.isdir(full) and (name == "smali" or re.match(r"smali_classes\d+$", name)):
            roots.append(full)
    return roots


def patch_method(lines, start):
    """Devuelve (nuevas_lineas, indice_siguiente). Inserta la llamada tras la
    cabecera del método que empieza en `start`."""
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
        # líneas de cabecera que van antes de las instrucciones
        if (s == "" or s.startswith("#") or s.startswith(".locals") or
                s.startswith(".registers") or s.startswith(".param") or
                s.startswith(".prologue") or s.startswith(".line")):
            out.append(l); i += 1; continue
        # primera instrucción / etiqueta / .end method -> insertamos antes
        break
    out.append(INJECTION)
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

    patched = 0
    target_root = None
    for root in roots:
        for path in glob.glob(os.path.join(root, "**", "*.smali"), recursive=True):
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()
            if not any(METHOD_RE.match(l) for l in lines):
                continue
            out = []
            i = 0
            file_changed = False
            while i < len(lines):
                if METHOD_RE.match(lines[i]):
                    print("doVibrate encontrado en %s: %s" % (path, lines[i].strip()), flush=True)
                    seg, ni = patch_method(lines, i)
                    out.extend(seg)
                    i = ni
                    patched += 1
                    file_changed = True
                else:
                    out.append(lines[i])
                    i += 1
            if file_changed:
                with open(path, "w", encoding="utf-8") as f:
                    f.writelines(out)
                if target_root is None:
                    target_root = root

    if patched == 0:
        print("ERROR: no se encontró ningún método doVibrate(...). "
              "¿Cambió el nombre en esta versión de RetroArch?", flush=True)
        sys.exit(2)

    dst = os.path.join(target_root, "com", "retrorazer")
    os.makedirs(dst, exist_ok=True)
    shutil.copy(helper, os.path.join(dst, "RRBridge.smali"))
    print("OK: %d método(s) doVibrate parcheado(s). Helper en %s" % (patched, dst), flush=True)


if __name__ == "__main__":
    main()
