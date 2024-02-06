package com.leohearts.alternativeUnlockHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;

public class HookClass implements IXposedHookLoadPackage {
    public String TAG = "alternativeUnlockHook";

    // NOTE: When modifying this, make sure credential sufficiency validation logic is intact.
    public static final int CREDENTIAL_TYPE_NONE = -1;
    public static final int CREDENTIAL_TYPE_PATTERN = 1;
    // This is the legacy value persisted on disk. Never return it to clients, but internally
    // we still need it to handle upgrade cases.
    public static final int CREDENTIAL_TYPE_PASSWORD_OR_PIN = 2;
    public static final int CREDENTIAL_TYPE_PIN = 3;
    public static final int CREDENTIAL_TYPE_PASSWORD = 4;

    private String fakePassword = "114514";
    private String realPassword = "***REMOVED***";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        Log.i(TAG, "handleLoadPackage: Loaded app");
        try {
            Properties properties = new Properties();
            properties.load(new FileReader("/data/local/tmp/alternativePass.properties"));
            fakePassword = properties.getProperty("fakePassword");
            realPassword = properties.getProperty("realPassword");
        } catch (Exception e) {
            if (e.getClass() != FileNotFoundException.class){
                e.printStackTrace();
            }
        }
        Class<?> LockPatternUtils = XposedHelpers.findClass("com.android.internal.widget.LockPatternUtils", lpparam.classLoader);
        XposedBridge.hookAllMethods(LockPatternUtils, "checkCredential", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                Log.i(TAG, "beforeHookedMethod: Hooked " + param.method.getName());
                Object mCredential = param.args[0];
                Log.d(TAG, "Cred: " + param.args[1].getClass());
                byte[] cred = (byte[])(XposedHelpers.callMethod(mCredential, "getCredential")); // from android 14
                String credStr = new String(cred);
                Log.d(TAG, "credStr: " + credStr);
                Log.d(TAG, "credType: " + (int)XposedHelpers.callMethod(mCredential, "getType"));
                Log.d(TAG, "credBytes: " + cred.length + Arrays.toString(cred));
                if (credStr.equals(fakePassword)){
                    Log.i(TAG, "replaceCred: detected");
                    param.args[0] = XposedHelpers.callMethod(mCredential, "createPin", (CharSequence) realPassword);
                    Log.i(TAG, "replaceCred: replaced");
                }
            }
        });
    }
}
