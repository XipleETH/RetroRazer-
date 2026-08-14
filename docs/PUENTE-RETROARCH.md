# Puente RetroArch → Kishi (versión definitiva)

Convierte el **rumble real** de los juegos de PS1 (y otros) en vibración del Kishi
V2 Pro, aprovechando su Audio Haptics.

## Arquitectura

```
Juego PS1 pide rumble
  └─ Núcleo libretro (DualShock + Rumble ON)
       └─ RetroArch frontend doVibrate(id, effect, strength, oneShot)
            └─ [INYECTADO] RRBridge.rumble(ctx, effect, strength)
                 └─ broadcast "com.retrorazer.rumblebridge.RUMBLE" (extra s=strength, e=effect)
                      └─ RumbleHapticService (app RetroRazer, en primer plano)
                           └─ HapticEngine: pulso grave con amplitud = strength
                                └─ Razer Nexus Audio Haptics → motores del Kishi 🎮
```

- **RetroArch** solo lleva una línea inyectada (en smali) que emite un broadcast.
- **Nuestra app** hace todo el trabajo de audio (ya calibrado y validado).

## Piezas

1. `patches/retroarch/RRBridge.smali` — clase-puente inyectada (emite el broadcast).
2. `patches/retroarch/patch_retroarch.py` — inyecta la llamada en `doVibrate`.
3. `.github/workflows/patch-retroarch.yml` — descarga RetroArch oficial (aarch64),
   lo parchea, firma y publica en la release `retroarch-latest`.

## Cómo obtener el RetroArch parcheado

El workflow **Patch RetroArch** corre solo al tocar `patches/retroarch/**`, o a
mano desde la pestaña **Actions → Patch RetroArch → Run workflow** (ahí puedes
pasar una `apk_url` distinta si hiciera falta otra versión).

APK resultante (release `retroarch-latest`):
```
https://github.com/XipleETH/RetroRazer-/releases/download/retroarch-latest/RetroArch-RetroRazer.apk
```

## Puesta en marcha (en el celular)

1. Instala **RetroRazer Rumble Bridge** (release `apk-latest`) y pulsa
   **🟢 Iniciar puente de rumble**. Debe quedar la notificación "puente activo".
2. **Razer Nexus** con **Audio Haptics = Alta**; volumen de multimedia arriba.
3. Instala **RetroArch-RetroRazer.apk**.
   - Si ya tienes RetroArch oficial: **desinstálalo antes** (misma app, firma
     distinta).
4. En RetroArch, carga un PS1 y en *Opciones del núcleo* activa **Rumble** con
   mando **DualShock/analógico** (ver `RETROARCH-CONFIG.md`).
5. Juega → el rumble del juego hará vibrar el Kishi.

## Notas y límites honestos

- El puente envía la **fuerza real** del rumble (0..65535), así que la vibración
  coincide con lo que pide el juego (no con el sonido).
- El pulso háptico se mezcla en el audio (leve zumbido grave durante el rumble);
  se puede afinar la frecuencia/onda en el Laboratorio háptico si molesta.
- El parche depende de que RetroArch mantenga el método `doVibrate`. Si una
  versión futura lo renombra, el workflow avisa (falla) y hay que ajustar el
  script. Puedes fijar una versión con el input `apk_url`.
- Firma de depuración: por eso no puede coexistir con el RetroArch oficial.
