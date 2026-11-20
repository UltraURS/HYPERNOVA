// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Connect Colours, Copyright (C) 2026 UltraURS and contributors.
package io.github.hypernova.feature.bluetooth;

import io.github.hypernova.hook.HookLog;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;
import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

@android.annotation.SuppressLint("MissingPermission")
public final class BluetoothSettingsHook {
    private static final String CATEGORY = "hypernova_bluetooth";
    private static final String AUTO = "hypernova_auto";
    private static final String ATTEMPTS = "hypernova_attempts";

    public static void install(ClassLoader loader, Context context) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            List<Method> resumes = new ArrayList<>();
            for (String name : new String[]{"DeviceProfilesSettings", "BluetoothDeviceDetailsFragment"}) {
                Class<?> fragment = XposedHelpers.findClass("com.android.settings.bluetooth." + name, loader);
                resumes.add(XposedHelpers.findMethodExact(fragment, "onResume"));
                XposedHelpers.findField(fragment, "mCachedDevice");
            }
            for (String type : new String[]{"androidx.preference.PreferenceCategory", "androidx.preference.SwitchPreference", "miuix.preference.DropDownPreference"})
                XposedHelpers.findConstructorExact(XposedHelpers.findClass(type, loader), Context.class);
            XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceChangeListener", loader);
            hooks.add(BluetoothReconnectHook.installManualDisconnect(context, loader));
            for (Method resume : resumes) hooks.add(XposedBridge.hookMethod(resume, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try {
                        addOptions(param.thisObject, loader);
                    } catch (Throwable error) {
                        HookLog.error("Bluetooth device options", error);
                    }
                }
            }));
            HookLog.info("bluetooth-settings INSTALLED");
        } catch (Throwable error) {
            for (var hook : hooks) hook.unhook();
            HookLog.error("bluetooth-settings install", error);
        }
    }

    private static void addOptions(Object fragment, ClassLoader loader) {
        Activity activity = (Activity) XposedHelpers.callMethod(fragment, "getActivity");
        if (activity == null || activity.isFinishing()) return;
        Object cached = XposedHelpers.getObjectField(fragment, "mCachedDevice");
        if (cached == null) return;
        BluetoothDevice device = (BluetoothDevice) XposedHelpers.getObjectField(cached, "mDevice");
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) return;
        String address = device.getAddress();
        Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen == null) return;
        Context context = (Context) XposedHelpers.callMethod(screen, "getContext");
        final Bundle config = BluetoothConfig.read(context, address);
        Object category = XposedHelpers.callMethod(screen, "findPreference", CATEGORY);
        if (category == null) {
            category = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "androidx.preference.PreferenceCategory", loader), context);
            XposedHelpers.callMethod(category, "setKey", CATEGORY);
            XposedHelpers.callMethod(category, "setTitle", "HYPERNOVA");
            XposedHelpers.callMethod(category, "setOrder", 10000);
            Object toggle = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "androidx.preference.SwitchPreference", loader), context);
            XposedHelpers.callMethod(toggle, "setKey", AUTO);
            XposedHelpers.callMethod(toggle, "setTitle", "自动连接");
            XposedHelpers.callMethod(toggle, "setPersistent", false);
            Object count = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "miuix.preference.DropDownPreference", loader), context);
            XposedHelpers.callMethod(count, "setKey", ATTEMPTS);
            XposedHelpers.callMethod(count, "setTitle", "尝试连接次数");
            XposedHelpers.callMethod(count, "setPersistent", false);
            CharSequence[] choices = {"1", "2", "3", "4", "5"};
            XposedHelpers.callMethod(count, "setEntries", (Object) choices);
            XposedHelpers.callMethod(count, "setEntryValues", (Object) choices);
            XposedHelpers.callMethod(screen, "addPreference", category);
            XposedHelpers.callMethod(category, "addPreference", toggle);
            XposedHelpers.callMethod(category, "addPreference", count);
        }
        Object toggle = XposedHelpers.callMethod(screen, "findPreference", AUTO);
        Object count = XposedHelpers.callMethod(screen, "findPreference", ATTEMPTS);
        update(toggle, count, config);
        setChange(loader, toggle, (preference, value) -> {
            Bundle change = new Bundle(config);
            change.putBoolean("enabled", (boolean) value);
            if (!save(context, address, change)) return false;
            config.putAll(change);
            update(toggle, count, config);
            return true;
        });
        setChange(loader, count, (preference, value) -> {
            Bundle change = new Bundle(config);
            change.putInt("attempts", Integer.parseInt((String) value));
            if (!save(context, address, change)) return false;
            config.putAll(change);
            update(toggle, count, config);
            return true;
        });
    }

    private static void update(Object toggle, Object count, Bundle config) {
        boolean enabled = config.getBoolean("enabled", false);
        XposedHelpers.callMethod(toggle, "setChecked", enabled);
        XposedHelpers.callMethod(toggle, "setSummary", config.getBoolean("paused")
                ? "手动断开后已暂停，重新开启蓝牙或重启手机后恢复"
                : "设备断开后按设定次数重连，手动断开时暂停");
        XposedHelpers.callMethod(count, "setValue", String.valueOf(config.getInt("attempts", 1)));
        XposedHelpers.callMethod(count, "setSummary", "共 " + config.getInt("attempts", 1)
                + " 次（含首次） · 每次最多等待 15 秒，失败后间隔 10 秒");
        XposedHelpers.callMethod(count, "setVisible", enabled);
    }

    private static boolean save(Context context, String address, Bundle config) {
        try {
            BluetoothConfig.save(context, address, config);
            return true;
        } catch (Throwable error) {
            HookLog.error("Save Bluetooth device options", error);
            Toast.makeText(context, "保存失败，请查看 LSPosed 中的 HYPERNOVA 日志", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static void setChange(ClassLoader loader, Object preference,
                                  BiFunction<Object, Object, Boolean> callback) {
        Class<?> listener = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceChangeListener", loader);
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[] {listener}, (self, method, args) -> {
            switch (method.getName()) {
                case "onPreferenceChange": return callback.apply(args[0], args[1]);
                case "equals": return self == args[0];
                case "hashCode": return System.identityHashCode(self);
                case "toString": return "HYPERNOVAPreferenceChange";
                default: throw new UnsupportedOperationException(method.getName());
            }
        });
        XposedHelpers.callMethod(preference, "setOnPreferenceChangeListener", proxy);
    }
}
