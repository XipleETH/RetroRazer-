.class public Lcom/retrorazer/RRBridge;
.super Ljava/lang/Object;
.source "RRBridge.java"

# Clase-puente inyectada en RetroArch.
#
#  rumble(ctx, effect, strength):
#     Reenvía el rumble real del juego a la app RetroRazer por broadcast, para que
#     el servicio háptico lo convierta en vibración del Kishi (Audio Haptics).
#
#  blockCapture(ctx):
#     Marca el audio de RetroArch como NO capturable (ALLOW_CAPTURE_BY_NONE), para
#     que Razer Nexus NO derive vibración del sonido del juego. Así solo vibra la
#     señal de rumble de RetroRazer (nuestra app sí es capturable). API 29+.

# direct methods
.method public static rumble(Landroid/content/Context;II)V
    .locals 2

    if-eqz p0, :cond_0

    new-instance v0, Landroid/content/Intent;

    const-string v1, "com.retrorazer.rumblebridge.RUMBLE"

    invoke-direct {v0, v1}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V

    const-string v1, "com.retrorazer.rumblebridge"

    invoke-virtual {v0, v1}, Landroid/content/Intent;->setPackage(Ljava/lang/String;)Landroid/content/Intent;

    const-string v1, "s"

    invoke-virtual {v0, v1, p2}, Landroid/content/Intent;->putExtra(Ljava/lang/String;I)Landroid/content/Intent;

    const-string v1, "e"

    invoke-virtual {v0, v1, p1}, Landroid/content/Intent;->putExtra(Ljava/lang/String;I)Landroid/content/Intent;

    invoke-virtual {p0, v0}, Landroid/content/Context;->sendBroadcast(Landroid/content/Intent;)V

    :cond_0
    return-void
.end method

.method public static blockCapture(Landroid/content/Context;)V
    .locals 2

    if-eqz p0, :cond_0

    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v1, 0x1d

    if-lt v0, v1, :cond_0

    const-string v0, "audio"

    invoke-virtual {p0, v0}, Landroid/content/Context;->getSystemService(Ljava/lang/String;)Ljava/lang/Object;

    move-result-object v0

    instance-of v1, v0, Landroid/media/AudioManager;

    if-eqz v1, :cond_0

    check-cast v0, Landroid/media/AudioManager;

    const/4 v1, 0x3

    invoke-virtual {v0, v1}, Landroid/media/AudioManager;->setAllowedCapturePolicy(I)V

    :cond_0
    return-void
.end method
