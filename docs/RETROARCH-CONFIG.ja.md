<!-- 言語: 日本語 -->
[English](RETROARCH-CONFIG.md) · [Español](RETROARCH-CONFIG.es.md) · **日本語**

# ランブルのための RetroArch 設定

ブリッジが動作していても、コアがランブルを*要求*しなければ**何も感じません**。
「壊れている」と判断する前に、まず以下をすべて設定してください。

## 1. 入力のグローバル設定

`設定 → 入力`:

- **Rumble / 振動**: ON。
- ビルドにあれば: **Enable Device Vibration（デバイス振動を有効化）** → ON。

`retroarch.cfg` では:
```ini
input_rumble_enable = "true"
```

## 2. コアオプション: Rumble（PS1 では必須）

PS1 のゲームを起動した状態で: `クイックメニュー → オプション`。

### Beetle PSX / Beetle PSX HW
- **Rumble** = `enabled`
- コントローラーポートを **analog / DualShock** タイプにすること。

### PCSX-ReARMed
- **Rumble**（`pcsx_rearmed_vibration`）= `enabled`
- **Pad type for port 1**（`pcsx_rearmed_pad1type`）= `analog` または `dualshock`

### SwanStation / DuckStation-libretro
- **Controller 1 Type** = `Analog Controller (DualShock)`
- **Enable Rumble / Vibration** = `enabled`

> 目安: **DualShock/アナログ + Rumble ON**。「デジタル/標準」パッドは振動を
> 要求しません。

## 3. コントローラーをポート 1 に割り当てる

`設定 → 入力 → ポート 1` — Kishi が**ポート 1**に割り当てられていることを確認して
ください（多くの 1 人用ゲームはポート 1 でランブルを送ります）。

## 4. 簡単なテスト

ランブルが分かりやすい PS1 ゲーム:
- *Ape Escape（サルゲッチュ）*（DualShock 必須、常時振動）
- *Metal Gear Solid（メタルギアソリッド）*（警戒 / 無線）
- *Gran Turismo 2（グランツーリスモ2）*（衝突 / スリップ）

## トラブルシューティング

- 何も振動しない → **RetroRazer のブリッジサービスが動作中**で、**Razer Nexus の
  Audio Haptics = High**、メディア音量が上がっているか確認。
- それでも振動しない → 上記のコアオプション（DualShock + Rumble）を確認。
- 音声でも振動する → **パッチ済み RetroArch**（`RetroArch-RetroRazer.apk`）を使って
  ください。ゲーム音声をキャプチャ不可に設定しています。
