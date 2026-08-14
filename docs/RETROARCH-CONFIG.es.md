<!-- Idioma: Español -->
[English](RETROARCH-CONFIG.md) · **Español** · [日本語](RETROARCH-CONFIG.ja.md)

# Configuración de RetroArch para el rumble

Aunque el puente funcione, **no sentirás nada** si el núcleo no *pide* rumble.
Aplica todo esto antes de dar algo por roto.

## 1. Ajustes globales de entrada

`Ajustes → Entrada`:

- **Rumble / Vibración**: ON.
- Si tu build lo tiene: **Vibrar en los mandos / Enable Device Vibration** → ON.

En `retroarch.cfg`:
```ini
input_rumble_enable = "true"
```

## 2. Opción de núcleo: Rumble (imprescindible en PS1)

Con un juego de PS1 cargado: `Menú rápido → Opciones`.

### Beetle PSX / Beetle PSX HW
- **Rumble** = `enabled`
- El puerto del mando debe ser de tipo **analógico / DualShock**.

### PCSX-ReARMed
- **Rumble** (`pcsx_rearmed_vibration`) = `enabled`
- **Pad type for port 1** (`pcsx_rearmed_pad1type`) = `analog` o `dualshock`

### SwanStation / DuckStation-libretro
- **Controller 1 Type** = `Analog Controller (DualShock)`
- **Enable Rumble / Vibration** = `enabled`

> Regla general: **DualShock/analógico + Rumble ON**. Un mando "digital/estándar"
> nunca pide vibración.

## 3. Mapea el control al puerto 1

`Ajustes → Entrada → Puerto 1` — asegúrate de que el Kishi está en el **Puerto 1**
(la mayoría de juegos de 1 jugador emiten rumble por el puerto 1).

## 4. Prueba rápida

Juegos de PS1 con rumble evidente:
- *Ape Escape* (requiere DualShock, vibración constante)
- *Metal Gear Solid* (alerta / códec)
- *Gran Turismo 2* (golpes / derrapes)

## Solución de problemas

- No vibra nada → confirma que el **servicio del puente RetroRazer está activo** y
  **Razer Nexus Audio Haptics = Alta**, con el volumen arriba.
- Sigue sin vibrar → revisa las opciones del núcleo (DualShock + Rumble).
- El audio también vibra → usa el **RetroArch parcheado**
  (`RetroArch-RetroRazer.apk`), que marca el audio del juego como no capturable.
