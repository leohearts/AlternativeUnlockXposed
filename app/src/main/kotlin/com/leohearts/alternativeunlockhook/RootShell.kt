package com.leohearts.alternativeunlockhook

import android.util.Log

object RootShell {
    fun sudo(cmd: String): Process? {
        return try {
            Log.i(HookClass.TAG, "sudo: $cmd")
            Runtime.getRuntime().exec(arrayOf("su", "-mm", "-c", cmd)) // use mount-master/mm for ksu related kernel managers' failing to read the file
        } catch (_: Exception) {
            Log.e(HookClass.TAG, "Failed to execute su: $cmd")
            null
        }
    }

    fun system(cmd: String): Process? {
        return try {
            Log.i(HookClass.TAG, "system: $cmd")
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        } catch (_: Exception) {
            Log.e(HookClass.TAG, "Failed to execute sh: $cmd")
            null
        }
    }
}
