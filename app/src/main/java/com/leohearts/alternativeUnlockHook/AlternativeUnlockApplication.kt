package com.leohearts.alternativeUnlockHook

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.io.IOException
import java.util.Properties
import java.util.concurrent.TimeUnit

const val PREFS_NAME = "alternative_unlock"
private const val LEGACY_CONFIG_PATH = "/data/local/tmp/alternativePass.properties"
private const val LEGACY_FALLBACK_PATH =
    "/data/user_de/0/com.android.systemui/no_backup/alternativePass.properties"
private const val MIGRATION_COMPLETE = "__legacy_migration_complete"

class AlternativeUnlockApplication : Application(), XposedServiceHelper.OnServiceListener {
    private lateinit var localPreferences: SharedPreferences

    @Volatile
    private var xposedService: XposedService? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        localPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        Log.i(TAG, "Xposed service bound: ${service.frameworkName} ${service.frameworkVersion}")
        syncToRemote()
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) xposedService = null
    }

    fun loadConfig(): Properties {
        importLegacyConfigOnce()
        return loadLocalProperties()
    }

    fun saveConfig(config: Properties): Boolean {
        config.remove("compatMode")
        config.remove("dynamicLoad")
        val localSaved = replacePreferences(localPreferences, config, includeMigrationMarker = true)
        if (localSaved) syncToRemote()
        return localSaved
    }

    private fun importLegacyConfigOnce() {
        if (localPreferences.getBoolean(MIGRATION_COMPLETE, false)) return

        val legacy = readLegacyProperties(LEGACY_CONFIG_PATH)
            ?: readLegacyProperties(LEGACY_FALLBACK_PATH)
            ?: Properties()
        legacy.remove("compatMode")
        legacy.remove("dynamicLoad")
        if (!replacePreferences(localPreferences, legacy, includeMigrationMarker = true)) {
            throw IOException("failed to persist migrated configuration")
        }
        syncToRemote()
    }

    private fun readLegacyProperties(path: String): Properties? {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat '$path'"))
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("timed out reading legacy configuration")
        }
        val output = process.inputStream.readBytes()
        val error = process.errorStream.readBytes().toString(Charsets.UTF_8)
        if (process.exitValue() != 0) {
            if (error.contains("No such file")) return null
            throw IOException("could not read legacy configuration: $error")
        }
        return Properties().apply { load(output.inputStream()) }
    }

    @Synchronized
    private fun syncToRemote() {
        val remote = runCatching { xposedService?.getRemotePreferences(PREFS_NAME) }.getOrNull()
            ?: return
        if (!replacePreferences(remote, loadLocalProperties(), includeMigrationMarker = false)) {
            Log.e(TAG, "Failed to synchronize Xposed Remote Preferences")
        }
    }

    private fun loadLocalProperties() = Properties().apply {
        localPreferences.all.forEach { (key, value) ->
            if (key != MIGRATION_COMPLETE && value is String) setProperty(key, value)
        }
    }

    private fun replacePreferences(
        preferences: SharedPreferences,
        config: Properties,
        includeMigrationMarker: Boolean,
    ): Boolean {
        val editor = preferences.edit().clear()
        config.stringPropertyNames().forEach { key -> editor.putString(key, config.getProperty(key)) }
        if (includeMigrationMarker) editor.putBoolean(MIGRATION_COMPLETE, true)
        return editor.commit()
    }

    companion object {
        lateinit var instance: AlternativeUnlockApplication
            private set
    }
}