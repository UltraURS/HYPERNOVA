# SPDX-License-Identifier: GPL-3.0-only
"""Compile the real power saver UI/config/worker against a small JVM host model."""
import argparse
import runpy
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE = runpy.run_path(str(ROOT / 'tools/verify-bluetooth-reconnect.py'))['SOURCES']
SOURCES = {name: BASE[name] for name in (
    'android/annotation/SuppressLint.java', 'android/Manifest.java',
    'android/content/pm/PackageManager.java', 'android/content/BroadcastReceiver.java',
    'android/content/Intent.java', 'android/content/IntentFilter.java', 'android/net/Uri.java',
    'android/os/Bundle.java', 'android/os/Handler.java', 'android/os/HandlerThread.java', 'android/os/Looper.java',
    'android/widget/Toast.java', 'de/robv/android/xposed/XC_MethodHook.java',
    'de/robv/android/xposed/XposedBridge.java', 'de/robv/android/xposed/XposedHelpers.java')}
SOURCES['android/content/Intent.java'] = SOURCES['android/content/Intent.java'].replace(
    'public class Intent {', 'public class Intent { public static final String ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED";')
SOURCES['android/widget/Toast.java'] = SOURCES['android/widget/Toast.java'].replace('LENGTH_SHORT = 0', 'LENGTH_SHORT = 0, LENGTH_LONG = 1')
SOURCES['de/robv/android/xposed/XposedHelpers.java'] = SOURCES['de/robv/android/xposed/XposedHelpers.java'].replace(
    'return type.getField(name);', 'Field f = type.getDeclaredField(name); f.setAccessible(true); return f;').replace(
    '    public static Object callMethod(', '''    public static Constructor<?> findConstructorExact(Class<?> type, Class<?>... args) {
        try { return type.getConstructor(args); } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static Object newInstance(Class<?> type, Object... args) {
        for (var constructor : type.getConstructors()) if (constructor.getParameterCount() == args.length) {
            try { return constructor.newInstance(args); } catch (Exception e) { throw new RuntimeException(e); }
        } throw new IllegalArgumentException(type.getName());
    }
    public static void setIntField(Object object, String name, int value) {
        try { findField(object.getClass(), name).setInt(object, value); } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static void setBooleanField(Object object, String name, boolean value) {
        try { findField(object.getClass(), name).setBoolean(object, value); } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static Object callMethod(''')
SOURCES.update({
    'android/R.java': 'package android; public class R { public static class id { public static final int title = 16908310; } }',
    'android/view/View.java': 'package android.view; public class View {}',
    'android/widget/TextView.java': '''package android.widget;
public class TextView extends android.view.View {
    private CharSequence text; public void setText(CharSequence value) { text = value; } public CharSequence getText() { return text; }
}''',
    'android/util/AttributeSet.java': 'package android.util; public interface AttributeSet {}',
    'android/os/BatteryManager.java': '''package android.os;
public class BatteryManager { public static final String EXTRA_LEVEL = "level", EXTRA_SCALE = "scale", EXTRA_PLUGGED = "plugged"; }''',
    'android/database/ContentObserver.java': '''package android.database;
public class ContentObserver {
    public final android.os.Handler handler;
    public ContentObserver(android.os.Handler handler) { this.handler = handler; }
    public void onChange(boolean self, android.net.Uri uri) {}
}''',
    'android/content/ContentResolver.java': '''package android.content;
import android.net.Uri; import android.os.Bundle; import android.database.ContentObserver;
public class ContentResolver {
    public final java.util.Map<String, String> secure = new java.util.HashMap<>();
    public final java.util.Map<String, Integer> system = new java.util.HashMap<>();
    public final java.util.Map<ContentObserver, Uri> observers = new java.util.HashMap<>();
    public boolean writable = true; public int requests;
    public void registerContentObserver(Uri uri, boolean descendants, ContentObserver observer) { observers.put(observer, uri); }
    public void unregisterContentObserver(ContentObserver observer) { observers.remove(observer); }
    public void changed(Uri uri) {
        for (var entry : observers.entrySet()) if (entry.getValue().equals(uri)) {
            var observer = entry.getKey(); observer.handler.post(() -> observer.onChange(false, uri));
        }
    }
    public Bundle call(Uri uri, String method, String argument, Bundle args) {
        if (!uri.equals(Uri.parse("content://com.miui.powercenter.powersaver")) || !method.equals("changePowerMode")
                || argument != null || !args.getBoolean("POWER_SAVE_MODE_OPEN") || args.getBoolean("POWER_DEVICE_NOTICE", true))
            throw new AssertionError("Unexpected native power saver operation");
        requests++; return null; // Accepted asynchronously; do not pretend the mode has already changed.
    }
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
            String previous = value == null ? resolver.secure.remove(key) : resolver.secure.put(key, value);
            if (!java.util.Objects.equals(previous, value)) resolver.changed(getUriFor(key));
            return true;
        }
    }
    public static class System {
        public static int getInt(ContentResolver resolver, String key, int fallback) { return resolver.system.getOrDefault(key, fallback); }
    }
}''',
    'android/content/Context.java': '''package android.content;
public class Context {
    public static final int RECEIVER_NOT_EXPORTED = 4;
    final ContentResolver resolver; public boolean permitted = true;
    public BroadcastReceiver receiver; public android.os.Handler receiverHandler; public Intent sticky;
    public Context() { this(new ContentResolver()); } public Context(ContentResolver resolver) { this.resolver = resolver; }
    public ContentResolver getContentResolver() { return resolver; }
    public int checkSelfPermission(String permission) { return permitted ? 0 : -1; }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
        if (receiver != null) throw new AssertionError("Unexpected registration"); return sticky;
    }
    public Intent registerReceiver(BroadcastReceiver r, IntentFilter f, String permission, android.os.Handler handler, int flags) {
        if (flags != RECEIVER_NOT_EXPORTED || !f.actions.equals(java.util.Set.of(Intent.ACTION_BATTERY_CHANGED)))
            throw new AssertionError("Unexpected battery receiver scope");
        receiver = r; receiverHandler = handler;
        if (sticky != null) { Intent initial = sticky; handler.post(() -> r.onReceive(this, initial)); }
        return sticky;
    }
    public void unregisterReceiver(BroadcastReceiver r) { receiver = null; }
    public void battery(int level, int scale, int plugged) {
        sticky = new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra("level", level).putExtra("scale", scale).putExtra("plugged", plugged);
        if (receiver != null) { Intent event = sticky; receiverHandler.post(() -> receiver.onReceive(this, event)); }
    }
}''',
    'android/app/Activity.java': '''package android.app;
public class Activity extends android.content.Context {
    public Activity(android.content.ContentResolver resolver) { super(resolver); } public boolean isFinishing() { return false; }
}''',
    'androidx/preference/Preference.java': '''package androidx.preference;
public class Preference {
    public interface c { boolean onPreferenceChange(Preference preference, Object value); }
    final android.content.Context context; public String key, title, summary;
    public int order, titleChanges; public boolean visible = true, persistent = true; private c change;
    public Preference(android.content.Context context) { this.context = context; }
    public android.content.Context getContext() { return context; }
    public void setKey(String value) { key = value; }
    public void setTitle(CharSequence value) { if (!java.util.Objects.equals(title, value.toString())) titleChanges++; title = value.toString(); }
    public void setSummary(CharSequence value) { summary = value.toString(); } public void setOrder(int value) { order = value; }
    public void setPersistent(boolean value) { persistent = value; } public void setVisible(boolean value) { visible = value; }
    public void setOnPreferenceChangeListener(c value) { change = value; }
    public boolean change(Object value) { return change == null || change.onPreferenceChange(this, value); }
}''',
    'androidx/preference/PreferenceGroup.java': '''package androidx.preference;
public class PreferenceGroup extends Preference {
    public final java.util.List<Preference> children = new java.util.ArrayList<>();
    public PreferenceGroup(android.content.Context c) { super(c); }
    public boolean addPreference(Preference p) { children.add(p); return true; }
    public Preference findPreference(CharSequence key) {
        for (Preference child : children) {
            if (key.equals(child.key)) return child;
            if (child instanceof PreferenceGroup group) { Preference found = group.findPreference(key); if (found != null) return found; }
        } return null;
    }
}''',
    'androidx/preference/PreferenceScreen.java': 'package androidx.preference; public class PreferenceScreen extends PreferenceGroup { public PreferenceScreen(android.content.Context c) { super(c); } }',
    'androidx/preference/PreferenceCategory.java': 'package androidx.preference; public class PreferenceCategory extends PreferenceGroup { public PreferenceCategory(android.content.Context c) { super(c); } }',
    'androidx/preference/CheckBoxPreference.java': '''package androidx.preference;
public class CheckBoxPreference extends Preference { public boolean checked;
    public CheckBoxPreference(android.content.Context c) { super(c); } public void setChecked(boolean value) { checked = value; }
}''',
    'androidx/preference/SeekBarPreference.java': '''package androidx.preference;
public class SeekBarPreference extends Preference {
    public int a; int b; private int c = 100, d, drag; private boolean i;
    public SeekBarPreference(android.content.Context c, android.util.AttributeSet attrs) { super(c); }
    public void i(int max) { c = Math.max(b, max); } public void j(int increment) { d = increment; }
    public void k(int value) {
        int next = Math.max(b, Math.min(c, value));
        if (a != next) { a = next; de.robv.android.xposed.XposedHelpers.callMethod(this, "n", a); }
    }
    public void n(int value) {} // The host layout may omit its separate numeric TextView.
    public void onBindViewHolder(i row) {
        row.title.setText(title); de.robv.android.xposed.XposedHelpers.callMethod(this, "n", a);
    }
    public void startTracking() { drag = a; }
    public void dragTo(int value) { drag = Math.max(b, Math.min(c, value)); de.robv.android.xposed.XposedHelpers.callMethod(this, "n", drag); }
    public void stopTracking() {
        if (drag != a) { if (change(drag)) k(drag); else de.robv.android.xposed.XposedHelpers.callMethod(this, "n", a); }
    }
}''',
    'androidx/preference/i.java': '''package androidx.preference;
public class i {
    public final android.widget.TextView title = new android.widget.TextView();
    public android.view.View b(int id) { return id == android.R.id.title ? title : null; }
}''',
    'androidx/recyclerview/widget/RecyclerView.java': '''package androidx.recyclerview.widget;
public class RecyclerView {
    public static class ItemAnimator { public boolean ended; public void endAnimations() { ended = true; } }
    public ItemAnimator animator = new ItemAnimator(); public int animatedRefreshes;
    public void setItemAnimator(ItemAnimator value) { if (animator != null) animator.endAnimations(); animator = value; }
    public void refreshNativeOption() { if (animator != null) animatedRefreshes++; }
}''',
    'androidx/preference/PreferenceFragmentCompat.java': '''package androidx.preference;
public class PreferenceFragmentCompat {
    public androidx.recyclerview.widget.RecyclerView list;
    public androidx.recyclerview.widget.RecyclerView getListView() { return list; }
}''',
    'com/miui/powercenter/savemode/PowerSaveFragment.java': '''package com.miui.powercenter.savemode;
public class PowerSaveFragment extends androidx.preference.PreferenceFragmentCompat {
    final android.app.Activity activity; public final androidx.preference.PreferenceScreen screen;
    public boolean optionsBeforeBinding;
    public PowerSaveFragment(android.app.Activity activity) {
        this.activity = activity; screen = new androidx.preference.PreferenceScreen(activity);
        var original = new androidx.preference.Preference(activity); original.setKey("original_power_option"); screen.addPreference(original);
    }
    public void onCreatePreferences(android.os.Bundle state, String rootKey) {}
    public void bind() {
        optionsBeforeBinding = screen.findPreference("hypernova_power_threshold") != null;
        list = new androidx.recyclerview.widget.RecyclerView();
    }
    public void onResume() { list.refreshNativeOption(); } public android.app.Activity getActivity() { return activity; }
    public androidx.preference.PreferenceScreen getPreferenceScreen() { return screen; }
}''',
    'io/github/hypernova/hook/HookLog.java': '''package io.github.hypernova.hook;
public class HookLog {
    public static int errors;
    public static void info(String message) {} public static void error(String message, Throwable error) { errors++; }
}''',
    'Regression.java': '''import android.content.*; import android.os.Handler; import android.provider.Settings;
import androidx.preference.*; import com.miui.powercenter.savemode.PowerSaveFragment;
import de.robv.android.xposed.XposedHelpers; import io.github.hypernova.hook.HookLog;
import io.github.hypernova.feature.power.*;
public class Regression {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void create(PowerSaveFragment page) { XposedHelpers.callMethod(page, "onCreatePreferences", null, null); page.bind(); }
    static void resume(PowerSaveFragment page) { XposedHelpers.callMethod(page, "onResume"); }
    static i bind(SeekBarPreference slider) { i row = new i(); XposedHelpers.callMethod(slider, "onBindViewHolder", row); return row; }
    static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; } throw new AssertionError("Expected rejection"); }
    public static void main(String[] args) throws Exception {
        Context context = new Context(); ContentResolver store = context.getContentResolver();
        check(PowerSaveConfig.read(context).equals(new PowerSaveConfig(false, 20)), "Default must be disabled at 20 percent");
        store.secure.put("unrelated", "keep");
        PowerSaveSettingsHook.install(Regression.class.getClassLoader());
        PowerSaveFragment page = new PowerSaveFragment(new android.app.Activity(store)); create(page);
        var originalAnimator = page.list.animator;
        var unrelatedList = new androidx.recyclerview.widget.RecyclerView();
        resume(page); resume(page);
        PreferenceCategory category = (PreferenceCategory) page.screen.findPreference("hypernova_power_category");
        CheckBoxPreference toggle = (CheckBoxPreference) page.screen.findPreference("hypernova_power_enabled");
        SeekBarPreference slider = (SeekBarPreference) page.screen.findPreference("hypernova_power_threshold");
        String defects = (!slider.title.contains("20%") ? "Missing visible threshold percentage. " : "")
                + (!page.optionsBeforeBinding ? "Options inserted after initial list binding. " : "")
                + (page.list.animator != null || page.list.animatedRefreshes != 0 ? "Native row refresh can animate independently. " : "");
        check(defects.isEmpty(), defects);
        check(originalAnimator.ended && unrelatedList.animator != null && !unrelatedList.animator.ended,
                "Only this page's item animations must stop; another list must remain untouched");
        check(page.screen.children.size() == 2 && category.children.size() == 2 && category.title.equals("HYPERNOVA") && category.order == 10000, "Category must appear once at the bottom");
        check(page.screen.findPreference("original_power_option") != null, "Native options must be preserved");
        check(!toggle.checked && !slider.visible && slider.a == 20 && !toggle.persistent && !slider.persistent, "Initial UI must match saved configuration");
        check((int) XposedHelpers.getObjectField(slider, "b") == 20 && (int) XposedHelpers.getObjectField(slider, "c") == 80
                && (int) XposedHelpers.getObjectField(slider, "d") == 1 && (boolean) XposedHelpers.getObjectField(slider, "i"), "Slider range, increment and value label must match");
        check(toggle.change(true) && slider.visible, "Enable must reveal threshold");
        i recycledRow = bind(slider), row = bind(slider);
        int titleChanges = slider.titleChanges;
        slider.startTracking();
        for (int value : new int[]{20, 37, 80, 45, 80}) {
            slider.dragTo(value);
            check(row.title.getText().toString().contains(value + "%"), "Visible percentage must update before release: " + value);
            check(slider.a == 20 && PowerSaveConfig.read(context).threshold() == 20 && slider.titleChanges == titleChanges,
                    "Dragging must not commit a value or notify the adapter to rebind the slider");
            check(recycledRow.title.getText().toString().contains("20%"), "Only the currently bound row may change");
        }
        slider.stopTracking();
        check(slider.title.contains("80%"), "Visible title must show the saved upper threshold even when the host omits its summary and value label");
        check(PowerSaveConfig.read(new Context(store)).equals(new PowerSaveConfig(true, 80)), "Another host context must see the same atomic configuration");
        slider.startTracking(); slider.dragTo(40); slider.dragTo(80); slider.stopTracking();
        check(row.title.getText().toString().contains("80%") && slider.titleChanges == titleChanges + 1, "Returning to the saved value must restore its display without committing again");
        store.writable = false;
        slider.startTracking(); slider.dragTo(60); slider.stopTracking();
        check(slider.a == 80 && row.title.getText().toString().contains("80%") && PowerSaveConfig.read(context).threshold() == 80,
                "Rejected save must restore the visible percentage to the committed value");
        store.writable = true;
        SeekBarPreference nativeSlider = new SeekBarPreference(context, null); nativeSlider.setTitle("Native slider"); nativeSlider.k(20);
        i nativeRow = bind(nativeSlider); nativeSlider.startTracking(); nativeSlider.dragTo(50); nativeSlider.stopTracking();
        check(nativeSlider.a == 50 && nativeRow.title.getText().equals("Native slider"), "Another native slider's title and behavior must remain untouched");
        check(toggle.change(false) && !slider.visible && PowerSaveConfig.read(context).threshold() == 80, "Disable must hide but retain threshold");
        store.writable = false;
        check(!toggle.change(true) && !toggle.checked, "Write failure must not fake a successful toggle");
        check(!slider.change(50) && slider.a == 80 && slider.title.contains("80%"), "Write failure must retain the saved percentage");
        store.writable = true;
        for (int value : new int[]{19, 81}) rejects(() -> new PowerSaveConfig(true, value));
        check(!PowerSaveConfig.isConfigChange(Settings.Secure.getUriFor("unrelated")), "Ignore unrelated configuration notifications");
        check("keep".equals(store.secure.get("unrelated")), "Do not modify another feature's settings");
        HookLog.errors = 0;
        Context denied = new Context(); denied.permitted = false; AutoPowerSaveHook.install(denied);
        check(denied.receiver == null && denied.getContentResolver().observers.isEmpty() && HookLog.errors == 1, "Permission rejection must not leave a worker registered");
        HookLog.errors = 0;
        context.battery(49, 100, 0); AutoPowerSaveHook.install(context); Handler.drain();
        check(store.requests == 0, "Disabled setting must not trigger");
        check(slider.change(50) && toggle.change(true), "User can save a 50 percent rule"); Handler.drain();
        check(store.requests == 1, "Enabling below the threshold must immediately use the native provider");
        context.battery(48, 100, 0); Handler.drain();
        check(store.requests == 1, "Repeated events and manual off must not resend an asynchronous request");
        context.battery(48, 100, 2); Handler.drain(); context.battery(48, 100, 0); Handler.drain();
        check(store.requests == 2, "Unplugging while still low must rearm");
        context.battery(48, 100, 2); context.battery(48, 100, 0); Handler.drain();
        check(store.requests == 3, "Queued plug/unplug events must not lose the new discharge interval");
        context.battery(50, 100, 0); Handler.drain();
        check(store.requests == 3, "Equality must not trigger");
        context.battery(49, 100, 0); Handler.drain(); check(store.requests == 4, "Recovery then decline must rearm");
        check(toggle.change(false)); Handler.drain();
        context.battery(10, 100, 0); context.battery(10, 100, 4); check(toggle.change(true)); Handler.drain();
        check(store.requests == 4, "Stale unplugged events must not trigger while power is connected");
        store.system.put("power_supersave_mode_open", 1); context.battery(10, 100, 0); Handler.drain();
        check(store.requests == 4, "Do not replace super power saving mode");
        store.system.put("power_supersave_mode_open", 0); context.battery(9, 100, 0); Handler.drain();
        check(store.requests == 4, "Manual exit from an already active mode must also be honored");
        check(toggle.change(false)); Handler.drain(); check(toggle.change(true)); Handler.drain();
        check(store.requests == 5, "Explicit disable and reenable must rearm");
        PowerSaveFragment recreated = new PowerSaveFragment(new android.app.Activity(store)); create(recreated); resume(recreated);
        check(((CheckBoxPreference) recreated.screen.findPreference("hypernova_power_enabled")).checked
                && ((SeekBarPreference) recreated.screen.findPreference("hypernova_power_threshold")).a == 50
                && recreated.screen.findPreference("hypernova_power_threshold").title.contains("50%")
                && recreated.optionsBeforeBinding && recreated.list.animatedRefreshes == 0, "Recreated page must restore the percentage before binding without moving native rows or resending requests");
        check(store.requests == 5 && HookLog.errors == 0, "Normal operation must remain quiet");
        store.secure.put("hypernova_power_auto", "invalid"); rejects(() -> PowerSaveConfig.read(context));
        context.battery(1, 100, 0); Handler.drain(); check(store.requests == 5 && HookLog.errors == 1, "Invalid config must fail closed");
        var field = AutoPowerSaveHook.class.getDeclaredField("instance"); field.setAccessible(true);
        var stop = AutoPowerSaveHook.class.getDeclaredMethod("stop"); stop.setAccessible(true); stop.invoke(field.get(null));
        check(context.receiver == null && store.observers.isEmpty(), "Stop must unregister resources");
        System.out.println("Automatic power saver OK: live percentages before release, release-only commit, no drag rebinds, rebound row isolation, rejected-save display rollback, untouched native sliders, options before binding, page-scoped item animation suppression, 20..80 persistence, atomic writes, permission failure, background provider, strict threshold, charging, manual off, recovery and cleanup");
    }
    static void check(boolean value) { check(value, "Preference change rejected"); }
}'''
})


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='auto-power-test-', dir=ROOT / '.local') as directory:
        temp = Path(directory)
        for name, code in SOURCES.items():
            target = temp / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(code, encoding='utf-8')
        for name in ('PowerSaveConfig', 'PowerSaveRule', 'PowerSaveSettingsHook', 'AutoPowerSaveHook'):
            target = temp / f'io/github/hypernova/feature/power/{name}.java'
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / f'app/src/main/java/io/github/hypernova/feature/power/{name}.java').read_bytes())
        subprocess.run([str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(temp / 'out')]
                       + [str(p) for p in temp.rglob('*.java')], check=True)
        subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(temp / 'out'), 'Regression'], check=True)


if __name__ == '__main__':
    main()
