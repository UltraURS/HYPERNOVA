// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.update;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.lang.reflect.Method;

public final class MiFitnessUpdateHook {
    private MiFitnessUpdateHook() { }

    public static void install(ClassLoader loader) {
        Class<?> result = XposedHelpers.findClass("com.mi.health.appupgrade.model.AppUpgradeResult", loader);
        Method versionType = XposedHelpers.findMethodExact(result, "getNew_version");
        XposedHelpers.findAndHookMethod("com.mi.health.appupgrade.AppUpgradeUtil", loader, "showUpdateDialogIfNeed",
            "androidx.fragment.app.FragmentManager", result, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Mi Fitness omits the cancel button for type 2 and for sync-error prompts.
                    if ((boolean) param.args[2] || (int) versionType.invoke(param.args[1]) == 2)
                        param.setResult(null);
                }
            });
        HookLog.info("mi-fitness force-update dialog hook installed");
    }
}
