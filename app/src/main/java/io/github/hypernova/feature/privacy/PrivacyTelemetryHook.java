// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.privacy;

import android.content.Context;
import android.content.Intent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

public final class PrivacyTelemetryHook {
    private static final String SYSTEM_HELPER_CONTROL = "com.mobiletools.systemhelper.control.";

    private PrivacyTelemetryHook() { }

    public static void installSystemHelper(ClassLoader loader, boolean appStats, boolean signalStats) {
        if (!appStats && !signalStats) return;
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            if (appStats) hooks.add(skipReceiver(loader, "AppInfoRegisterReceiver"));
            if (signalStats) {
                hooks.add(skipReceiver(loader, "GetSignalInfoReceiver"));
                hooks.add(skipReceiver(loader, "SignalInfoRegisterReceiver"));
            }
            hooks.add(XposedHelpers.findAndHookMethod(SYSTEM_HELPER_CONTROL + "e", loader, "a",
                JSONObject.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.args[0] instanceof JSONObject record)) return;
                        // The host has already dequeued this record. Keep registration records on the native send path.
                        String type = record.optString("RegType");
                        if ((appStats && "A".equals(type)) || (signalStats && "R".equals(type))) {
                            param.setResult(false);
                        }
                    }
                }));
            HookLog.info("systemhelper-telemetry INSTALLED appStats=" + appStats + " signalStats=" + signalStats);
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("systemhelper-telemetry install", error);
        }
    }

    private static XC_MethodHook.Unhook skipReceiver(ClassLoader loader, String name) {
        return XposedHelpers.findAndHookMethod(SYSTEM_HELPER_CONTROL + name, loader, "onReceive",
            Context.class, Intent.class, XC_MethodReplacement.returnConstant(null));
    }

    public static void installRegService(ClassLoader loader) {
        // The APP_REPORT_SWITCH gate covers scheduling, collection and retries for app statistics.
        XposedHelpers.findAndHookMethod("u0.i", loader, "g", XC_MethodReplacement.returnConstant(false));
        HookLog.info("regservice-app-stats INSTALLED");
    }

    public static void installAnalytics(ClassLoader loader) {
        // This entry point schedules the onetrack_usage collector for both transport branches.
        XposedHelpers.findAndHookMethod("a.b.a.e.g", loader, "h",
            boolean.class, XC_MethodReplacement.returnConstant(null));
        HookLog.info("analytics-usage-stats INSTALLED");
    }
}
