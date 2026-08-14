<!-- Language: English (default) -->
**English** · [Español](RETROARCH-CONFIG.es.md) · [日本語](RETROARCH-CONFIG.ja.md)

# RetroArch configuration for rumble

Even with the bridge working, you feel **nothing** if the core doesn't *request*
rumble. Apply all of this before assuming something is broken.

## 1. Global input settings

`Settings → Input`:

- **Rumble / Vibration**: ON.
- If your build has it: **Enable Device Vibration** → ON.

In `retroarch.cfg`:
```ini
input_rumble_enable = "true"
```

## 2. Core option: Rumble (required for PS1)

With a PS1 game loaded: `Quick Menu → Options`.

### Beetle PSX / Beetle PSX HW
- **Rumble** = `enabled`
- Controller port must be an **analog / DualShock** type.

### PCSX-ReARMed
- **Rumble** (`pcsx_rearmed_vibration`) = `enabled`
- **Pad type for port 1** (`pcsx_rearmed_pad1type`) = `analog` or `dualshock`

### SwanStation / DuckStation-libretro
- **Controller 1 Type** = `Analog Controller (DualShock)`
- **Enable Rumble / Vibration** = `enabled`

> Rule of thumb: **DualShock/analog + Rumble ON**. A "digital/standard" pad never
> asks for vibration.

## 3. Map the controller to port 1

`Settings → Input → Port 1` — make sure the Kishi is assigned to **Port 1** (most
1‑player games drive rumble on port 1).

## 4. Quick test

PS1 games with obvious rumble:
- *Ape Escape* (needs DualShock, constant vibration)
- *Metal Gear Solid* (alert / codec buzz)
- *Gran Turismo 2* (impacts / skids)

## Troubleshooting

- Nothing vibrates → confirm the **RetroRazer bridge service is running** and
  **Razer Nexus Audio Haptics = High**, media volume up.
- Still nothing → check core options above (DualShock + Rumble).
- Audio also vibrates → make sure you use the **patched RetroArch**
  (`RetroArch-RetroRazer.apk`), which marks game audio as non-capturable.
