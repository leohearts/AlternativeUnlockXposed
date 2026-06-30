package com.leohearts.alternativeUnlockHook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CommandReceiver : BroadcastReceiver() {
    private val tag = "alternativeUnlockHook"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.leohearts.alternativeUnlockHook.EXECUTE") return
        val command = intent.getStringExtra("command") ?: return
        val type = intent.getStringExtra("type") ?: "sh"
        Log.i(tag, "CommandReceiver: executing command=$command type=$type")
        val pendingResult = goAsync()
        Thread {
            try {
                val proc = if (type == "sudo") {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                } else {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                }
                val exit = proc.waitFor()
                Log.i(tag, "CommandReceiver: exited with $exit")
            } catch (e: Exception) {
                Log.e(tag, "CommandReceiver: failed", e)
            } finally {
                pendingResult.finish()
            }
        }.apply { name = "CommandReceiver" }.start()
    }
}
