package com.leohearts.alternativeUnlockHook;

import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class HookClass extends XposedModule {
    private static final String TAG = "alternativeUnlockHook";
    private static final String SYSTEMUI_PNAME = "com.android.systemui";
    private static final String PREFS_NAME = "alternative_unlock";
    private static final String LOCK_PATTERN_UTILS =
            "com.android.internal.widget.LockPatternUtils";
    private static final String LOCKSCREEN_CREDENTIAL =
            "com.android.internal.widget.LockscreenCredential";

    public static final int CREDENTIAL_TYPE_PATTERN = 1;
    public static final int CREDENTIAL_TYPE_PIN = 3;

    private volatile String fakePassword = "114514";
    private volatile String realPassword = "1919810";
    private volatile String actionType = "sh";
    private volatile String actionCommand = "whoami";
    private volatile boolean useRegex;
    private volatile boolean skipRealPassword = true;
    private volatile boolean pamStyle;
    private volatile long commandTimeout = 5;

    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Module loaded in " + param.getProcessName()
                + " on " + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!SYSTEMUI_PNAME.equals(param.getPackageName()) || !param.isFirstPackage()) return;

        try {
            preferences = getRemotePreferences(PREFS_NAME);
            loadConfig(preferences);
            preferenceListener = (sharedPreferences, key) -> loadConfig(sharedPreferences);
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            hookCredentialCheck(param.getClassLoader());
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Failed to initialize SystemUI hook", throwable);
        }
    }

    private void hookCredentialCheck(ClassLoader classLoader) throws ClassNotFoundException {
        Class<?> target = classLoader.loadClass(LOCK_PATTERN_UTILS);
        int hooked = 0;
        for (Method method : target.getDeclaredMethods()) {
            if (!"checkCredential".equals(method.getName())) continue;
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes.length == 0
                    || !LOCKSCREEN_CREDENTIAL.equals(parameterTypes[0].getName())) {
                continue;
            }
            method.setAccessible(true);
            hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                List<Object> originalArgs = chain.getArgs();
                if (originalArgs.isEmpty() || originalArgs.get(0) == null) {
                    return chain.proceed();
                }

                Object credential = originalArgs.get(0);
                byte[] credentialBytes = (byte[]) invokeNoArgs(credential, "getCredential");
                String credentialString = new String(credentialBytes, StandardCharsets.UTF_8);
                int credentialType = (int) invokeNoArgs(credential, "getType");

                if (credentialString.equals(realPassword)) {
                    log(Log.INFO, TAG, "Real password detected; suppressing credential logs");
                } else {
                    log(Log.DEBUG, TAG, "Credential type=" + credentialType
                            + " bytes=" + credentialBytes.length + Arrays.toString(credentialBytes));
                    if (credentialType == CREDENTIAL_TYPE_PATTERN) {
                        // Android encodes pattern cells as ASCII '1' through '9'. This explicit
                        // value is needed once during pattern setup; see the README instructions.
                        log(Log.DEBUG, TAG, "Pattern credential code=" + credentialString);
                    }
                }

                boolean matched = matchesAlternativeCredential(credentialString);
                if (!matched || (skipRealPassword && credentialString.equals(realPassword))) {
                    return chain.proceed();
                }

                log(Log.INFO, TAG, "Alternative credential detected");
                if (!runAction(credentialString)) return chain.proceed();

                Object[] replacementArgs = originalArgs.toArray();
                Object replacementCredential =
                        createCredential(credential.getClass(), credentialType, realPassword);
                replacementArgs[0] = replacementCredential;
                log(Log.INFO, TAG, "Credential replaced");
                try {
                    return chain.proceed(replacementArgs);
                } finally {
                    // LockPatternUtils performs the Binder check synchronously. Its caller owns
                    // the original credential, so explicitly clear the replacement created here.
                    try {
                        invokeNoArgs(replacementCredential, "zeroize");
                    } catch (Throwable throwable) {
                        log(Log.WARN, TAG, "Failed to zeroize replacement credential", throwable);
                    }
                }
            });
            hooked++;
        }
        if (hooked == 0) {
            throw new NoSuchMethodError(LOCK_PATTERN_UTILS + ".checkCredential");
        }
        log(Log.INFO, TAG, "Hooked " + hooked + " checkCredential overload(s)");
    }

    private void loadConfig(SharedPreferences prefs) {
        fakePassword = prefs.getString("fakePassword", "114514");
        realPassword = prefs.getString("realPassword", "1919810");
        actionType = prefs.getString("actionType", "sh");
        actionCommand = prefs.getString("actionCommand", "whoami");
        useRegex = Boolean.parseBoolean(prefs.getString("useRegex", "false"));
        skipRealPassword = Boolean.parseBoolean(prefs.getString("skipRealPassword", "true"));
        pamStyle = Boolean.parseBoolean(prefs.getString("pamStyle", "false"));
        try {
            commandTimeout = Long.parseLong(prefs.getString("commandTimeout", "5").trim());
            if (commandTimeout <= 0) commandTimeout = 5;
        } catch (RuntimeException ignored) {
            commandTimeout = 5;
        }
        log(Log.INFO, TAG, "Configuration loaded from Xposed Remote Preferences");
    }

    private boolean matchesAlternativeCredential(String credential) {
        if (!useRegex) return credential.equals(fakePassword);
        try {
            return credential.matches(fakePassword);
        } catch (RuntimeException ignored) {
            return credential.equals(fakePassword);
        }
    }

    private boolean runAction(String credential) {
        Process process = null;
        try {
            String input = pamStyle ? credential : null;
            if (actionType.contains("sh")) {
                process = startProcess(new String[]{"sh", "-c", actionCommand}, input);
            } else if (actionType.contains("sudo")) {
                process = startProcess(new String[]{"su", "-c", actionCommand}, input);
            }

            if (!pamStyle || process == null) return true;
            if (!process.waitFor(commandTimeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log(Log.WARN, TAG, "Action timed out; credential not replaced");
                return false;
            }
            int exitCode = process.exitValue();
            log(Log.INFO, TAG, "Action exit code=" + exitCode);
            return exitCode == 0;
        } catch (Exception exception) {
            log(Log.ERROR, TAG, "Action failed", exception);
            return !pamStyle;
        }
    }

    private Process startProcess(String[] command, String input) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (input != null) builder.environment().put("AU_INPUT", input);
        builder.redirectOutput(new File("/dev/null"));
        builder.redirectError(new File("/dev/null"));
        return builder.start();
    }

    private static Object invokeNoArgs(Object receiver, String methodName) throws Exception {
        Method method = findDeclaredMethod(receiver.getClass(), methodName);
        method.setAccessible(true);
        return method.invoke(receiver);
    }

    private static Method findDeclaredMethod(Class<?> type, String methodName,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                // Some OEMs return a LockscreenCredential subclass whose accessors are inherited.
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + methodName);
    }

    private static Object createCredential(Class<?> credentialClass, int type, String password)
            throws Exception {
        for (Constructor<?> constructor : credentialClass.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != 2 || parameters[0] != int.class) continue;
            constructor.setAccessible(true);
            if (parameters[1] == byte[].class) {
                return constructor.newInstance(type, password.getBytes(StandardCharsets.UTF_8));
            }
            if (CharSequence.class.isAssignableFrom(parameters[1])) {
                return constructor.newInstance(type, password);
            }
        }

        String factory = type == CREDENTIAL_TYPE_PIN ? "createPin" : "createPassword";
        if (type == CREDENTIAL_TYPE_PATTERN) factory = "createPattern";
        Method method = credentialClass.getDeclaredMethod(factory, CharSequence.class);
        method.setAccessible(true);
        return method.invoke(null, password);
    }
}