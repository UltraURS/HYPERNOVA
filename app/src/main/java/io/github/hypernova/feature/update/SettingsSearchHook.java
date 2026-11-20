package io.github.hypernova.feature.update;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;

final class SettingsSearchHook {
    private SettingsSearchHook() { }
    static List<XC_MethodHook.Unhook> install(ClassLoader loader) throws Exception {
        Class<?> item = XposedHelpers.findClass("com.android.settings.search.SearchResultItem", loader);
        Class<?> result = XposedHelpers.findClass("com.android.settings.search.SearchResult", loader);
        Class<?> adapter = XposedHelpers.findClass("com.android.settings.SettingsFragment$SearchResultAdapter", loader);
        Field key = item.getField("preferenceKey"), pkg = item.getField("pkg"), intent = item.getField("intent");
        Method query = result.getDeclaredMethod("getSearchResultList", Context.class, String.class);
        Method refresh = adapter.getDeclaredMethod("refresh", List.class);
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        class Filter {
            boolean observed;
            Object apply(Object value) throws IllegalAccessException {
                if (!(value instanceof List)) return value;
                List<?> original = (List<?>) value;
                List<Object> filtered = new ArrayList<>();
                for (Object row : original) {
                    boolean hidden = false;
                    if (row != null && item.isInstance(row)) {
                        Intent target = (Intent) intent.get(row);
                        ComponentName component = target == null ? null : target.getComponent();
                        hidden = EntryPolicy.hideResetSearch((String) pkg.get(row), (String) key.get(row),
                            component == null ? null : component.getPackageName(),
                            component == null ? null : component.getClassName(),
                            target == null ? null : target.getStringExtra(":settings:show_fragment"));
                    }
                    if (!hidden) filtered.add(row);
                }
                if (filtered.size() == original.size()) return value;
                if (!observed) { observed = true; HookLog.info("reset-search OBSERVED"); }
                return filtered;
            }
        }
        Filter filter = new Filter();
        try {
            installed.add(XposedBridge.hookMethod(query, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!param.hasThrowable()) param.setResult(filter.apply(param.getResult()));
                }
            }));
            installed.add(XposedBridge.hookMethod(refresh, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.args[0] = filter.apply(param.args[0]);
                }
            }));
            HookLog.info("reset-search INSTALLED");
            return installed;
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : installed) hook.unhook();
            throw new IllegalStateException("Search filter installation failed", error);
        }
    }
}
