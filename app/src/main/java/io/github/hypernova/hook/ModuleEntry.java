package io.github.hypernova.hook;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
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
import io.github.hypernova.feature.update.MiFitnessUpdateHook;
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
import io.github.hypernova.feature.privacy.PrivacyTelemetryHook;
import io.github.hypernova.feature.stk.StkWelcomeHook;
import io.github.hypernova.feature.xiaoai.XiaoAiHook;
import io.github.hypernova.feature.screenshot.ScreenshotAppHook;
import io.github.hypernova.feature.screenshot.ScreenshotTileHook;
import java.util.function.Supplier;

public final class ModuleEntry implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (Feature.PACKAGE.equals(load.packageName)) {
            XposedHelpers.findAndHookMethod("io.github.hypernova.ModuleStatus", load.classLoader,
                "frameworkApi", XC_MethodReplacement.returnConstant(XposedBridge.getXposedVersion()));
            return;
        }
        install(load.packageName, load.processName, load.classLoader,
            () -> new XSharedPreferences(Feature.PACKAGE, Feature.PREFS));
    }

    static void install(String packageName, String processName, ClassLoader loader, Supplier<SharedPreferences> config) {
        boolean systemui = "com.android.systemui".equals(packageName) && packageName.equals(processName);
        boolean settings = "com.android.settings".equals(packageName) && packageName.equals(processName);
        boolean home = "com.miui.home".equals(packageName) && packageName.equals(processName);
        boolean panel = "com.miui.securitycenter".equals(packageName) && "com.miui.securitycenter:ui".equals(processName);
        boolean calendar = "com.android.calendar".equals(packageName) && packageName.equals(processName);
        boolean weather = "com.miui.weather2".equals(packageName) && packageName.equals(processName);
        boolean health = "com.mi.health".equals(packageName) && packageName.equals(processName);
        boolean stk = "com.android.stk".equals(packageName);
        boolean systemHelper = "com.mobiletools.systemhelper".equals(packageName) && packageName.equals(processName);
        boolean regService = "com.miui.dmregservice".equals(packageName) && packageName.equals(processName);
        boolean analytics = "com.miui.analytics".equals(packageName) && packageName.equals(processName);
        boolean privacyHost = systemHelper || regService || analytics;
        boolean xiaoai = XiaoAiHook.PACKAGE.equals(packageName) && packageName.equals(processName);
        boolean screenshot = ScreenshotAppHook.PACKAGE.equals(packageName)
            && (packageName.equals(processName) || (packageName + ":photo_editor").equals(processName));
        boolean powerSettings = "com.miui.securitycenter".equals(packageName) && packageName.equals(processName);
        boolean powerWorker = "com.miui.securitycenter".equals(packageName) && "com.miui.securitycenter.remote".equals(processName);
        if (!(systemui || settings || home || panel || calendar || weather || health || stk || privacyHost || xiaoai || screenshot || powerSettings || powerWorker)) return;
        HookLog.info("LOAD " + processName);
        if (!DeviceProfile.matchesBuild()) { HookLog.info("UNSUPPORTED build"); return; }
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            private boolean started;
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (started || param.hasThrowable()) return;
                Context context = (Context) param.args[0];
                if (!packageName.equals(context.getPackageName())) return;
                if (stk && !"com.android.phone".equals(Application.getProcessName())) return;
                started = true;
                Runnable setup = () -> {
                    try {
                        SharedPreferences prefs = config.get();
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
                        boolean helperAppStats = systemHelper && prefs.getBoolean(Feature.SYSTEM_HELPER_APP_STATS.key, false);
                        boolean helperSignalStats = systemHelper && prefs.getBoolean(Feature.SYSTEM_HELPER_SIGNAL_STATS.key, false);
                        boolean regAppStats = regService && prefs.getBoolean(Feature.REG_SERVICE_APP_STATS.key, false);
                        boolean analyticsUsage = analytics && prefs.getBoolean(Feature.ANALYTICS_USAGE_STATS.key, false);
                        int holdMillis = prefs.getInt(SidebarGesture.HOLD_KEY, SidebarGesture.DEFAULT_HOLD_MILLIS);
                        if (powerSettings || powerWorker ? !autoPower : systemui ? !(traffic || notifications || clearAll || freeformClose || bluetooth || screenshotMenu) : settings ? !(ota || reset || bluetooth)
                            : home ? !sidebar : calendar ? !calendarAds : weather ? !weatherAds
                            : health ? !prefs.getBoolean(Feature.HEALTH_FORCE_UPDATE.key, false)
                            : stk ? !prefs.getBoolean(Feature.STK_WELCOME.key, false)
                            : privacyHost ? !(helperAppStats || helperSignalStats || regAppStats || analyticsUsage)
                            : xiaoai ? !prefs.getBoolean(Feature.XIAOAI_CUSTOM.key, false) : screenshot ? !screenshotMenu : !(sidebar || hideTrigger || swipeClose)) {
                            HookLog.info(processName + " DISABLED"); return;
                        }
                        if (!(powerSettings || powerWorker ? DeviceProfile.verifyPowerCenter(context) : DeviceProfile.verify(context, packageName))) {
                            HookLog.info(packageName + " UNSUPPORTED APK hash/version/splits"); return;
                        }
                        boolean bluetoothReady = bluetooth && (systemui || settings)
                            && DeviceProfile.verify(context, systemui ? "com.android.settings" : "com.android.systemui");
                        if (bluetooth && (systemui || settings) && !bluetoothReady)
                            HookLog.info("bluetooth-reconnect UNSUPPORTED peer APK hash/version/splits");
                        if (systemui) {
                            if (traffic) {
                                try { TrafficTileHook.install(loader); }
                                catch (Throwable error) { HookLog.error("traffic install", error); }
                            }
                            if (notifications) new Handler(Looper.getMainLooper()).post(
                                () -> NotificationControlsHook.install(context, loader));
                            if (clearAll) new Handler(Looper.getMainLooper()).post(
                                () -> NotificationClearAllHook.install(loader));
                            if (freeformClose) new Handler(Looper.getMainLooper()).post(
                                () -> FreeformCloseHook.install(loader));
                            if (bluetoothReady) new Handler(Looper.getMainLooper()).post(
                                () -> BluetoothReconnectHook.install(context, loader, bluetoothPrompts));
                            if (screenshotMenu) ScreenshotTileHook.install(context, loader);
                        }
                        else if (settings) {
                            if (ota || reset) SettingsEntryHook.install(loader, ota, reset);
                            if (bluetoothReady) new Handler(Looper.getMainLooper()).post(
                                () -> BluetoothSettingsHook.install(loader, context));
                        }
                        else if (home) new Handler(Looper.getMainLooper()).post(
                            () -> SidebarGestureHook.install(loader, holdMillis));
                        else if (calendar) CalendarAdHook.install(loader);
                        else if (weather) WeatherAdHook.install(loader);
                        else if (health) MiFitnessUpdateHook.install(loader);
                        else if (stk) StkWelcomeHook.install(loader);
                        else if (systemHelper) PrivacyTelemetryHook.installSystemHelper(loader, helperAppStats, helperSignalStats);
                        else if (regService) PrivacyTelemetryHook.installRegService(loader);
                        else if (analytics) PrivacyTelemetryHook.installAnalytics(loader);
                        else if (xiaoai) new Handler(Looper.getMainLooper()).post(
                            () -> XiaoAiHook.install(context, loader));
                        else if (screenshot) ScreenshotAppHook.install(loader, packageName.equals(processName));
                        else if (powerSettings) new Handler(Looper.getMainLooper()).post(
                            () -> PowerSaveSettingsHook.install(loader));
                        else if (powerWorker) AutoPowerSaveHook.install(context);
                        else new Handler(Looper.getMainLooper()).post(() -> {
                            if (hideTrigger) SidebarTriggerHook.install(loader);
                            if (sidebar) SidebarPanelHook.install(loader);
                            if (swipeClose) SidebarSwipeHook.install(loader);
                        });
                    } catch (Throwable error) { HookLog.error(packageName, error); }
                };
                // These hooks must be ready before the target receives its first request or runs startup tasks.
                if (screenshot || health || stk || privacyHost) setup.run();
                else new Thread(setup, "HYPERNOVA-setup").start();
            }
        });
    }
}
