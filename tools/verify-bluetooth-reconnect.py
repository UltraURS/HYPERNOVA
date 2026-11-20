# SPDX-License-Identifier: GPL-3.0-only
"""Exercise the real reconnect hook with independent ACL/profile states and a fake clock."""
import argparse
import runpy
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONFIG = runpy.run_path(str(ROOT / 'tools/verify-bluetooth-config.py'))['SOURCES']
SIDEBAR = runpy.run_path(str(ROOT / 'tools/verify-sidebar-window-lifecycle.py'))['SOURCES']
SOURCES = {name: code for name, code in CONFIG.items() if name != 'Regression.java'}
for name in ('android/content/Intent.java', 'android/os/Looper.java',
             'de/robv/android/xposed/XC_MethodHook.java', 'de/robv/android/xposed/XposedBridge.java',
             'de/robv/android/xposed/XposedHelpers.java', 'io/github/hypernova/hook/HookLog.java'):
    SOURCES[name] = SIDEBAR[name]
SOURCES['de/robv/android/xposed/XposedHelpers.java'] = SOURCES['de/robv/android/xposed/XposedHelpers.java'].replace(
    '    public static Object callMethod(', '''    public static Object callStaticMethod(Class<?> type, String name, Object... args) {
        for (var method : type.getMethods())
            if (method.getName().equals(name) && method.getParameterCount() == args.length)
                return XposedBridge.invoke(method, null, args);
        throw new IllegalArgumentException(name);
    }
    public static Object callMethod(''')
SOURCES['android/os/Bundle.java'] = SOURCES['android/os/Bundle.java'].replace(
    '    public Bundle() {}', '''    public java.util.Set<String> keySet() { return values.keySet(); }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public void putBundle(String key, Bundle value) { values.put(key, value); }
    public Bundle getBundle(String key) { return (Bundle) values.get(key); }
    public Bundle() {}''')
SOURCES['android/content/ContentResolver.java'] = SOURCES['android/content/ContentResolver.java'].replace(
    '    public int boot', '''    public android.database.ContentObserver observer;
    public void registerContentObserver(android.net.Uri uri, boolean descendants, android.database.ContentObserver value) { observer = value; }
    public void unregisterContentObserver(android.database.ContentObserver value) { observer = null; }
    public int boot''')
SOURCES.update({
    'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint { String[] value(); }',
    'android/Manifest.java': '''package android;
public class Manifest { public static class permission {
    public static final String BLUETOOTH_PRIVILEGED = "privileged", BLUETOOTH_CONNECT = "connect", WRITE_SECURE_SETTINGS = "secure";
} }''',
    'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED = 0; }',
    'android/content/Context.java': '''package android.content;
public class Context {
    public static final int RECEIVER_EXPORTED = 2;
    final ContentResolver resolver = new ContentResolver();
    final android.bluetooth.BluetoothManager manager = new android.bluetooth.BluetoothManager();
    public final android.app.AlarmManager alarms = new android.app.AlarmManager();
    public BroadcastReceiver receiver; public IntentFilter filter;
    public ContentResolver getContentResolver() { return resolver; }
    public int checkSelfPermission(String permission) { return 0; }
    public <T> T getSystemService(Class<T> type) { return type.cast(type == android.app.AlarmManager.class ? alarms : manager); }
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, String permission, android.os.Handler handler, int flags) {
        if (flags != RECEIVER_EXPORTED) throw new AssertionError("Bluetooth stack broadcasts must be received");
        receiver = r; filter = f; return null;
    }
    public void unregisterReceiver(BroadcastReceiver value) { receiver = null; }
    public void send(String action, android.bluetooth.BluetoothDevice device) {
        if (!filter.actions.contains(action)) throw new AssertionError("Missing broadcast " + action);
        receiver.onReceive(this, new Intent(action).putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, device));
        android.os.Handler.drain();
    }
    public void configChanged() { resolver.observer.onChange(false, android.provider.Settings.Secure.CONTENT_URI); android.os.Handler.drain(); }
}''',
    'android/content/BroadcastReceiver.java': 'package android.content; public abstract class BroadcastReceiver { public abstract void onReceive(Context c, Intent i); }',
    'android/content/IntentFilter.java': '''package android.content;
public class IntentFilter {
    public final java.util.Set<String> actions = new java.util.HashSet<>();
    public IntentFilter(String action) { addAction(action); } public void addAction(String action) { actions.add(action); }
}''',
    'android/database/ContentObserver.java': '''package android.database;
public class ContentObserver { public ContentObserver(android.os.Handler h) {} public void onChange(boolean self, android.net.Uri uri) {} }''',
    'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long now; public static long elapsedRealtime() { return now; } }',
    'android/os/HandlerThread.java': '''package android.os;
public class HandlerThread { public HandlerThread(String name) {} public void start() {} public Looper getLooper() { return new Looper(); } public boolean quitSafely() { return true; } }''',
    'android/os/Handler.java': '''package android.os;
public class Handler {
    static final java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
    public Handler(Looper looper) {} public boolean post(Runnable r) { queue.add(r); return true; }
    public void removeCallbacks(Runnable r) { queue.removeIf(item -> item == r); }
    public void removeCallbacksAndMessages(Object token) { queue.clear(); }
    public static void drain() { int count = 0; while (!queue.isEmpty()) {
        if (++count > 1000) throw new AssertionError("Unbounded handler loop"); queue.remove().run();
    } }
}''',
    'android/app/AlarmManager.java': '''package android.app;
public class AlarmManager {
    public static final int ELAPSED_REALTIME_WAKEUP = 2;
    public interface OnAlarmListener { void onAlarm(); }
    public OnAlarmListener pending; public long due;
    public void cancel(OnAlarmListener listener) { if (pending == listener) pending = null; }
    public void setExact(int type, long time, String tag, OnAlarmListener listener, android.os.Handler h) {
        if (time <= android.os.SystemClock.now) throw new AssertionError("Alarm loop"); pending = listener; due = time;
    }
    public void advance(long now) {
        android.os.SystemClock.now = now;
        if (pending != null && due <= now) { OnAlarmListener call = pending; pending = null; call.onAlarm(); }
        android.os.Handler.drain();
    }
}''',
    'android/widget/Toast.java': '''package android.widget;
public class Toast {
    public static final int LENGTH_SHORT = 0;
    public static final java.util.List<String> messages = new java.util.ArrayList<>();
    final String message; Toast(String message) { this.message = message; }
    public static Toast makeText(android.content.Context c, String text, int duration) { return new Toast(text); }
    public void show() { messages.add(message); }
}''',
    'android/bluetooth/BluetoothManager.java': '''package android.bluetooth;
public class BluetoothManager { public BluetoothAdapter getAdapter() { return BluetoothAdapter.INSTANCE; } }''',
    'android/bluetooth/BluetoothAdapter.java': '''package android.bluetooth;
public class BluetoothAdapter {
    public static final BluetoothAdapter INSTANCE = new BluetoothAdapter();
    public static final String ACTION_STATE_CHANGED = "adapter", ACTION_CONNECTION_STATE_CHANGED = "profiles", EXTRA_STATE = "state";
    public static final int ERROR = -1, STATE_ON = 12, STATE_TURNING_OFF = 13, STATE_OFF = 10;
    public final java.util.Map<String, BluetoothDevice> devices = new java.util.LinkedHashMap<>();
    public boolean isEnabled() { return true; }
    public java.util.Set<BluetoothDevice> getBondedDevices() { return new java.util.LinkedHashSet<>(devices.values()); }
    public BluetoothDevice getRemoteDevice(String address) { return devices.get(address); }
    public static boolean checkBluetoothAddress(String address) { return address.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"); }
}''',
    'android/bluetooth/BluetoothDevice.java': '''package android.bluetooth;
public class BluetoothDevice {
    public static final String ACTION_ACL_CONNECTED = "acl_connected", ACTION_ACL_DISCONNECTED = "acl_disconnected", ACTION_BOND_STATE_CHANGED = "bond";
    public static final String EXTRA_DEVICE = "device", EXTRA_BOND_STATE = "bond_state";
    public static final int BOND_BONDED = 12, BOND_NONE = 10;
    public final String address; public boolean acl; public int requests, aclReads;
    public BluetoothDevice(String address) { this.address = address; }
    public String getAddress() { return address; } public String getAlias() { return "Watch"; }
    public int getBondState() { return BOND_BONDED; }
    public boolean isConnected() { aclReads++; return acl; }
    public int connect() { requests++; return 0; }
}''',
    'com/android/settingslib/bluetooth/LocalBluetoothProfile.java': 'package com.android.settingslib.bluetooth; public class LocalBluetoothProfile {}',
    'com/android/settingslib/bluetooth/CachedBluetoothDevice.java': '''package com.android.settingslib.bluetooth;
public class CachedBluetoothDevice {
    public android.bluetooth.BluetoothDevice mDevice; public boolean connected, busy;
    public CachedBluetoothDevice(android.bluetooth.BluetoothDevice device) { mDevice = device; }
    public boolean isConnected() { return connected; } public boolean isBusy() { return busy; }
    public void disconnect() { connected = false; }
    public void onProfileStateChanged(LocalBluetoothProfile profile, int state) { connected = state == 2; busy = state == 1 || state == 3; }
}''',
    'com/android/settingslib/bluetooth/CachedBluetoothDeviceManager.java': '''package com.android.settingslib.bluetooth;
public class CachedBluetoothDeviceManager {
    public final java.util.Map<String, CachedBluetoothDevice> devices = new java.util.HashMap<>();
    public CachedBluetoothDevice findDevice(android.bluetooth.BluetoothDevice device) { return devices.get(device.getAddress()); }
}''',
    'com/android/settingslib/bluetooth/LocalBluetoothManager.java': '''package com.android.settingslib.bluetooth;
public class LocalBluetoothManager {
    public static final LocalBluetoothManager INSTANCE = new LocalBluetoothManager();
    public final CachedBluetoothDeviceManager mCachedDeviceManager = new CachedBluetoothDeviceManager();
    public static LocalBluetoothManager getInstance(android.content.Context context) { return INSTANCE; }
}''',
    'Regression.java': '''import android.bluetooth.*; import android.content.*; import android.os.*; import android.widget.Toast;
import com.android.settingslib.bluetooth.*;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.feature.bluetooth.*;
public class Regression {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static long successes() { return Toast.messages.stream().filter(s -> s.contains("重连成功")).count(); }
    static void save(Context c, BluetoothDevice d, boolean enabled, int attempts) {
        Bundle b = new Bundle(); b.putBoolean("enabled", enabled); b.putInt("attempts", attempts);
        BluetoothConfig.save(c, d.getAddress(), b);
    }
    static CachedBluetoothDevice add(String address) {
        BluetoothDevice d = new BluetoothDevice(address); BluetoothAdapter.INSTANCE.devices.put(address, d);
        CachedBluetoothDevice cached = new CachedBluetoothDevice(d);
        LocalBluetoothManager.INSTANCE.mCachedDeviceManager.devices.put(address, cached); return cached;
    }
    static void profile(CachedBluetoothDevice cached, int state) {
        XposedHelpers.callMethod(cached, "onProfileStateChanged", new LocalBluetoothProfile(), state);
    }
    public static void main(String[] args) {
        Context context = new Context(); CachedBluetoothDevice cached = add("00:11:22:33:44:55");
        BluetoothDevice watch = cached.mDevice; save(context, watch, true, 2);
        BluetoothReconnectHook.install(context, Regression.class.getClassLoader(), true); Handler.drain();
        check(watch.requests == 1, "First request must be sent");
        SystemClock.now = 100; watch.acl = true; context.send(BluetoothDevice.ACTION_ACL_CONNECTED, watch);
        check(successes() == 0 && context.alarms.pending != null, "ACL-only connection must not stop retrying or report success");
        SystemClock.now = 1_000; profile(cached, 2); Handler.drain();
        check(successes() == 0, "Profile connection must first be confirmed");
        SystemClock.now = 1_500; profile(cached, 0); Handler.drain();
        check(watch.acl && context.alarms.pending != null, "Profile loss without ACL loss must keep retrying");
        context.alarms.advance(15_000); context.alarms.advance(24_999);
        check(watch.requests == 1, "Failure must respect the retry interval");
        context.alarms.advance(25_000); check(watch.requests == 2, "Connection flash must not reset the budget to attempt one");
        SystemClock.now = 27_000; profile(cached, 2); Handler.drain();
        SystemClock.now = 31_000; profile(cached, 0); profile(cached, 2); Handler.drain();
        context.alarms.advance(32_000); check(successes() == 0, "Fast loss and reconnect before worker delivery must reset confirmation");
        context.alarms.advance(36_000);
        check(successes() == 1 && context.alarms.pending == null, "Stable profile must report one success and stop polling");
        SystemClock.now = 40_000; profile(cached, 0); Handler.drain();
        context.alarms.advance(49_999); check(watch.requests == 2, "Stable connection loss needs a cooldown");
        context.alarms.advance(50_000); check(watch.requests == 3, "Profile-only loss must start a new round after cooldown");
        SystemClock.now = 50_100; profile(cached, 2); Handler.drain();
        SystemClock.now = 50_200; XposedHelpers.callMethod(cached, "disconnect"); context.configChanged();
        check(BluetoothConfig.read(context, watch.getAddress()).getBoolean("paused"), "Manual disconnect must persist pause");
        check(context.alarms.pending == null && successes() == 1, "Pause during confirmation must cancel timers and success");
        save(context, watch, false, 2); context.configChanged();
        CachedBluetoothDevice second = add("00:11:22:33:44:66"); second.busy = true;
        save(context, second.mDevice, true, 1); context.configChanged();
        check(second.mDevice.requests == 0, "Do not compete with the system's pending connection");
        SystemClock.now = 51_000; profile(second, 2); Handler.drain(); context.alarms.advance(56_000);
        check(second.mDevice.requests == 0 && successes() == 1, "System-completed connection must not trigger another request or module success toast");
        save(context, second.mDevice, false, 1); context.configChanged();
        BluetoothDevice missing = new BluetoothDevice("00:11:22:33:44:77"); missing.acl = true;
        BluetoothAdapter.INSTANCE.devices.put(missing.getAddress(), missing); save(context, missing, true, 1); context.configChanged();
        check(missing.requests == 0 && context.alarms.pending == null, "Missing profile cache must not fall back to ACL or connect blindly");
        CachedBluetoothDevice available = new CachedBluetoothDevice(missing);
        LocalBluetoothManager.INSTANCE.mCachedDeviceManager.devices.put(missing.getAddress(), available);
        SystemClock.now = 57_000; profile(available, 2); Handler.drain(); context.alarms.advance(62_000);
        check(missing.requests == 0 && context.alarms.pending == null, "Profile initialization events must resume observation without an existing entry");
        check(watch.aclReads + second.mDevice.aclReads + missing.aclReads == 0, "Never query BluetoothDevice ACL state as a success criterion");
        System.out.println("Bluetooth reconnect OK: ACL/profile separation, five-second confirmation, flash budget, profile-only loss, fast events, cooldown, native busy state, pause, cache readiness and idle timers");
    }
}'''
})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='bluetooth-reconnect-test-', dir=ROOT / '.local') as directory:
        temp = Path(directory)
        for name, code in SOURCES.items():
            target = temp / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(code, encoding='utf-8')
        for name in ('BluetoothConfig', 'BluetoothReconnectHook', 'RetryRound', 'ReconnectMessages'):
            target = temp / f'io/github/hypernova/feature/bluetooth/{name}.java'
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / f'app/src/main/java/io/github/hypernova/feature/bluetooth/{name}.java').read_bytes())
        subprocess.run([str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(temp / 'out')]
                       + [str(p) for p in temp.rglob('*.java')], check=True)
        subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(temp / 'out'), 'Regression'], check=True)


if __name__ == '__main__':
    main()
