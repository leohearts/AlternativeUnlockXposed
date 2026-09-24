package com.leohearts.alternativeUnlockHook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class HookClass implements IXposedHookLoadPackage {
    public String TAG = "alternativeUnlockHook";
    public String CONFIG_PATH = "/data/local/tmp/alternativePass.properties";


    // NOTE: When modifying this, make sure credential sufficiency validation logic is intact.
    public static final int CREDENTIAL_TYPE_NONE = -1;
    public static final int CREDENTIAL_TYPE_PATTERN = 1;
    // This is the legacy value persisted on disk. Never return it to clients, but internally
    // we still need it to handle upgrade cases.
    public static final int CREDENTIAL_TYPE_PASSWORD_OR_PIN = 2;
    public static final int CREDENTIAL_TYPE_PIN = 3;
    public static final int CREDENTIAL_TYPE_PASSWORD = 4;

    private String fakePassword = "114514";
    private String realPassword = "1919810";
    private String actionType = "sh";
    private String actionCommand = "whoami";
    private String useRegex = "false";
    private String skipRealPassword = "true";
    private String pamStyle = "false";
    private String commandTimeout = "5";
    private String dynamicLoad = "false";

    public Process sudo(String cmd, String auInput) throws IOException {
        Log.i(TAG, "sudo: " + cmd);
        ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
        if (auInput != null) pb.environment().put("AU_INPUT", auInput);
        // never read the output: an undrained pipe fills up (~64KB) and blocks the
        // command before it can exit, which the PAM waitFor would report as a timeout
        // Android ProcessBuilder has no Redirect.DISCARD; /dev/null achieves the same
        pb.redirectOutput(new java.io.File("/dev/null"));
        pb.redirectError(new java.io.File("/dev/null"));
        return pb.start();
    }
    public Process system(String cmd, String auInput) throws IOException {
        Log.i(TAG, "system: " + cmd);
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        if (auInput != null) pb.environment().put("AU_INPUT", auInput);
        // Android ProcessBuilder has no Redirect.DISCARD; /dev/null achieves the same
        pb.redirectOutput(new java.io.File("/dev/null"));
        pb.redirectError(new java.io.File("/dev/null"));
        return pb.start();
    }

    @SuppressLint("SdCardPath")
    public void initConfig(){
        try {
            Properties properties = new Properties();
            FileReader f;
            try {
                f = new FileReader(CONFIG_PATH);
            } catch (FileNotFoundException e) {
                f = new FileReader("/data/data/com.android.systemui/alternativePass.properties");   // make sure module can work if migration process hasn't been started
            }
            properties.load(f);
            fakePassword = properties.getProperty("fakePassword", "114514");
            realPassword = properties.getProperty("realPassword", "1919810"); // nobody sets 1919810 as real password , right ???
            actionType = properties.getProperty("actionType", "sh");
            actionCommand = properties.getProperty("actionCommand", "whoami"); // dont do anything if unset
            useRegex = properties.getProperty("useRegex", "false");
            skipRealPassword = properties.getProperty("skipRealPassword", "true");
            pamStyle = properties.getProperty("pamStyle", "false");
            commandTimeout = properties.getProperty("commandTimeout", "5");
            dynamicLoad = properties.getProperty("dynamicLoad", "false");
        } catch (Exception e) {
            if (e.getClass() != FileNotFoundException.class){
                e.printStackTrace();
            }
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        Log.i(TAG, "handleLoadPackage: Loaded app");
        initConfig();
        Class<?> LockPatternUtils = XposedHelpers.findClass("com.android.internal.widget.LockPatternUtils", lpparam.classLoader);
        XposedBridge.hookAllMethods(LockPatternUtils, "checkCredential", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                super.beforeHookedMethod(param);
                if (Objects.equals(dynamicLoad, "true")){
                    initConfig();   // load config again for debugging
                }
                Log.i(TAG, "beforeHookedMethod: Hooked " + param.method.getName());
                Object mCredential = param.args[0];
                Log.d(TAG, "Cred: " + param.args[1].getClass());
                byte[] cred = (byte[])(XposedHelpers.callMethod(mCredential, "getCredential")); // from android 14
                String credStr = new String(cred);
                int credType = (int)XposedHelpers.callMethod(mCredential, "getType");
                if (credStr.equals(realPassword)) {
                    Log.i(TAG, "realPassword detected, suppressing logs");
                }
                else {
                    Log.d(TAG, "credStr: " + credStr);
                    Log.d(TAG, "credBytes: " + cred.length + Arrays.toString(cred));
                }
                Log.d(TAG, "credType: " + credType);
                boolean matched;
                if (Objects.equals(useRegex, "true")) {
                    try {
                        matched = credStr.matches(fakePassword); // regex match, so ".+" matches any input
                    } catch (Exception e) {
                        matched = credStr.equals(fakePassword); // invalid regex: fall back to literal match
                    }
                } else {
                    matched = credStr.equals(fakePassword);
                }
                // skipRealPassword: the real password unlocks on its own, and skipping keeps it out of the command's environment
                if (matched && !(Objects.equals(skipRealPassword, "true") && credStr.equals(realPassword))){
                    Log.i(TAG, "replaceCred: detected");
                    boolean pam = Objects.equals(pamStyle, "true");
                    boolean unlock = true; // no command run (i.e. do nothing) means unlock
                    try {
                        Process p = null;
                        String auInput = pam ? credStr : null;
                        if (actionType.contains("sh")) { // foolproof
                            p = system(actionCommand, auInput);
                        } else if (actionType.contains("sudo")) {
                            p = sudo(actionCommand, auInput);
                        }
                        if (pam && p != null) { // pam_exec style: the command's exit status decides unlocking
                            long timeoutSec;
                            try {
                                timeoutSec = Long.parseLong(commandTimeout.trim());
                                if (timeoutSec <= 0) throw new NumberFormatException();
                            } catch (Exception e) {
                                timeoutSec = 5;
                            }
                            if (p.waitFor(timeoutSec, TimeUnit.SECONDS)) { // don't hang SystemUI forever
                                int code = p.exitValue();
                                unlock = code == 0;
                                Log.i(TAG, "actionCommand exit code: " + code);
                            } else {
                                p.destroyForcibly();
                                unlock = false;
                                Log.w(TAG, "actionCommand timed out, not unlocking");
                            }
                        }
                    } catch (Exception ignored) {}
                    if (unlock) {
                        // replace with real password
                        param.args[0] = XposedHelpers.newInstance(mCredential.getClass(), credType, (CharSequence) realPassword);
                        // this is the hacky way for less stability but more compatibility
                        // You will need to track the logcat with `adb logcat | grep alternativeUnlockHook` for more details about "how to convert my pattern to a string"
                        Log.i(TAG, "replaceCred: replaced");
                    }
                }
            }
        });
    }
}
