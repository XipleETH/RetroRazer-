# RetroArch + Sensa DIRECTO (una sola app, sin lag)

Integra el rumble **directo** del Razer Kishi V2 Pro **dentro** de RetroArch, en el
mismo proceso — sin broadcast ni segunda app. Esto elimina la latencia: RetroArch
maneja el motor del control él mismo, tan instantáneo como cuando vibra el teléfono.

## Por qué

El puente de dos apps (`rumble-bridge` en modo AUDIO o SENSA) funciona, pero mete
lag: `RetroArch → broadcast → servicio → USB`. Al meter el streamer Sensa **dentro**
de RetroArch, el evento de `doVibrate` escribe directo al Kishi por USB en-proceso.
Latencia mínima, sincronizado con la acción del juego.

## El protocolo (descifrado capturando Razer Nexus con Frida)

- **Enable**: 6 reportes Razer de 90 bytes por `SET_REPORT` (bmReqType=0x21,
  bReq=0x09, wValue=0x0300, wIndex=3) a la interfaz #3. Formato OpenRazer
  `razer_report`; **CRC = XOR de bytes[2..87]**. Comandos: `(0x08,0x06,{0x00,0x21})`,
  `(0x05,0x84,{0})`, `(0x00,0x8a,{0,0})`, `(0x0c,0x8a,{0})`, `(0x0c,0x8b,{0})`,
  `(0x0c,0x8a,{0})`.
- **Stream**: frames de 64 bytes por `USBDEVFS_BULK` al endpoint **0x03** (iface #4):
  `55 aa 00 00 00 00 00 30 fe 79` (cabecera fija) + 24×int16 LE (forma de onda,
  en pares duplicados) + `00 00 00 00` + **byte62 = CRC8(poly=0x01, init=0, bytes[2..61])** + `00`.

Todo esto vive en [`RRSensa.java`](RRSensa.java).

## Cómo construir el APK (single-app)

Requiere: JDK 17, Android SDK build-tools 34 (`d8`, `zipalign`, `apksigner`),
[apktool](https://github.com/iBotPeaches/Apktool), y el APK oficial de RetroArch
aarch64 (buildbot.libretro.com).

```bash
# 1) Compilar RRSensa.java -> classes.dex (dex adicional; multidex nativo lo carga)
javac -source 8 -target 8 -cp "$ANDROID_SDK/platforms/android-34/android.jar" \
      -d out patches/retroarch-sensa/RRSensa.java
d8 --min-api 26 --lib "$ANDROID_SDK/platforms/android-34/android.jar" \
   --output rrsensa out/com/retrorazer/*.class            # -> rrsensa/classes.dex

# 2) Decodificar RetroArch (solo smali)
apktool d -r -f -o ra RetroArch_aarch64.apk

# 3) Inyectar en el smali de RetroArch:
#   RetroActivityCommon.smali :: doVibrate(IIII)V  (justo tras la MethodParameters):
#       invoke-static {p0, p2, p3}, Lcom/retrorazer/RRSensa;->rumble(Landroid/content/Context;II)V
#       return-void            # solo el Kishi vibra, no el teléfono
#   RetroActivityFuture.smali :: onCreate(Landroid/os/Bundle;)V  (tras la anotación):
#       invoke-static {p0}, Lcom/retrorazer/RRSensa;->init(Landroid/content/Context;)V

# 4) Reconstruir, agregar classes2.dex, alinear y firmar
apktool b -o ra-unsigned.apk ra
#   agregar rrsensa/classes.dex como classes2.dex dentro de ra-unsigned.apk (zip)
zipalign -f -p 4 ra-unsigned.apk ra-aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android RetroArch-RetroRazer.apk ...
```

Iterar solo el streamer (afinar vibración) es barato: recompila `RRSensa.java` →
reemplaza `classes2.dex` → re-firma. No hace falta re-ensamblar el smali de RetroArch.

## Uso

1. Instala este RetroArch parcheado (desinstala el oficial antes — misma package,
   firma distinta).
2. Acopla el **Kishi V2 Pro** y abre RetroArch → **concede el permiso USB** del Kishi.
3. Núcleo PS1 con mando **DualShock + Rumble ON**. Juega → el control vibra directo.

No requiere Razer Nexus ni audio. Build no oficial de RetroArch (GPLv3); fuente
correspondiente = RetroArch original + este parche.
