// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Connect Colours, Copyright (C) 2026 UltraURS and contributors.
package io.github.hypernova.feature.bluetooth;

import io.github.hypernova.hook.HookLog;

import android.app.AlarmManager;
import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

// Runs inside the verified SystemUI APK, which holds BLUETOOTH_CONNECT/PRIVILEGED.
@android.annotation.SuppressLint("MissingPermission")
public final class BluetoothReconnectHook {
    // Owns only Application.attach's base Context, for the lifetime of the SystemUI process.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static BluetoothReconnectHook instance;
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Object cachedDevices;
    private final Handler handler;
    private final HandlerThread worker;
    private final BroadcastReceiver receiver;
    private final ContentObserver observer;
    private volatile boolean active;
    private boolean registered, observing;
    private XC_MethodHook.Unhook profileChanges;
    private int nextEntryId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final boolean showPrompts;
    private final AlarmManager alarms;
    private final AlarmManager.OnAlarmListener alarmTick = this::refreshAndDrive;
    private final Map<String, Entry> entries = new HashMap<>();
    private final Runnable tick = this::refreshAndDrive;

    public static void install(Context context, ClassLoader loader, boolean showPrompts) {
        if (instance != null) return;
        BluetoothReconnectHook created = null;
        XC_MethodHook.Unhook manual = null;
        try {
            if (XposedHelpers.findMethodExact(BluetoothDevice.class, "connect").getReturnType() != int.class)
                throw new NoSuchMethodException("Unsupported Bluetooth connection contract");
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_PRIVILEGED) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("BLUETOOTH_PRIVILEGED unavailable in host");
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            if (manager == null || manager.getAdapter() == null) { HookLog.info("bluetooth-reconnect NO_ADAPTER"); return; }
            Class<?> local = XposedHelpers.findClass("com.android.settingslib.bluetooth.LocalBluetoothManager", loader);
            Class<?> cache = XposedHelpers.findClass("com.android.settingslib.bluetooth.CachedBluetoothDeviceManager", loader);
            Class<?> cached = XposedHelpers.findClass("com.android.settingslib.bluetooth.CachedBluetoothDevice", loader);
            Class<?> profile = XposedHelpers.findClass("com.android.settingslib.bluetooth.LocalBluetoothProfile", loader);
            XposedHelpers.findMethodExact(local, "getInstance", Context.class);
            XposedHelpers.findField(local, "mCachedDeviceManager");
            XposedHelpers.findMethodExact(cache, "findDevice", BluetoothDevice.class);
            XposedHelpers.findMethodExact(cached, "isConnected");
            XposedHelpers.findMethodExact(cached, "isBusy");
            XposedHelpers.findMethodExact(cached, "onProfileStateChanged", profile, int.class);
            Object localManager = XposedHelpers.callStaticMethod(local, "getInstance", context);
            if (localManager == null) { HookLog.info("bluetooth-reconnect NO_PROFILE_MANAGER"); return; }
            manual = installManualDisconnect(context, loader);
            created = new BluetoothReconnectHook(context, manager.getAdapter(),
                    XposedHelpers.getObjectField(localManager, "mCachedDeviceManager"), showPrompts);
            created.observeProfiles(cached, profile);
            created.start();
            instance = created;
            HookLog.info("bluetooth-reconnect INSTALLED in SystemUI");
        } catch (Throwable error) {
            if (manual != null) manual.unhook();
            if (created != null) try { created.stop(); } catch (Throwable cleanup) { HookLog.error("bluetooth-reconnect cleanup", cleanup); }
            HookLog.error("bluetooth-reconnect install", error);
        }
    }

    static XC_MethodHook.Unhook installManualDisconnect(Context context, ClassLoader loader) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED
            || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Bluetooth configuration permissions unavailable in host");
        Class<?> cached = XposedHelpers.findClass("com.android.settingslib.bluetooth.CachedBluetoothDevice", loader);
        XposedHelpers.findField(cached, "mDevice");
        return XposedBridge.hookMethod(XposedHelpers.findMethodExact(cached, "disconnect"), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            BluetoothDevice device = (BluetoothDevice) XposedHelpers.getObjectField(param.thisObject, "mDevice");
                            // Save before the host disconnects, so the resulting broadcast cannot race us.
                            BluetoothConfig.pause(context, device.getAddress());
                        } catch (Throwable error) {
                            HookLog.error("Pause manual Bluetooth disconnect", error);
                        }
                    }
                });
    }

    private BluetoothReconnectHook(Context context, BluetoothAdapter adapter, Object cachedDevices, boolean showPrompts) {
        this.context = context;
        this.adapter = adapter;
        this.cachedDevices = cachedDevices;
        this.showPrompts = showPrompts;
        alarms = context.getSystemService(AlarmManager.class);
        worker = new HandlerThread("HYPERNOVA-Bluetooth");
        worker.start();
        handler = new Handler(worker.getLooper());
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!active) return;
                try {
                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                        if (state == BluetoothAdapter.STATE_ON) {
                            BluetoothConfig.reset(context);
                            entries.clear();
                            refreshAndDrive();
                        } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                            handler.removeCallbacks(tick);
                            alarms.cancel(alarmTick);
                            entries.clear();
                        }
                        return;
                    }
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    if (device == null) return;
                    String address = device.getAddress();
                    if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())
                            && intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1) == BluetoothDevice.BOND_NONE) {
                        entries.remove(address);
                        BluetoothConfig.remove(context, address);
                    }
                    // ACL events only prompt a profile query: a watch's BLE and call links can differ.
                    refreshAndDrive();
                } catch (Throwable error) {
                    HookLog.error("Bluetooth state event", error);
                }
            }
        };
        observer = new ContentObserver(handler) {
                    @Override public void onChange(boolean selfChange, Uri uri) {
                        if (BluetoothConfig.isConfigChange(uri)) refreshAndDrive();
                    }
                };
    }

    private void observeProfiles(Class<?> cached, Class<?> profile) {
        profileChanges = XposedBridge.hookMethod(XposedHelpers.findMethodExact(cached,
                "onProfileStateChanged", profile, int.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!active || param.hasThrowable()) return;
                try {
                    BluetoothDevice device = (BluetoothDevice) XposedHelpers.getObjectField(param.thisObject, "mDevice");
                    // Capture loss on the event thread so a quick reconnect cannot hide it from the worker.
                    boolean connected = (boolean) XposedHelpers.callMethod(param.thisObject, "isConnected");
                    String profileName = param.args[0].getClass().getSimpleName();
                    int state = (int) param.args[1];
                    handler.post(() -> {
                        if (!active) return;
                        Entry entry = entries.get(device.getAddress());
                        if (entry != null) {
                            HookLog.info("Bluetooth profile device#" + entry.id + " " + profileName + " state=" + state);
                            if (!connected) entry.round.disconnected(SystemClock.elapsedRealtime());
                        }
                        refreshAndDrive();
                    });
                } catch (Throwable error) {
                    HookLog.error("Bluetooth profile event", error);
                }
            }
        });
    }

    private void start() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED);
        registered = true;
        context.getContentResolver().registerContentObserver(BluetoothConfig.URI, true, observer);
        observing = true;
        active = true;
        handler.post(tick);
    }

    private void stop() {
        active = false;
        try {
            handler.removeCallbacksAndMessages(null);
            alarms.cancel(alarmTick);
            if (observing) context.getContentResolver().unregisterContentObserver(observer);
            if (registered) context.unregisterReceiver(receiver);
            if (profileChanges != null) profileChanges.unhook();
        } finally { worker.quitSafely(); }
    }

    private void refreshAndDrive() {
        if (!active) return;
        handler.removeCallbacks(tick);
        alarms.cancel(alarmTick);
        try {
            if (!adapter.isEnabled()) return;
            Bundle settings = new Bundle();
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                settings.putBundle(device.getAddress(), BluetoothConfig.read(context, device.getAddress()));
            }
            entries.keySet().removeIf(address -> !settings.containsKey(address)
                    || !settings.getBundle(address).getBoolean("enabled"));
            long now = SystemClock.elapsedRealtime();
            long next = Long.MAX_VALUE;
            for (String address : settings.keySet()) {
                Bundle config = settings.getBundle(address);
                if (!config.getBoolean("enabled")) continue;
                BluetoothDevice device = adapter.getRemoteDevice(address);
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                    entries.remove(address);
                    continue;
                }
                Object cached = XposedHelpers.callMethod(cachedDevices, "findDevice", device);
                // Never substitute an ACL-only result when the system has no functional profile state.
                if (cached == null) continue;
                boolean connected = (boolean) XposedHelpers.callMethod(cached, "isConnected");
                boolean busy = (boolean) XposedHelpers.callMethod(cached, "isBusy");
                int limit = config.getInt("attempts", 1);
                Entry entry = entries.get(address);
                if (entry == null || entry.limit != limit) {
                    entry = new Entry(++nextEntryId, limit, now, connected && !busy);
                    entries.put(address, entry);
                }
                if (config.getBoolean("paused")) entry.round.pause();
                RetryRound.State before = entry.round.state();
                if (entry.round.poll(now, connected, busy)) {
                    notifyUser(ReconnectMessages.attempt(device.getAlias(), entry.round.attempts(), limit));
                    try {
                        int result = (int) XposedHelpers.callMethod(device, "connect");
                        if (result != 0) entry.round.failed(now);
                        HookLog.info("Bluetooth reconnect device#" + entry.id + " attempt " + entry.round.attempts() + "/" + limit
                                + " result=" + result);
                    } catch (Throwable error) {
                        entry.round.failed(now);
                        HookLog.error("Bluetooth connect request", error);
                    }
                }
                if (before != entry.round.state()) {
                    HookLog.info("Bluetooth reconnect device#" + entry.id + " " + before + "->" + entry.round.state()
                            + " profileConnected=" + connected + " busy=" + busy
                            + " attempts=" + entry.round.attempts() + "/" + limit);
                }
                notifyUser(ReconnectMessages.outcome(before, entry.round.state(),
                        entry.round.attempts(), device.getAlias()));
                next = Math.min(next, entry.round.nextDeadline());
            }
            if (next != Long.MAX_VALUE) {
                alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        Math.max(SystemClock.elapsedRealtime() + 1, next),
                        "HYPERNOVA-Bluetooth", alarmTick, handler);
            }
        } catch (Throwable error) {
            // Configuration failures stop retries rather than connecting with unknown settings.
            HookLog.error("Bluetooth reconnect configuration", error);
        }
    }

    private void notifyUser(String message) {
        if (showPrompts && message != null) {
            mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        }
    }

    private static final class Entry {
        final int id;
        final int limit;
        final RetryRound round;
        Entry(int id, int limit, long now, boolean connected) {
            this.id = id;
            this.limit = limit;
            round = new RetryRound(limit, now, connected);
        }
    }
}
