package io.github.hypernova.hook;

import de.robv.android.xposed.XposedBridge;

public final class HookLog {
    private HookLog() { }
    public static void info(String message) { XposedBridge.log("[HYPERNOVA] " + message); }
    public static void error(String feature, Throwable error) {
        info(feature + " ERROR " + error.getClass().getSimpleName());
    }
}
