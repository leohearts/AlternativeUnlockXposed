@file:Suppress("SpellCheckingInspection", "GrazieInspection")

package com.leohearts.alternativeunlockhook

import android.annotation.SuppressLint
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.FileNotFoundException
import java.io.FileReader
import java.util.Objects
import java.util.Properties

class HookClass : IXposedHookLoadPackage {
    // NOTE: When modifying this, make sure credential sufficiency validation logic is intact.
    companion object {
        const val TAG: String = "alternativeUnlockHook"
        // wait if you stored inside `com.android.systemui`, why move to here? for dual-user support? `/data/user/0/` mightve worked
        const val CONFIG_PATH: String = "/data/local/tmp/alternativePass.properties"
    }

    private var fakePassword: String = "114514"
    private var realPassword: String = "1919810"
    private var actionType: String = "sh"
    private var actionCommand: String = "whoami"
    private var dynamicLoad: String = "false"

    @SuppressLint("SdCardPath")
    fun initConfig() {
        try {
            val properties = Properties()
            val f: FileReader = try {
                FileReader(CONFIG_PATH)
            } catch (_: FileNotFoundException) {
                FileReader("/data/data/com.android.systemui/alternativePass.properties") // make sure module can work if migration process hasn't been started
            }
            properties.load(f)
            fakePassword = properties.getProperty("fakePassword", "114514")
            realPassword = properties.getProperty(
                "realPassword", "1919810"
            ) // nobody sets 1919810 as real password, right ???
            actionType = properties.getProperty("actionType", "sh")
            actionCommand =
                properties.getProperty("actionCommand", "whoami") // dont do anything if unset
            dynamicLoad = properties.getProperty("dynamicLoad", "false")
        } catch (e: Exception) {
            if (e.javaClass != FileNotFoundException::class.java) {
                e.printStackTrace()
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        Log.i(TAG, "handleLoadPackage: Loaded app")
        initConfig()
        val lockPatternUtils = XposedHelpers.findClass(
            "com.android.internal.widget.LockPatternUtils", lpparam.classLoader
        )
        XposedBridge.hookAllMethods(lockPatternUtils, "checkCredential", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                super.beforeHookedMethod(param)
                if (Objects.equals(dynamicLoad, "true")) {
                    initConfig() // load config again for debugging
                }
                Log.i(TAG, "beforeHookedMethod: Hooked " + param.method.name)
                val mCredential = param.args[0]
                Log.d(TAG, "Cred: " + param.args[1].javaClass)
                val cred = XposedHelpers.callMethod(
                    mCredential, "getCredential"
                ) as ByteArray // from android 14
                val credStr = String(cred)
                val credType = XposedHelpers.callMethod(mCredential, "getType") as Int
                if (credStr == realPassword) {
                    Log.i(TAG, "realPassword detected, suppressing logs")
                } else {
                    Log.d(TAG, "credStr: $credStr")
                    Log.d(TAG, "credBytes: " + cred.size + cred.contentToString())
                }
                Log.d(TAG, "credType: $credType")
                if (credStr == fakePassword) {
                    Log.i(TAG, "replaceCred: detected")
                    try {
                        if (actionType.contains("sh")) { // foolproof (totally)
                            RootShell.system(actionCommand)
                        } else if (actionType.contains("sudo")) {
                            RootShell.sudo(actionCommand)
                        }
                    } catch (_: Exception) {
                    }
                    // replace with real password
                    param.args[0] = XposedHelpers.newInstance(
                        mCredential.javaClass, credType, realPassword as CharSequence
                    )
                    // this is the hacky way for less stability but more compatibility
                    // You will need to track the logcat with `adb logcat | grep alternativeUnlockHook` for more details about "how to convert my pattern to a string"
                    Log.i(TAG, "replaceCred: replaced")
                }
            }
        })
    }
}
