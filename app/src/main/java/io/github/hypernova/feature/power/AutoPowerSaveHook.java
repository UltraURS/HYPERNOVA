// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.power;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import io.github.hypernova.hook.HookLog;

/** Runs only in the verified power center's existing remote process. */
public final class AutoPowerSaveHook {
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static AutoPowerSaveHook instance;
    private static final Uri POWER_URI = Uri.parse("content://com.miui.powercenter.powersaver");
    private final Context context;
    private final HandlerThread worker = new HandlerThread("HYPERNOVA-Power");
    private final Handler handler;
    private final PowerSaveRule rule = new PowerSaveRule();
    private final Runnable configChanged = () -> { rule.reset(); check(); };
    private final IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    private final BroadcastReceiver receiver;
    private final ContentObserver observer;
    private volatile boolean active;
    private boolean registered, observing;

    public static void install(Context context) {
        if (instance != null) return;
        AutoPowerSaveHook created = null;
        try {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Power saver configuration permission unavailable in host");
            created = new AutoPowerSaveHook(context);
            created.start();
            instance = created;
            HookLog.info("power-auto INSTALLED in securitycenter.remote");
        } catch (Throwable error) {
            if (created != null) try { created.stop(); } catch (Throwable cleanup) { HookLog.error("power-auto cleanup", cleanup); }
            HookLog.error("power-auto install", error);
        }
    }

    private AutoPowerSaveHook(Context context) {
        this.context = context;
        worker.start();
        handler = new Handler(worker.getLooper());
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) check(intent);
            }
        };
        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (PowerSaveConfig.isConfigChange(uri)) {
                    handler.removeCallbacks(configChanged);
                    handler.post(configChanged);
                }
            }
        };
    }

    private void start() {
        context.getContentResolver().registerContentObserver(PowerSaveConfig.URI, false, observer);
        observing = true;
        context.registerReceiver(receiver, batteryFilter, null, handler, Context.RECEIVER_NOT_EXPORTED);
        registered = true;
        active = true;
        handler.post(this::check);
    }

    private void stop() {
        active = false;
        try {
            handler.removeCallbacksAndMessages(null);
            if (observing) context.getContentResolver().unregisterContentObserver(observer);
            if (registered) context.unregisterReceiver(receiver);
        } finally { worker.quitSafely(); }
    }

    private void check() {
        check(null);
    }

    private void check(Intent event) {
        if (!active) return;
        try {
            PowerSaveConfig config = PowerSaveConfig.read(context);
            // Preserve a queued charging/recovery transition even if the latest sample is already low again.
            if (event != null) rule.observeRecovery(config.threshold(),
                    event.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), event.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                    event.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
            // Query the latest sticky state, rather than act on an older queued battery event.
            Intent battery = context.registerReceiver(null, batteryFilter);
            if (battery == null) return;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            boolean saving = Settings.System.getInt(context.getContentResolver(), "POWER_SAVE_MODE_OPEN", 0) != 0
                    || Settings.System.getInt(context.getContentResolver(), "power_supersave_mode_open", 0) != 0;
            if (!rule.shouldEnable(config.enabled(), config.threshold(), level, scale, plugged, saving)) return;
            Bundle args = new Bundle();
            args.putBoolean("POWER_SAVE_MODE_OPEN", true);
            args.putBoolean("POWER_DEVICE_NOTICE", false);
            context.getContentResolver().call(POWER_URI, "changePowerMode", null, args);
            HookLog.info("power-auto REQUESTED level=" + level + "/" + scale + " threshold=" + config.threshold());
        } catch (Throwable error) { HookLog.error("power-auto evaluate", error); }
    }
}
