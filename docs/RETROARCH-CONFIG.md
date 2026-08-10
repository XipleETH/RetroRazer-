# Configuración de RetroArch para rumble (Camino A)

Aunque el frontend enrute bien la vibración al control, **no sentirás nada** si el
núcleo no la pide. Estos son los ajustes que hay que tocar para PlayStation 1 y
otros sistemas. Aplica todo esto **antes** de dar por fallido el arreglo.

## 1. Ajustes globales de entrada

`Ajustes → Entrada`:

- **Vibración / Rumble**: activado.
- Si tu build lo tiene: **Vibrar en los mandos / Enable Device Vibration** →
  activado (esto es lo que hace que la vibración vaya al mando y no al teléfono).

En `retroarch.cfg` los ajustes equivalentes:

```ini
# Habilita el reenvío de rumble en general
input_rumble_enable = "true"
# (nombre puede variar según versión/fork; busca claves con 'rumble' o 'vibrat')
```

## 2. Opción de núcleo: Rumble (imprescindible en PS1)

Con un juego de PS1 cargado: `Menú rápido → Opciones` (opciones del núcleo).

### Beetle PSX / Beetle PSX HW
- **Rumble** = `enabled`
- El puerto del mando debe estar en un tipo **analógico/DualShock** para que el
  juego active los motores.

### PCSX-ReARMed
- **Rumble** (`pcsx_rearmed_vibration`) = `enabled`
- **Pad type for port 1** (`pcsx_rearmed_pad1type`) = `analog` o `dualshock`

### SwanStation / DuckStation-libretro
- **Controller 1 Type** = `Analog Controller (DualShock)`
- **Enable Rumble / Vibration** = `enabled`

> Regla general: **DualShock/analógico + Rumble activado**. Un mando "digital/standard"
> nunca pedirá vibración.

## 3. Mapear el control como puerto 1

`Ajustes → Entrada → Puerto 1` — asegúrate de que el Razer está asignado al
**Puerto 1** (que es el que la mayoría de juegos de 1 jugador usan para rumble).

## 4. Comprobación rápida

Juegos de PS1 con rumble muy evidente para probar:
- *Ape Escape* (requiere DualShock, vibración constante).
- *Metal Gear Solid* (vibración en alerta / vía de comunicaciones).
- *Gran Turismo 2* (golpes/derrapes).

Si con esto no vibra y `rumble-bridge` confirmó `hasVibrator = true` y que *Probar*
sí mueve los motores, el problema está en el **eslabón 3** → aplica el parche de
`patches/`. Si `rumble-bridge` dice `hasVibrator = false`, estás en el **Camino B**
(ver `ANALISIS-TECNICO.md`).
