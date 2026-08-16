# Vibraciones ricas nivel PS5 en el Kishi V2 Pro — investigación

> Del **zumbador** actual a una **síntesis wideband** estilo DualSense/Core Haptics.
> Este documento fusiona 5 líneas de investigación + análisis empírico de la captura real de
> Razer Nexus + una pasada de crítica adversarial. Marca de forma explícita lo **medido**, lo
> **anclado por física** y lo **por confirmar**. Los números del código salen de
> [`RRSensa.java`](RRSensa.java) verificado.

## TL;DR

- El actuador del Kishi V2 Pro es **de la misma clase que el del PS5 DualSense**: háptica *wideband*
  por forma de onda (voice-coil/LRA), **dos actuadores** (uno por grip, confirmado por teardown de
  iFixit), manejados por un stream PCM — no un motor ERM de zumbido.
- Por tanto **"nivel PS5" es alcanzable y es 100% un problema de síntesis de software.** El techo real
  NO está en el actuador sino en la **entrada** (RetroArch nos da 2 escalares a ~60 Hz; el juego PS1 no
  manda ni frecuencia ni transitorios). La riqueza hay que **inventarla por síntesis**.
- Nuestro RRSensa hoy manda una **cuadrada de amplitud constante** = desperdicia el actuador.
- **La incógnita que bloquea todo lo demás:** el sample-rate real y el ancho de banda del actuador NO
  se pueden medir de la captura (no tiene timestamps). Hay que anclarlos con **un barrido de frecuencia
  en hardware (EXP-1)** antes de fijar cualquier cifra en Hz.

---

## 1. Cómo funcionan REALMENTE los motores del Kishi (medido)

### 1.1 Tipo de actuador — wideband, no zumbador (alta confianza)

Las líneas de hardware convergen: el Kishi V2 Pro implementa **Razer HyperSense / Sensa HD Haptics,
powered by Interhaptics** (Razer compró Interhaptics). El diseño explícito es *audio-to-haptics* con
latencia baja — la misma filosofía que el DualSense (2 actuadores voice-coil manejados como una pista
de audio PCM). Interhaptics modela la háptica con dos primitivas: **Transients** (clicks) y **Continuos**
(tono sostenido con envolvente amplitud+frecuencia). Por eso lo que capturamos es una *forma de onda*,
no un comando "vibra a 150 Hz".

La firma estadística de la captura **prueba** wideband y **refuta** el modelo de buzzer:

| Métrica (captura Nexus) | Valor | Qué significa |
|---|---|---|
| Pico \|amp\| | 14067 = **42.9 % del full-scale int16** | Nexus deja ~7 dB de headroom, nunca satura |
| **Crest factor (por canal)** | **chA 4.04 · chB 3.91** | Rico en dinámica. Una cuadrada constante da crest ~1.0–1.4 |
| RMS por frame | 976 → 7328 (**rango 7.5×**), pico en frame 235/300 | Efecto **dinámico con envolvente**, no amplitud plana |
| DC | ≈ 0 | AC simétrica, sin rectificar |

### 1.2 Estructura de canal — ESTÉREO de 12 pares entrelazados; cada canal es band-limited

Resuelto empíricamente (Python sobre los 300 frames, des-entrelazando muestras pares/impares):

- Cada frame = **12 pares entrelazados A,B,A,B…** (muestra 0,2,4… = canal A; 1,3,5… = canal B). El
  modelo alternativo "bloque [12L|12R]" queda descartado; `autocorr` mono `lag2(0.799) > lag1(0.547)`
  es la firma clásica de dos canales entrelazados.
- **Corrección importante sobre la "energía cerca de Nyquist":** en el stream **mono** el 24.7 % de la
  energía cae en la banda alta — pero eso es el **zigzag del entrelazado**. **Des-entrelazado, cada canal
  tiene solo 0.2 % de energía alta** → cada canal es una **onda suave, band-limited**. O sea el actuador
  **NO** recibe clicks agudos muestra-a-muestra; recibe una portadora suave cuya *riqueza está en la
  envolvente de amplitud* (crest 4.0) y en un contenido armónico moderado, no en transitorios a tasa de
  audio. (Esto matiza el reporte inicial, que había leído la energía Nyquist del mono como "clicks reales".)
- Los dos canales están **en fase (lag 0)** pero son **distintos** (`corr(chA,chB)=0.627`, y espectros
  algo diferentes) = **háptica estéreo real de 2 actuadores**, no un mono duplicado. Lo corrobora el
  teardown (2 motores físicos).

> **Nota de código:** [`RRSensa.java:203-205`](RRSensa.java) ya escribe el entrelazado A,B correcto.
> PERO **rutea `effect0→canal A` y `effect1→canal B`** ([`:193-194`](RRSensa.java)), lo cual es el
> modelo equivocado: los dos canales físicos son **L/R (izq/der grip)**, no "fuerte/débil". Ver §4.

### 1.3 Espectro y la INCÓGNITA CLAVE: el sample-rate (sin medir)

El contenido por canal es band-limited alrededor de una fundamental baja + armónicos moderados
(los canales difieren: chB fundamental ~0.032 cyc/muestra; chA con más peso en ~0.13). Pero **todo el
eje en Hz depende del sample-rate real, que NO se puede medir de esta captura** (Nexus no dejó timestamps
por frame). Aquí hay que ser honesto:

| Fuente | Sample-rate por canal | Frame-rate implícito | Base |
|---|---|---|---|
| **Nuestro código** (`FPS=1500`, `sampleRate=12·FPS`) | 18000 Hz/canal | 1500 fps | **valor NUESTRO, no medido** |
| Ancla por resonancia LRA (~150–230 Hz) | **~1900–5300 Hz/canal** | ~160–440 fps | ancla física |

**Resolución de la aparente contradicción "wideband vs SR bajo":** "wideband háptico" **no** significa
banda de audio. Significa que el actuador cubre con riqueza un rango háptico de **decenas a ~300 Hz**.
Ese contenido se transmite con un PCM de **unos pocos kHz** por canal, no de 18 kHz. Casi con seguridad
enviamos frames **mucho más rápido** de lo que el firmware los consume, y por eso nuestra perilla
`freqHz=150` está **descalibrada** (si el firmware reproduce a ~5 kHz/canal pero calculamos la fase
contra 18000, "150 Hz" suena físicamente a ~40 Hz). **Ninguna cifra en Hz de este documento es fiable
hasta anclar la resonancia y el ancho de banda −3 dB con EXP-1.** (Ojo: aún no está verificado que los
frames a 1500 fps se acepten sin NAK — el código descarta el retorno de `bulkTransfer`; hay que medirlo.)

### 1.4 Formato de frame (verificado 100 %, no tocar)

Cabecera 10 B `55 aa 00 00 00 00 00 30 fe 79` · 24× int16 LE en bytes[10..57] · bytes[58..61]=0 ·
**byte62 = CRC8(poly=0x01, init=0, sobre bytes[2..61])** confirmado **300/300** · byte63=0. Sin
índice/nonce. ENABLE = 6 SET_REPORT de 90 B a iface#3. El código ya lo reproduce bien.

---

## 2. Por qué "nivel PS5" es alcanzable — y dónde está el límite real

### 2.1 El hardware NO es el techo
Tu propia captura demuestra que el actuador **ya produce wideband con crest 4.0 y 43 % de headroom**
cuando lo maneja Nexus. Reproduce formas de onda arbitrarias en la banda háptica. La "riqueza
profesional" es alcanzable por software.

### 2.2 El techo REAL está en la ENTRADA
`retro_set_rumble_state(port, effect, strength)` entrega **solo 2 escalares latcheados a ~60 Hz**:
- `effect 0` = motor STRONG. En PS1 es **analógico 8-bit (0..255)** → por eso medimos `~0..255` y
  `scaleMax=300` es correcto.
- `effect 1` = motor WEAK. En PS1 es **1-bit on/off** → esencialmente un booleano, no una amplitud.

La fuente **no tiene** frecuencia, sharpness, transitorios ni forma de onda. El "crest 4.0" de Nexus
**NO viene del juego** — Nexus lo **sintetiza/deriva del audio**. Conclusión estratégica: la riqueza se
**infiere y sintetiza** a partir de la envolvente de amplitud y sus **flancos** (cada subida brusca =
un impacto).

### 2.3 El gap exacto: buzzer actual vs. lo que el actuador puede dar

| Dimensión | Hoy (`RRSensa.java`) | Lo que puede el actuador | Gap |
|---|---|---|---|
| Forma de onda | cuadrada/seno **amplitud constante** | portadora suave con envolvente dinámica | falta toda la dinámica |
| Crest factor | ~1.0 | ~4.0 (medido) | suena "barato" por esto |
| Transitorios/onsets | ninguno | énfasis en cada impacto | causa nº1 de "no se siente vivo" |
| Frecuencia/sharpness | `freqHz=150` **fijo** | variable en la banda háptica | falta el eje sharpness |
| Envolvente | `floor 0.18` + curva, **sin release** | ataque agudo + decay natural | causa el **"golpe al final"** |
| Headroom | `maxAmp=24000` (73 % FS) | pico ~43 % FS, dinámica 7.5× | sobre-comprimido |
| Canales | 2 ondas idénticas en fase | 2 ondas L/R distintas | estéreo háptico sin usar |
| Textura | ninguna | ruido band-limited para materiales | motores/superficies "muertos" |

---

## 3. Qué significa "rico" aquí (reinterpretado tras la corrección)

Dado que **cada canal es band-limited** y el SR real es modesto, "nivel DualSense" en este hardware
**no** son clicks a tasa de audio, sino:
1. **Dinámica de envolvente** — ataque agudo + decay natural, sin piso, para que un disparo *decaiga* en
   vez de rebotar (crest ~4.0 = envolvente con picos).
2. **Eje sharpness** — variar la frecuencia de la portadora *dentro de la banda del actuador* con la
   intensidad, no solo la amplitud.
3. **Énfasis de transitorio en onsets** — un pulso corto de mayor frecuencia **dentro de la banda medida**
   (p.ej. ~200–300 Hz, NO 800 Hz, salvo que EXP-1 confirme ese ancho de banda).
4. **Estéreo** — las dos empuñaduras con ondas distintas (localización).
5. **Textura** — ruido band-limited gateado, para materialidad de motores/superficies.

---

## 4. Plan de rediseño de la síntesis (priorizado)

Modelo objetivo: **3 capas estilo Core Haptics** sintetizadas muestra-a-muestra y ruteadas a 2 actuadores:
```
s_out = softclip( env_cont*osc_cont  +  env_trans*osc_trans  +  texGain*noise )
```
Todo O(1) por muestra (LUT de seno), cabe en el hilo `URGENT_AUDIO` actual.

> **Regla que atraviesa todo el §4:** expresa envolventes y frecuencias en forma **rate-agnóstica**
> (coef one-pole `k = 1 − exp(−1/(SR·T))`, frecuencias en cyc/muestra) con **`SR` como una sola variable
> global** que fijas tras EXP-1. **No hardcodear ms/Hz contra 18000 hasta anclar.**

**[P0 — GATE] EXP-1 primero: anclar sample-rate + ancho de banda −3 dB.** Bloqueante. Sin esto, todas las
frecuencias son relativas y el feel es impredecible (§5).

**[P0] Matar el "golpe al final".** Causa: `floorFrac=0.18` ([`:46`](RRSensa.java)) fuerza un piso audible
para cualquier `strength>0`, y al llegar a 0 corta en seco; además la cuadrada no tiene release. Fix:
`floorFrac → 0` **y** envolvente one-pole **asimétrica** aplicada al oscilador (el release es lo que de
verdad lo arregla; un corte a mitad de onda hace click aunque el piso sea 0):
```
target = map(strengthSuavizado)                 // control-rate
por muestra: k = (target>env) ? kAtt : kRel;  env += (target-env)*k
kAtt = 1-exp(-1/(SR*Ta))   Ta≈8ms
kRel = 1-exp(-1/(SR*Trel)) Trel≈120ms → a cero suave
```
Antes de culpar solo al floor, loguear la línea de tiempo CRUDA de `strength` (EXP-6) para descartar que
el core mande un pulso fuerte final.

**[P0] Corregir amplitud/headroom.** Baja de `maxAmp=24000` (73 % FS) a un régimen que replique la firma:
continuo con pico `A_cont ≈ 5000`, transitorios hasta `~14000` (≈43 % FS), **soft-clip** `s=PEAK·tanh(x/PEAK)`
con `PEAK ≈ 16000`. **Trata el 43 % FS de Nexus como probable techo seguro/lineal**, no como "7 dB libres":
no excederlo sin vigilar distorsión/temperatura.

**[P0/P1] Capa transitoria + detección de onset.** El corazón del "nivel PS5". Separar por efecto:
```
// effect0 (analógico): escala por el flanco
base += (s-base)*kBase                 // baseline lenta ~200ms
rise = s - sPrev; sPrev = s
if rise>ONSET_DELTA && s>S_MIN && framesDesdeUltimo>REFRACTORY:   // SOLO cruces positivos
    A_trans = clamp(rise/255,0,1)*14000
// effect1 (booleano): cualquier 0->1 dispara un transitorio LIGERO FIJO (no escalado)
```
Click con **ataque raised-cosine ~0.8 ms** (evita pop de DC) + **decay exponencial** (eso es "decae suave
en vez de rebotar"). Frecuencia del click **dentro de la banda medida en EXP-1** (arranque tentativo:
fuerte ~ borde alto de la banda, ligero un poco más arriba; NO fijar 450/800 Hz antes de anclar).
Solo cruces positivos ⇒ nunca dispara al soltar.

**[P1] Portadora continua wideband.** Reemplaza la cuadrada por `osc = sinLUT(phase) + 0.3·sinLUT(2·phase)`
(2º armónico moderado, coherente con lo medido) siguiendo la envolvente ADSR. Suavizado anti-zipper del
control solo en la capa continua (`Tctl≈10ms`); la detección de onset usa el `strength` **crudo**.

**[P1] Eje sharpness (frecuencia con intensidad).** `f = fBanda·(1 + 0.45·n)`, `n = strength/scaleMax`.
Rango **contingente a EXP-1**: si la banda es ancha, ~50–300 Hz; si el actuador es resonante estrecho,
**colapsar** a modulación de amplitud+transitorio **cerca de la resonancia** (el band-split no funcionaría).

**[P1] Recuperar `oneShot`.** La inyección smali reenvía solo `{p0,p2,p3}` y descarta `p4=oneShot`.
Pasar `{p0,p2,p3,p4}` a `RRSensa.rumble(...)` y disparar transitorio directo si `oneShot!=0`. (Verificar
antes su derivación en `input/drivers/android_input.c` del APK objetivo — es artefacto del frontend, no
de libretro.)

**[P1] Ampliar `RRSENSA_TUNE`** con los parámetros nuevos (`contamp, transamp, attackms, releasems,
onsetdelta, refractms, bandlo, bandhi, texgain, peak, sendfps`) para A/B en vivo por `adb broadcast`.

**[P2] Modelo de canal correcto (L/R, no effect→canal).** La **entrada** es asimétrica (effect0 analógico
/ effect1 booleano) pero los **actuadores** son L/R simétricos. Sintetiza cuerpo+detalle+transitorio en
**cada** actuador (mezclando ambos efectos) e introduce **ligera descorrelación L/R** en la fundamental
(coherencia ~0.6 medida) manteniendo lag 0. Mantén el entrelazado A,B ya existente.

**[P2] Textura + send-rate.** Ruido LCG + bandpass 1-polo, `gain=0.12·env_cont`, gateado por el continuo.
Tras EXP-1, baja `sendfps` de 1500 al valor anclado (probable ~160–440 fps) → menos tráfico USB y menos
latencia de cola.

---

## 5. Plan de experimentos / calibración (vía broadcast `RRSENSA_TUNE`)

Ya existe el receptor con `testamp` (fuerza amplitud constante) — infraestructura ideal para A/B por
`adb shell am broadcast`. Primero ampliarlo (P1 arriba).

- **EXP-1 — ANCLAJE (bloqueante).** Con `testamp` fijo y `wave=sine`, barrer `freq` 40→400 Hz nominales y
  medir dónde **pica la salida física** y su **ancho de banda −3 dB** (acelerómetro del teléfono pegado al
  grip, o micro de contacto; percepción como fallback). Da el `SR` real y decide si band-split/sharpness/
  transitorios agudos son posibles.
- **EXP-2 — Canal.** Enviar amplitud solo en muestras pares vs impares → sentir/medir qué grip vibra
  (confirma L/R; `swap` invierte).
- **EXP-3 — floor+release.** A/B `floorp=18` vs `floorp=0`+release 120 ms → ¿desaparece el "golpe al final"?
- **EXP-4 — transitorio on/off.** Mismo continuo, con y sin capa de onset → valida la tesis central.
- **EXP-5 — crest/headroom.** Barrido `A_cont`×`A_trans`×`PEAK` buscando crest ~4.0 sin bottoming; **no
  pasar de ~43 % FS** sin vigilar calor/distorsión.
- **EXP-6 — onset por juego.** Loguear (`adb logcat -s RRSensa`, ya imprime `s=`) el `max strength` y la
  distribución de flancos por core; ajustar `ONSET_DELTA/S_MIN/REFRACTORY`.
- **EXP-7 — send-rate.** Bajar `sendfps` hacia lo anclado; medir feel/latencia/estabilidad del bulk.
- **EXP-8 — vocabulario háptico (PRERREQUISITO, no opcional).** Recapturar de Nexus **varios** efectos
  (disparo, motor, impacto, explosión) **con timestamp por frame** → fija por fin el frame-rate real y
  evita el sobre-ajuste a una sola muestra.

---

## 6. Riesgos e incógnitas

1. **Sample-rate absoluto y ancho de banda −3 dB** — la mayor. Todo el eje Hz depende de EXP-1/EXP-8.
2. **Clase exacta / respuesta del actuador** — sin datasheet. Si fuera LRA **resonante estrecho**, los
   transitorios agudos y el band-split no rinden; hay que colapsar cerca de resonancia.
3. **Térmica y mecánica (FALTA en el plan original)** — manejar un voice-coil a ~43 % FS en cada onset +
   textura continua + drive fuera de resonancia genera **calor y bottoming**. Añadir guarda de
   duty-cycle / RMS temporal y evitar drive sostenido fuera de resonancia.
4. **Presupuesto de latencia** — frame de juego ~16 ms + suavizado 10 ms + cola de frames + buffer USB/
   firmware desconocido. El objetivo "<10 ms flanco→vibración" no está presupuestado; pacear a 1500 fps a
   un endpoint con buffer podría **añadir** latencia. Medir el retorno de `bulkTransfer` (hoy descartado).
5. **effect1 en los cores PS1** — confirmar que llega como booleano (Beetle PSX / PCSX-ReARMed / SwanStation)
   y la derivación de `oneShot` en `android_input.c` del APK objetivo.
6. **Semántica de las 6 SET_REPORT de enable** — sin decodificar; una podría fijar ganancia/modo estéreo/
   **sample-rate** (resolvería #1). No tocarlas mientras funcionen; plan de fallback si el enable cambia
   entre firmwares/lotes.
7. **Generalización** — la captura es UN efecto ~simétrico; otros podrían usar asimetría L/R o más FS
   (EXP-8).

---

## Apéndice — parámetros de arranque (relativos hasta anclar SR)

| Parámetro | Arranque | Nota |
|---|---|---|
| `A_cont` | 5000 | RMS → ~3500 medido |
| `A_trans` | ≤ 14000 | ≈ pico medido (~43 % FS) |
| `PEAK` (soft-clip) | 16000 (~49 % FS) | crest → ~4 |
| `floorFrac` | **0** | era 0.18 (artefacto) |
| ADSR continuo | Ta=8 ms, Trel=120 ms | coef = 1−exp(−1/(SR·T)) |
| Suavizado control | Tctl=10 ms | solo continuo |
| Onset | DELTA=35, S_MIN=20, REFRACTORY=45 ms, baseline τ=200 ms | escala 0..255, solo positivos |
| effect1 | transitorio ligero **fijo** en 0→1 | es booleano |
| Armónico continuo | +0.3·sin(2·phase) | 2º armónico medido |
| Bandas / freq transitorio | **medir en EXP-1** | NO hardcodear |
| Send-rate | anclar (prob. 160–440 fps) | era 1500, no medido |

*Medido sobre la captura de Nexus (300 frames / 7200 muestras). Todo lo marcado "anclar/EXP-1" está sin
verificar en hardware — la validación final de "wideband a rate anclado ≈ sensación DualSense" sigue
pendiente de prueba en el Kishi real.*
