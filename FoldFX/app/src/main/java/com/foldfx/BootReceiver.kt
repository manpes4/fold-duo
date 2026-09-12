package com.foldfx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Relance le service au démarrage du téléphone ou après une mise à jour de l'app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.enabled(context)) return
        if (!Settings.canDrawOverlays(context)) return
        try {
            context.startForegroundService(Intent(context, FoldFxService::class.java))
        } catch (_: Exception) {
        }
    }
}
