# SPDX-License-Identifier: GPL-3.0-only
"""Compile the real BluetoothConfig against a small per-user SettingsProvider model."""
import argparse
import subprocess
import tempfile
from pathlib import Path


SOURCES = {
    'android/net/Uri.java': '''package android.net;
public record Uri(java.net.URI value) {
    public static Uri parse(String value) { return new Uri(java.net.URI.create(value)); }
    public String getAuthority() { return value.getAuthority(); } public String getPath() { return value.getPath(); }
}''',
    'android/content/ContentResolver.java': '''package android.content;
public class ContentResolver {
    public final java.util.Map<String, String> secure = new java.util.HashMap<>();
    public int boot = 1; public boolean writable = true;
}''',
    'android/content/Context.java': '''package android.content;
public class Context {
    final ContentResolver resolver;
    public Context(ContentResolver resolver) { this.resolver = resolver; }
    public ContentResolver getContentResolver() { return resolver; }
}''',
    'android/os/Bundle.java': '''package android.os;
public class Bundle {
    final java.util.Map<String, Object> values = new java.util.HashMap<>();
    public Bundle() {} public Bundle(Bundle other) { values.putAll(other.values); }
    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public boolean getBoolean(String key) { return getBoolean(key, false); }
    public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    public void putInt(String key, int value) { values.put(key, value); }
    public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
}''',
    'android/bluetooth/BluetoothAdapter.java': '''package android.bluetooth;
public class BluetoothAdapter {
    public static boolean checkBluetoothAddress(String address) { return address.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}"); }
}''',
    'android/provider/Settings.java': '''package android.provider;
import android.content.ContentResolver; import android.net.Uri;
public class Settings {
    public static class Secure {
        public static final Uri CONTENT_URI = Uri.parse("content://settings/secure");
        public static Uri getUriFor(String key) { return Uri.parse("content://settings/secure/" + key); }
        public static String getString(ContentResolver resolver, String key) { return resolver.secure.get(key); }
        public static boolean putString(ContentResolver resolver, String key, String value) {
            if (!resolver.writable) return false;
            if (value == null) resolver.secure.remove(key); else resolver.secure.put(key, value);
            return true;
        }
    }
    public static class Global {
        public static final String BOOT_COUNT = "boot_count";
        public static int getInt(ContentResolver resolver, String key, int fallback) { return resolver.boot; }
    }
}''',
    'Regression.java': '''import android.content.*; import android.os.Bundle; import android.net.Uri;
import android.provider.Settings;
import io.github.hypernova.feature.bluetooth.BluetoothConfig;
public class Regression {
    static final String A = "02:00:00:00:00:01", B = "00:11:22:33:44:55";
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Invalid/unwritable configuration must be rejected");
    }
    public static void main(String[] args) {
        var store = new ContentResolver(); var context = new Context(store);
        store.secure.put("connect_colours_bluetooth_020000000001", "5");
        var defaults = BluetoothConfig.read(context, A);
        check(!defaults.getBoolean("enabled") && defaults.getInt("attempts", 0) == 1, "new devices must default off/one attempt");
        check(!defaults.getBoolean("paused"), "new devices must not be paused");
        defaults.putBoolean("enabled", true); defaults.putInt("attempts", 5);
        BluetoothConfig.save(context, A, defaults);
        check(BluetoothConfig.read(new Context(store), A).getInt("attempts", 0) == 5, "new context must retain device settings");
        check(!BluetoothConfig.read(context, B).getBoolean("enabled"), "devices must be independent");
        check(!BluetoothConfig.read(new Context(new ContentResolver()), A).getBoolean("enabled"), "users must be independent");
        var stale = BluetoothConfig.read(context, A); BluetoothConfig.pause(context, A);
        stale.putInt("attempts", 3); BluetoothConfig.save(context, A, stale);
        check(BluetoothConfig.read(new Context(store), A).getBoolean("paused"), "stale UI save must not overwrite manual pause");
        check(BluetoothConfig.read(context, A).getInt("attempts", 0) == 3, "count must update independently of pause");
        BluetoothConfig.reset(context);
        check(!BluetoothConfig.read(context, A).getBoolean("paused"), "Bluetooth toggle must reset pause session");
        BluetoothConfig.pause(context, A); store.boot++;
        check(!BluetoothConfig.read(context, A).getBoolean("paused"), "reboot must clear pause without losing configuration");
        BluetoothConfig.save(context, B, defaults); BluetoothConfig.pause(context, A); BluetoothConfig.remove(context, A);
        check(!BluetoothConfig.read(context, A).getBoolean("enabled") && !BluetoothConfig.read(context, A).getBoolean("paused"), "unpair must remove configuration and pause");
        check(BluetoothConfig.read(context, B).getBoolean("enabled"), "unpair must preserve other devices");
        check("5".equals(store.secure.get("connect_colours_bluetooth_020000000001")), "other module namespace must remain untouched");
        check(BluetoothConfig.isConfigChange(null) && BluetoothConfig.isConfigChange(Settings.Secure.CONTENT_URI), "whole-table notifications must refresh");
        check(BluetoothConfig.isConfigChange(Settings.Secure.getUriFor("hypernova_bluetooth_session")), "session notifications must refresh");
        check(!BluetoothConfig.isConfigChange(Settings.Secure.getUriFor("accessibility_enabled")), "unrelated notifications must be ignored");
        check(!BluetoothConfig.isConfigChange(Uri.parse("content://other/secure/hypernova_bluetooth_session")), "other providers must be ignored");
        rejects(() -> BluetoothConfig.read(context, "invalid"));
        for (int count : new int[]{0, 6}) {
            var invalid = new Bundle(); invalid.putInt("attempts", count);
            rejects(() -> BluetoothConfig.save(context, A, invalid));
        }
        store.secure.put("hypernova_bluetooth_020000000001", "9"); rejects(() -> BluetoothConfig.read(context, A));
        store.secure.put("hypernova_bluetooth_020000000001", "bad"); rejects(() -> BluetoothConfig.read(context, A));
        store.writable = false; rejects(() -> BluetoothConfig.save(context, B, defaults));
        System.out.println("Bluetooth configuration OK: defaults, devices/users, persistence, manual pause, toggle/reboot, unpair, namespace, notifications, invalid values and write failure");
    }
}'''
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix='bluetooth-config-test-', dir=root / '.local') as directory:
        temp = Path(directory)
        for name, code in SOURCES.items():
            target = temp / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(code, encoding='utf-8')
        target = temp / 'io/github/hypernova/feature/bluetooth/BluetoothConfig.java'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((root / 'app/src/main/java/io/github/hypernova/feature/bluetooth/BluetoothConfig.java').read_bytes())
        subprocess.run([str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(temp / 'out')]
                       + [str(p) for p in temp.rglob('*.java')], check=True)
        subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(temp / 'out'), 'Regression'], check=True)


if __name__ == '__main__':
    main()
