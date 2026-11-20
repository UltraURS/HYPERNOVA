// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.power;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.function.BiFunction;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;

public final class PowerSaveSettingsHook {
    private static final String CATEGORY = "hypernova_power_category";
    private static final String AUTO = "hypernova_power_enabled";
    private static final String THRESHOLD = "hypernova_power_threshold";
    private static final String TITLE_VIEW = "hypernova_power_title_view";

    public static void install(ClassLoader loader) {
        try {
            Class<?> fragment = XposedHelpers.findClass("com.miui.powercenter.savemode.PowerSaveFragment", loader);
            for (String type : new String[]{"PreferenceCategory", "CheckBoxPreference"}) {
                XposedHelpers.findConstructorExact(XposedHelpers.findClass("androidx.preference." + type, loader), Context.class);
            }
            Class<?> slider = XposedHelpers.findClass("androidx.preference.SeekBarPreference", loader);
            XposedHelpers.findConstructorExact(slider, Context.class, AttributeSet.class);
            XposedHelpers.findField(slider, "b"); // min
            XposedHelpers.findField(slider, "i"); // showSeekBarValue
            for (String method : new String[]{"i", "j", "k"}) XposedHelpers.findMethodExact(slider, method, int.class);
            Class<?> holder = XposedHelpers.findClass("androidx.preference.i", loader);
            XposedHelpers.findMethodExact(holder, "b", int.class);
            Method bind = XposedHelpers.findMethodExact(slider, "onBindViewHolder", holder);
            Method valueLabel = XposedHelpers.findMethodExact(slider, "n", int.class);
            XposedHelpers.findClass("androidx.preference.Preference$c", loader);
            Class<?> preferences = XposedHelpers.findClass("androidx.preference.PreferenceFragmentCompat", loader);
            Class<?> list = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", loader);
            Class<?> animator = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$ItemAnimator", loader);
            XposedHelpers.findMethodExact(preferences, "getListView");
            XposedHelpers.findMethodExact(list, "setItemAnimator", animator);
            Method create = XposedHelpers.findMethodExact(fragment, "onCreatePreferences", Bundle.class, String.class);
            Method resume = XposedHelpers.findMethodExact(fragment, "onResume");
            XposedBridge.hookMethod(bind, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (XposedHelpers.getAdditionalInstanceField(param.thisObject, TITLE_VIEW) == null) return;
                    try {
                        Object title = XposedHelpers.callMethod(param.args[0], "b", android.R.id.title);
                        XposedHelpers.setAdditionalInstanceField(param.thisObject, TITLE_VIEW,
                                new WeakReference<>(title instanceof TextView text ? text : null));
                    } catch (Throwable error) { HookLog.error("Bind power saver threshold title", error); }
                }
            });
            XposedBridge.hookMethod(valueLabel, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try {
                        Object bound = XposedHelpers.getAdditionalInstanceField(param.thisObject, TITLE_VIEW);
                        if (bound instanceof WeakReference<?> reference && reference.get() instanceof TextView title) {
                            title.setText(String.format(Locale.ROOT, "触发电量：%d%%", param.args[0]));
                        }
                    } catch (Throwable error) { HookLog.error("Update power saver threshold title", error); }
                }
            });
            XposedBridge.hookMethod(create, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try { addOptions(param.thisObject, loader); }
                    catch (Throwable error) { HookLog.error("Create power saver options", error); }
                }
            });
            XposedBridge.hookMethod(resume, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object recycler = XposedHelpers.callMethod(param.thisObject, "getListView");
                        if (recycler != null) XposedHelpers.callMethod(recycler, "setItemAnimator", (Object) null);
                    } catch (Throwable error) { HookLog.error("Power saver list animations", error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try { addOptions(param.thisObject, loader); }
                    catch (Throwable error) { HookLog.error("Power saver options", error); }
                }
            });
            HookLog.info("power-auto-settings INSTALLED");
        } catch (Throwable error) { HookLog.error("power-auto-settings install", error); }
    }

    private static void addOptions(Object fragment, ClassLoader loader) {
        Activity activity = (Activity) XposedHelpers.callMethod(fragment, "getActivity");
        if (activity == null || activity.isFinishing()) return;
        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen == null) return;
        Context context = (Context) XposedHelpers.callMethod(screen, "getContext");
        PowerSaveConfig config = PowerSaveConfig.read(context);
        Object category = XposedHelpers.callMethod(screen, "findPreference", CATEGORY);
        if (category == null) {
            category = XposedHelpers.newInstance(XposedHelpers.findClass("androidx.preference.PreferenceCategory", loader), context);
            XposedHelpers.callMethod(category, "setKey", CATEGORY);
            XposedHelpers.callMethod(category, "setTitle", "HYPERNOVA");
            XposedHelpers.callMethod(category, "setOrder", 10000);
            Object toggle = XposedHelpers.newInstance(XposedHelpers.findClass("androidx.preference.CheckBoxPreference", loader), context);
            XposedHelpers.callMethod(toggle, "setKey", AUTO);
            XposedHelpers.callMethod(toggle, "setTitle", "自动打开省电模式");
            XposedHelpers.callMethod(toggle, "setPersistent", false);
            Object slider = XposedHelpers.newInstance(XposedHelpers.findClass("androidx.preference.SeekBarPreference", loader), context, null);
            XposedHelpers.setAdditionalInstanceField(slider, TITLE_VIEW, new WeakReference<TextView>(null));
            XposedHelpers.callMethod(slider, "setKey", THRESHOLD);
            XposedHelpers.callMethod(slider, "setPersistent", false);
            XposedHelpers.setIntField(slider, "b", 20);
            XposedHelpers.setBooleanField(slider, "i", true);
            XposedHelpers.callMethod(slider, "i", 80); // setMax
            XposedHelpers.callMethod(slider, "j", 1); // setSeekBarIncrement
            XposedHelpers.callMethod(screen, "addPreference", category);
            XposedHelpers.callMethod(category, "addPreference", toggle);
            XposedHelpers.callMethod(category, "addPreference", slider);
        }
        Object toggle = XposedHelpers.callMethod(screen, "findPreference", AUTO);
        Object slider = XposedHelpers.callMethod(screen, "findPreference", THRESHOLD);
        update(toggle, slider, config);
        setChange(loader, toggle, (preference, value) -> save(context, toggle, slider, (Boolean) value, null));
        setChange(loader, slider, (preference, value) -> save(context, toggle, slider, null, (Integer) value));
    }

    private static void update(Object toggle, Object slider, PowerSaveConfig config) {
        XposedHelpers.callMethod(toggle, "setChecked", config.enabled());
        XposedHelpers.callMethod(toggle, "setSummary", "仅未接通电源时触发；本轮手动关闭后不再强制开启");
        XposedHelpers.callMethod(slider, "k", config.threshold()); // setValue
        XposedHelpers.callMethod(slider, "setTitle", "触发电量：" + config.threshold() + "%");
        XposedHelpers.callMethod(slider, "setSummary", "未接通电源时，当电池电量小于 " + config.threshold() + "% 时自动打开省电模式");
        XposedHelpers.callMethod(slider, "setVisible", config.enabled());
    }

    private static boolean save(Context context, Object toggle, Object slider, Boolean enabled, Integer threshold) {
        try {
            PowerSaveConfig current = PowerSaveConfig.read(context);
            PowerSaveConfig next = new PowerSaveConfig(enabled == null ? current.enabled() : enabled,
                    threshold == null ? current.threshold() : threshold);
            next.save(context);
            update(toggle, slider, next);
            return true;
        } catch (Throwable error) {
            HookLog.error("Save power saver options", error);
            Toast.makeText(context, "保存失败，请查看 LSPosed 中的 HYPERNOVA 日志", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static void setChange(ClassLoader loader, Object preference, BiFunction<Object, Object, Boolean> callback) {
        Class<?> listener = XposedHelpers.findClass("androidx.preference.Preference$c", loader);
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (self, method, args) -> {
            switch (method.getName()) {
                case "onPreferenceChange": return callback.apply(args[0], args[1]);
                case "equals": return self == args[0];
                case "hashCode": return System.identityHashCode(self);
                case "toString": return "HYPERNOVAPowerPreferenceChange";
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        XposedHelpers.callMethod(preference, "setOnPreferenceChangeListener", proxy);
    }
}
