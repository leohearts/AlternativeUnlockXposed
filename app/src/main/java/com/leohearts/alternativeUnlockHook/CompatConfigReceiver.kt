package com.leohearts.alternativeUnlockHook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

// The hook (inside SystemUI) sends this when it can not read CONFIG_PATH; mirror the
// config into the SystemUI-owned directory so the hook has a readable copy.
class CompatConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONFIG_FALLBACK_NEEDED) return
        val pending = goAsync()
        Thread {
            try {
                if (!sudoFileExists(CONFIG_PATH)) return@Thread
                val config = loadConfig().getOrNull()
                if (config == null) return@Thread
                config.setProperty("compatMode", "true")
                if (writeConfig(config)) {
                    syncFallback(true)
                    setPermission(config)
                    Log.i(TAG, "Enabled config fallback for SystemUI (compat mode)")
                    // this should only happen once when SystemUI restarts.
                } else {
                    Log.w(TAG, "compat fallback: failed to write config")
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
