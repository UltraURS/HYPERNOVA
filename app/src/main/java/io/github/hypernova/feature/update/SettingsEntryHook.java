package io.github.hypernova.feature.update;

import android.content.ComponentName;
import android.content.Intent;
import java.net.URISyntaxException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;

public final class SettingsEntryHook {
    private SettingsEntryHook() { }
    public static void install(ClassLoader loader, boolean ota, boolean reset) throws Exception {
        Class<?> preference = XposedHelpers.findClass("androidx.preference.Preference", loader);
        Class<?> group = XposedHelpers.findClass("androidx.preference.PreferenceGroup", loader);
        Method key = preference.getDeclaredMethod("getKey");
        Method visible = preference.getDeclaredMethod("setVisible", boolean.class);
        Method add = group.getDeclaredMethod("addPreference", preference);
        Method recommendation = null;
        Method recommendationIntent = null;
        Method configuredIntent = null;
        if (reset) {
            Class<?> helper = XposedHelpers.findClass("com.android.settings.recommend.RecommendUIHelper", loader);
            Class<?> item = XposedHelpers.findClass("com.android.settings.recommend.bean.RecommendItem", loader);
            Class<?> link = XposedHelpers.findClass("com.android.settings.recommend.RecommendUIHelper$RecommendItemLinkData", loader);
            recommendation = helper.getDeclaredMethod("getLinkData", item);
            recommendationIntent = link.getDeclaredMethod("getIntent");
            recommendationIntent.setAccessible(true);
            configuredIntent = item.getDeclaredMethod("getIntent");
        }
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            hooks.add(XposedBridge.hookMethod(visible, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (EntryPolicy.hide((String) key.invoke(param.thisObject), ota, reset)) param.args[0] = false;
                }
            }));
            hooks.add(XposedBridge.hookMethod(add, new XC_MethodHook() {
                private boolean observed;
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object item = param.args[0];
                    if (item != null && EntryPolicy.hide((String) key.invoke(item), ota, reset)) {
                        visible.invoke(item, false);
                        if (!observed) { observed = true; HookLog.info("settings-entry OBSERVED"); }
                    }
                }
            }));
            if (reset) {
                final Method getIntent = recommendationIntent;
                final Method getConfiguredIntent = configuredIntent;
                hooks.add(XposedBridge.hookMethod(recommendation, new XC_MethodHook() {
                    private boolean observed;
                    @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args[0] == null) return;
                        String uri = (String) getConfiguredIntent.invoke(param.args[0]);
                        if (uri == null) return;
                        try {
                            // The verified asset identifies the reset item even when the search
                            // index later replaces its resolved Intent with a raw Intent.
                            if (isResetIntent(Intent.parseUri(uri, 0))) {
                                param.setResult(null);
                                observed();
                            }
                        } catch (URISyntaxException invalid) {
                            // Preserve the host's handling of malformed recommendation data.
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.hasThrowable() || param.getResult() == null) return;
                        Intent intent = (Intent) getIntent.invoke(param.getResult());
                        if (isResetIntent(intent)) {
                            // addRecommendItem treats null as "do not render this link".
                            param.setResult(null);
                            observed();
                        }
                    }
                    private void observed() {
                        if (!observed) { observed = true; HookLog.info("reset-recommendation OBSERVED"); }
                    }
                }));
                hooks.addAll(SettingsSearchHook.install(loader));
            }
            HookLog.info("settings-entry INSTALLED ota=" + ota + " reset=" + reset + " (UI only)");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            throw new IllegalStateException("Settings hook installation failed", error);
        }
    }

    private static boolean isResetIntent(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        return component != null && EntryPolicy.hideResetRecommendation(
            component.getPackageName(), component.getClassName(),
            intent.getStringExtra(":settings:show_fragment"));
    }
}
