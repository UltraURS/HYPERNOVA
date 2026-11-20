package io.github.hypernova.hook;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.hypernova.config.Feature;
import io.github.hypernova.compat.DeviceProfile;
import io.github.hypernova.feature.traffic.TrafficTileHook;
import io.github.hypernova.feature.update.SettingsEntryHook;
import io.github.hypernova.feature.notification.NotificationControlsHook;
import io.github.hypernova.feature.notification.NotificationClearAllHook;
import io.github.hypernova.feature.sidebar.SidebarGesture;
import io.github.hypernova.feature.sidebar.SidebarGestureHook;
import io.github.hypernova.feature.sidebar.SidebarPanelHook;
import io.github.hypernova.feature.sidebar.SidebarTriggerHook;
import io.github.hypernova.feature.sidebar.SidebarSwipeHook;
import io.github.hypernova.feature.freeform.FreeformCloseHook;
import io.github.hypernova.feature.ads.CalendarAdHook;
import io.github.hypernova.feature.ads.WeatherAdHook;
import io.github.hypernova.feature.bluetooth.BluetoothReconnectHook;
import io.github.hypernova.feature.bluetooth.BluetoothSettingsHook;
import io.github.hypernova.feature.power.AutoPowerSaveHook;
import io.github.hypernova.feature.power.PowerSaveSettingsHook;
import io.github.hypernova.feature.xiaoai.XiaoAiHook;
import io.github.hypernova.feature.screenshot.ScreenshotAppHook;
import io.github.hypernova.feature.screenshot.ScreenshotTileHook;

public final class ModuleEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (Feature.PACKAGE.equals(load.packageName)) {
            XposedHelpers.findAndHookMethod("io.github.hypernova.ModuleStatus", load.classLoader,
                "frameworkApi", XC_MethodReplacement.returnConstant(XposedBridge.getXposedVersion()));
            return;
        }
        boolean systemui = "com.android.systemui".equals(load.packageName) && load.packageName.equals(load.processName);
        boolean settings = "com.android.settings".equals(load.packageName) && load.packageName.equals(load.processName);
        boolean home = "com.miui.home".equals(load.packageName) && load.packageName.equals(load.processName);
        boolean panel = "com.miui.securitycenter".equals(load.packageName) && "com.miui.securitycenter:ui".equals(load.processName);
        boolean calendar = "com.android.calendar".equals(load.packageName) && load.packageName.equals(load.processName);
        boolean weather = "com.miui.weather2".equals(load.packageName) && load.packageName.equals(load.processName);
        boolean xiaoai = XiaoAiHook.PACKAGE.equals(load.packageName) && load.packageName.equals(load.processName);
        boolean screenshot = ScreenshotAppHook.PACKAGE.equals(load.packageName)
            && (load.packageName.equals(load.processName) || (load.packageName + ":photo_editor").equals(load.processName));
        boolean powerSettings = "com.miui.securitycenter".equals(load.packageName) && load.packageName.equals(load.processName);
        boolean powerWorker = "com.miui.securitycenter".equals(load.packageName) && "com.miui.securitycenter.remote".equals(load.processName);
        if (!(systemui || settings || home || panel || calendar || weather || xiaoai || screenshot || powerSettings || powerWorker)) return;
        HookLog.info("LOAD " + load.processName);
        if (!DeviceProfile.matchesBuild()) { HookLog.info("UNSUPPORTED build"); return; }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            private boolean started;
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (started || param.hasThrowable()) return;
                Context context = (Context) param.args[0];
                if (!load.packageName.equals(context.getPackageName())) return;
                started = true;
                Runnable setup = () -> {
                    try {
                        XSharedPreferences prefs = new XSharedPreferences(Feature.PACKAGE, Feature.PREFS);
                        if (prefs.getInt(Feature.SCHEMA, 0) != 1) {
                            HookLog.info("CONFIG_UNAVAILABLE open module and save configuration first"); return;
                        }
                        boolean traffic = prefs.getBoolean(Feature.TRAFFIC.key, false);
                        boolean ota = prefs.getBoolean(Feature.OTA_ENTRY.key, false);
                        boolean reset = prefs.getBoolean(Feature.RESET_ENTRY.key, false);
                        boolean notifications = prefs.getBoolean(Feature.NOTIFICATION_CONTROLS.key, false);
                        boolean clearAll = prefs.getBoolean(Feature.NOTIFICATION_CLEAR_ALL.key, false);
                        boolean sidebar = prefs.getBoolean(Feature.SIDEBAR_GESTURE.key, false);
                        boolean hideTrigger = prefs.getBoolean(Feature.SIDEBAR_HIDE_TRIGGER.key, false);
                        boolean calendarAds = prefs.getBoolean(Feature.CALENDAR_ADS.key, false);
                        boolean weatherAds = prefs.getBoolean(Feature.WEATHER_ADS.key, false);
                        boolean swipeClose = prefs.getBoolean(Feature.SIDEBAR_SWIPE_CLOSE.key, false);
                        boolean freeformClose = prefs.getBoolean(Feature.FREEFORM_CLOSE.key, false);
                        boolean bluetooth = prefs.getBoolean(Feature.BLUETOOTH_RECONNECT.key, false);
                        boolean bluetoothPrompts = prefs.getBoolean(Feature.BLUETOOTH_PROMPTS.key, false);
                        boolean autoPower = prefs.getBoolean(Feature.AUTO_POWER_SAVE.key, false);
                        boolean screenshotMenu = prefs.getBoolean(Feature.SCREENSHOT_MENU.key, false);
                        int holdMillis = prefs.getInt(SidebarGesture.HOLD_KEY, SidebarGesture.DEFAULT_HOLD_MILLIS);
                        if (powerSettings || powerWorker ? !autoPower : systemui ? !(traffic || notifications || clearAll || freeformClose || bluetooth || screenshotMenu) : settings ? !(ota || reset || bluetooth)
                            : home ? !sidebar : calendar ? !calendarAds : weather ? !weatherAds
                            : xiaoai ? !prefs.getBoolean(Feature.XIAOAI_CUSTOM.key, false) : screenshot ? !screenshotMenu : !(sidebar || hideTrigger || swipeClose)) {
                            HookLog.info(load.processName + " DISABLED"); return;
                        }
                        if (!(powerSettings || powerWorker ? DeviceProfile.verifyPowerCenter(context) : DeviceProfile.verify(context, load.packageName))) {
                            HookLog.info(load.packageName + " UNSUPPORTED APK hash/version/splits"); return;
                        }
                        boolean bluetoothReady = bluetooth && (systemui || settings)
                            && DeviceProfile.verify(context, systemui ? "com.android.settings" : "com.android.systemui");
                        if (bluetooth && (systemui || settings) && !bluetoothReady)
                            HookLog.info("bluetooth-reconnect UNSUPPORTED peer APK hash/version/splits");
                        if (systemui) {
                            if (traffic) {
                                try { TrafficTileHook.install(load.classLoader); }
                                catch (Throwable error) { HookLog.error("traffic install", error); }
                            }
                            if (notifications) new Handler(Looper.getMainLooper()).post(
                                () -> NotificationControlsHook.install(context, load.classLoader));
                            if (clearAll) new Handler(Looper.getMainLooper()).post(
                                () -> NotificationClearAllHook.install(load.classLoader));
                            if (freeformClose) new Handler(Looper.getMainLooper()).post(
                                () -> FreeformCloseHook.install(load.classLoader));
                            if (bluetoothReady) new Handler(Looper.getMainLooper()).post(
                                () -> BluetoothReconnectHook.install(context, load.classLoader, bluetoothPrompts));
                            if (screenshotMenu) ScreenshotTileHook.install(context, load.classLoader);
                        }
                        else if (settings) {
                            if (ota || reset) SettingsEntryHook.install(load.classLoader, ota, reset);
                            if (bluetoothReady) new Handler(Looper.getMainLooper()).post(
                                () -> BluetoothSettingsHook.install(load.classLoader, context));
                        }
                        else if (home) new Handler(Looper.getMainLooper()).post(
                            () -> SidebarGestureHook.install(load.classLoader, holdMillis));
                        else if (calendar) CalendarAdHook.install(load.classLoader);
                        else if (weather) WeatherAdHook.install(load.classLoader);
                        else if (xiaoai) new Handler(Looper.getMainLooper()).post(
                            () -> XiaoAiHook.install(context, load.classLoader));
                        else if (screenshot) ScreenshotAppHook.install(load.classLoader, load.packageName.equals(load.processName));
                        else if (powerSettings) new Handler(Looper.getMainLooper()).post(
                            () -> PowerSaveSettingsHook.install(load.classLoader));
                        else if (powerWorker) AutoPowerSaveHook.install(context);
                        else new Handler(Looper.getMainLooper()).post(() -> {
                            if (hideTrigger) SidebarTriggerHook.install(load.classLoader);
                            if (sidebar) SidebarPanelHook.install(load.classLoader);
                            if (swipeClose) SidebarSwipeHook.install(load.classLoader);
                        });
                    } catch (Throwable error) { HookLog.error(load.packageName, error); }
                };
                // A cold screenshot service can receive the first request as soon as attach returns.
                if (screenshot) setup.run();
                else new Thread(setup, "HYPERNOVA-setup").start();
            }
        });
    }
}
