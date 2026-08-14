.class public Lcom/retrorazer/RRBridge;
.super Ljava/lang/Object;
.source "RRBridge.java"

# Clase-puente inyectada en RetroArch. Reenvía el rumble del juego a la app
# RetroRazer Rumble Bridge mediante un broadcast, para que el servicio háptico lo
# convierta en vibración del Kishi (Audio Haptics).
#
# Equivale a este Java:
#   public static void rumble(Context ctx, int effect, int strength) {
#       if (ctx == null) return;
#       Intent i = new Intent("com.retrorazer.rumblebridge.RUMBLE");
#       i.setPackage("com.retrorazer.rumblebridge");
#       i.putExtra("s", strength);
#       i.putExtra("e", effect);
#       ctx.sendBroadcast(i);
#   }

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
