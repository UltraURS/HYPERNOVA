# SPDX-License-Identifier: GPL-3.0-only
"""Exercise the real sidebar hooks with a small JVM host model, without an Android runtime."""
import argparse
import subprocess
import tempfile
from pathlib import Path


SOURCES = {
    'android/content/Intent.java': '''package android.content;
public class Intent {
    final String action; final java.util.Map<String, Object> extras = new java.util.HashMap<>();
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
    public Intent putExtra(String key, Object value) { extras.put(key, value); return this; }
    public int getIntExtra(String key, int fallback) { return extras.containsKey(key) ? (Integer) extras.get(key) : fallback; }
    public <T> T getParcelableExtra(String key, Class<T> type) { return type.cast(extras.get(key)); }
}''',
    'android/content/Context.java': '''package android.content;
public class Context {
    public <T> T getSystemService(Class<T> type) {
        try { return type.getConstructor().newInstance(); } catch (Exception e) { throw new RuntimeException(e); }
    }
}''',
    'android/app/Service.java': 'package android.app; public class Service extends android.content.Context { public static final int START_STICKY = 1; }',
    'android/app/KeyguardManager.java': 'package android.app; public class KeyguardManager { public boolean isKeyguardLocked() { return false; } }',
    'android/os/PowerManager.java': 'package android.os; public class PowerManager { public boolean isInteractive() { return true; } }',
    'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper() { return new Looper(); } }',
    'android/os/Handler.java': '''package android.os;
public class Handler {
    public Handler(Looper looper) {} public boolean post(Runnable action) { action.run(); return true; }
    public boolean postDelayed(Runnable action, long delay) { return true; } public void removeCallbacks(Runnable action) {}
}''',
    'android/os/ResultReceiver.java': '''package android.os;
public class ResultReceiver { public int result = -1; public void send(int code, Object data) { result = code; } }
''',
    'android/view/WindowManager.java': '''package android.view;
public class WindowManager {
    public static class LayoutParams {
        public static final int FLAG_NOT_TOUCHABLE = 16;
        public int flags, miuiFlags = 0x400; public float alpha = 1;
    }
    public void updateViewLayout(View view, LayoutParams params) { view.params = params; }
}''',
    'android/view/View.java': '''package android.view;
public class View {
    public static final int VISIBLE = 0, GONE = 8;
    public WindowManager.LayoutParams params = new WindowManager.LayoutParams();
    public int visibility, x;
    final ViewTreeObserver observer = new ViewTreeObserver();
    public Object getLayoutParams() { return params; }
    public Object getParent() { return this; }
    public android.content.Context getContext() { return new android.content.Context(); }
    public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
    public float getAlpha() { return 1; }
    public boolean getGlobalVisibleRect(android.graphics.Rect rect) { return true; }
    public boolean onTouchEvent(MotionEvent event) { return false; }
    public boolean post(Runnable action) { action.run(); return true; }
    public int getVisibility() { return visibility; } public void setVisibility(int value) { visibility = value; }
    public boolean isShown() { return visibility == VISIBLE; } public boolean isAttachedToWindow() { return true; }
    public boolean isLaidOut() { return true; } public boolean isLayoutRequested() { return false; }
    public int getWidth() { return 100; } public int getHeight() { return 300; }
    public void getLocationOnScreen(int[] location) { location[0] = x; location[1] = 200; }
    public ViewTreeObserver getViewTreeObserver() { return observer; }
    public void requestLayout() {} public void invalidate() {}
}''',
    'android/view/ViewGroup.java': '''package android.view;
public class ViewGroup extends View {
    public boolean onInterceptTouchEvent(MotionEvent event) { return false; } public int getChildCount() { return 1; }
}
''',
    'android/view/ViewTreeObserver.java': '''package android.view;
public class ViewTreeObserver {
    public interface OnPreDrawListener { boolean onPreDraw(); }
    final java.util.List<OnPreDrawListener> listeners = new java.util.ArrayList<>();
    public void addOnPreDrawListener(OnPreDrawListener listener) { listeners.add(listener); }
    public void removeOnPreDrawListener(OnPreDrawListener listener) { listeners.remove(listener); }
    public boolean isAlive() { return true; }
    public void draw() { for (var listener : java.util.List.copyOf(listeners)) listener.onPreDraw(); }
}
''',
    'android/view/MotionEvent.java': '''package android.view;
public class MotionEvent {
    public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3, ACTION_POINTER_DOWN=5;
    public int getActionMasked() { return 0; } public int getPointerCount() { return 1; }
    public long getEventTime() { return 0; }
    public float getRawX() { return 0; } public float getRawY() { return 0; }
}''',
    'android/view/ViewConfiguration.java': '''package android.view;
public class ViewConfiguration {
    public static ViewConfiguration get(android.content.Context context) { return new ViewConfiguration(); }
    public int getScaledTouchSlop() { return 8; } public static int getLongPressTimeout() { return 500; }
}''',
    'android/graphics/Rect.java': '''package android.graphics;
public class Rect { public boolean contains(int x, int y) { return true; } public boolean isEmpty() { return false; } }
''',
    'android/content/res/Resources.java': '''package android.content.res;
public class Resources { public android.util.DisplayMetrics getDisplayMetrics() { return new android.util.DisplayMetrics(); } }
''',
    'android/util/DisplayMetrics.java': 'package android.util; public class DisplayMetrics { public float density = 1; public int widthPixels = 1000; }',
    'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
public class XC_MethodHook {
    public static class MethodHookParam {
        public Object thisObject; public Object[] args; public Throwable error;
        public Object result; public boolean returnEarly;
        public boolean hasThrowable() { return error != null; }
        public void setResult(Object value) { result = value; returnEarly = true; }
    }
    public class Unhook { public void unhook() {} }
    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
}''',
    'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
import java.lang.reflect.*; import java.util.*;
public class XposedBridge {
    static final Map<Method, List<XC_MethodHook>> hooks = new HashMap<>();
    public static XC_MethodHook.Unhook hookMethod(Method method, XC_MethodHook hook) {
        hooks.computeIfAbsent(method, k -> new ArrayList<>()).add(hook); return hook.new Unhook();
    }
    public static Object invoke(Method method, Object object, Object... args) {
        var p = new XC_MethodHook.MethodHookParam(); p.thisObject = object; p.args = args;
        var active = hooks.getOrDefault(method, List.of()); Object result = null;
        try {
            for (var hook : active) hook.beforeHookedMethod(p);
            try { result = p.returnEarly ? p.result : method.invoke(object, args); }
            catch (InvocationTargetException e) { p.error = e.getCause(); }
            for (int i = active.size() - 1; i >= 0; i--) active.get(i).afterHookedMethod(p);
        } catch (Throwable e) { throw new RuntimeException(e); }
        if (p.error != null) throw new RuntimeException(p.error);
        return result;
    }
}''',
    'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
import java.lang.reflect.*; import java.util.*;
public class XposedHelpers {
    static final Map<Object, Map<String, Object>> extra = new IdentityHashMap<>();
    public static Class<?> findClass(String name, ClassLoader loader) {
        try { return Class.forName(name, true, loader); } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static Method findMethodExact(Class<?> type, String name, Class<?>... args) {
        try { return type.getMethod(name, args); } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static Field findField(Class<?> type, String name) {
        try { return type.getField(name); } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static Object callMethod(Object object, String name, Object... args) {
        for (var method : object.getClass().getMethods())
            if (method.getName().equals(name) && method.getParameterCount() == args.length)
                return XposedBridge.invoke(method, object, args);
        throw new IllegalArgumentException(name);
    }
    public static Object getObjectField(Object object, String name) {
        try { return findField(object.getClass(), name).get(object); } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static boolean getBooleanField(Object object, String name) { return (Boolean) getObjectField(object, name); }
    public static Object getAdditionalInstanceField(Object object, String name) {
        return extra.getOrDefault(object, Map.of()).get(name);
    }
    public static void setAdditionalInstanceField(Object object, String name, Object value) {
        extra.computeIfAbsent(object, k -> new HashMap<>()).put(name, value);
    }
    public static Object removeAdditionalInstanceField(Object object, String name) {
        return extra.getOrDefault(object, new HashMap<>()).remove(name);
    }
}''',
    'io/github/hypernova/hook/HookLog.java': '''package io.github.hypernova.hook;
public class HookLog {
    public static void info(String text) {}
    public static void error(String text, Throwable error) { throw new AssertionError(text, error); }
}''',
    'com/miui/gamebooster/service/DockWindowManagerService.java': '''package com.miui.gamebooster.service;
public class DockWindowManagerService extends android.app.Service {
    public A4.n b; public boolean c = true;
    public int onStartCommand(android.content.Intent intent, int flags, int id) { return 1; }
}''',
    'A4/n.java': '''package A4;
public class n {
    public com.miui.dock.sidebar.j e = new com.miui.dock.sidebar.j(this, true);
    public com.miui.dock.sidebar.j f = new com.miui.dock.sidebar.j(this, false);
    public boolean p = true;
    public void L1() {}
    public void e1() {
        de.robv.android.xposed.XposedHelpers.callMethod(e, "U", false);
        de.robv.android.xposed.XposedHelpers.callMethod(f, "U", false);
    }
    public void A1(com.miui.dock.sidebar.j wrapper, Runnable completed) { completed.run(); }
    public void Q1(com.miui.dock.sidebar.j wrapper) {}
    public void X0(com.miui.dock.sidebar.j wrapper) {
        if (wrapper.F()) wrapper.z().params.miuiFlags &= ~32;
    }
    public void Z1(android.view.View view, android.view.WindowManager.LayoutParams params) {
        new android.view.WindowManager().updateViewLayout(view, params);
    }
    public void h1(com.miui.dock.sidebar.j wrapper) { de.robv.android.xposed.XposedHelpers.callMethod(this, "i1", wrapper, false); }
    public void i1(com.miui.dock.sidebar.j wrapper, boolean move) {
        wrapper.z().params.flags |= 16;
        de.robv.android.xposed.XposedHelpers.callMethod(this, "Z1", wrapper.z(), wrapper.z().params);
        de.robv.android.xposed.XposedHelpers.callMethod(wrapper, "U", false);
    }
    public void K0(boolean move, com.miui.gamebooster.windowmanager.newbox.TurboLayout content, com.miui.dock.sidebar.j wrapper, android.view.View panel) {}
    public void k1(com.miui.dock.sidebar.j wrapper, boolean move) { de.robv.android.xposed.XposedHelpers.callMethod(wrapper, "U", false); }
}''',
    'com/miui/gamebooster/windowmanager/newbox/TurboLayout.java': '''package com.miui.gamebooster.windowmanager.newbox;
public class TurboLayout extends android.view.ViewGroup { public android.view.View getDockLayout() { return this; } }
''',
    'com/miui/dock/sidebar/j.java': '''package com.miui.dock.sidebar;
import android.view.*; import de.robv.android.xposed.XposedHelpers;
public class j {
    final A4.n manager; final View panel = new View(), cover = new View(), handle = new View(); boolean opened;
    final boolean left; public int opens;
    final com.miui.gamebooster.windowmanager.newbox.TurboLayout content = new com.miui.gamebooster.windowmanager.newbox.TurboLayout();
    public boolean fail;
    public j(A4.n manager, boolean left) {
        this.manager = manager; this.left = left; panel.params.flags = 824; cover.params.flags = 328488;
        panel.x = handle.x = content.x = left ? 0 : 900;
        if (!left) panel.setVisibility(View.GONE);
        if (!left) cover.setVisibility(View.GONE);
    }
    public View u() { return cover; } public View z() { return panel; } public View w() { return handle; }
    public boolean F() { return opened; } public void U(boolean value) { opened = value; }
    public boolean H() { return left; } public A4.n o() { return manager; }
    public com.miui.gamebooster.windowmanager.newbox.TurboLayout A() { return content; }
    public void Q() {
        opens++;
        // Mirrors U2.h.k -> B1.H(true) -> Z1, followed by R0 -> O -> U(true).
        panel.params.flags &= ~16;
        XposedHelpers.callMethod(manager, "Z1", panel, panel.params);
        if (fail) throw new IllegalStateException("native preparation failed");
        XposedHelpers.callMethod(this, "U", true);
        // Native createDockView adds the pass-down bit after U(true), before Q returns.
        panel.params.miuiFlags |= 32;
    }
}''',
    'Regression.java': '''import android.view.*;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.feature.sidebar.SidebarTriggerHook;
public class Regression {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void openPanels() {
        var manager = new A4.n(); var service = new com.miui.gamebooster.service.DockWindowManagerService(); service.b = manager;
        for (int side = 0; side < 2; side++) {
            var wrapper = side == 0 ? manager.e : manager.f;
            for (int cycle = 0; cycle < 3; cycle++) {
                var reply = new android.os.ResultReceiver();
                var intent = new android.content.Intent(io.github.hypernova.feature.sidebar.SidebarPanelHook.ACTION)
                    .putExtra(io.github.hypernova.feature.sidebar.SidebarPanelHook.SIDE, side)
                    .putExtra(io.github.hypernova.feature.sidebar.SidebarPanelHook.REPLY, reply);
                XposedHelpers.callMethod(service, "onStartCommand", intent, 0, 1);
                wrapper.z().getViewTreeObserver().draw();
                check((wrapper.z().params.miuiFlags & 32) == 0, "expanded panel must disable MIUI click pass-down before animation");
                check(wrapper.z().params.miuiFlags == 0x400, "unrelated MIUI flags must survive");
                wrapper.z().getViewTreeObserver().draw();
                check(reply.result == 1, "visible panel must acknowledge open request");
                check((wrapper.z().params.flags & 16) == 0, "isolated panel must remain touchable");
                int opens = wrapper.opens;
                // A repeated request for an existing panel must clear pass-down without rebuilding it.
                wrapper.z().params.miuiFlags |= 32;
                var repeated = new android.os.ResultReceiver();
                intent.putExtra(io.github.hypernova.feature.sidebar.SidebarPanelHook.REPLY, repeated);
                XposedHelpers.callMethod(service, "onStartCommand", intent, 0, 1);
                wrapper.z().getViewTreeObserver().draw();
                check(repeated.result == 1 && wrapper.opens == opens, "existing panel must not be rebuilt");
                check((wrapper.z().params.miuiFlags & 32) == 0, "existing panel must disable MIUI click pass-down");
                XposedHelpers.callMethod(wrapper, "U", false);
                if (side == 1) check(wrapper.z().getVisibility() == View.GONE && wrapper.u().getVisibility() == View.GONE,
                    "auxiliary window visibility must be restored");
            }
        }
    }
    static void hidden(com.miui.dock.sidebar.j wrapper) {
        for (View view : new View[]{wrapper.u(), wrapper.z()})
            check(view.params.alpha == 0 && (view.params.flags & 16) != 0, "collapsed edge must pass through");
    }
    public static void main(String[] args) {
        io.github.hypernova.feature.sidebar.SidebarPanelHook.install(Regression.class.getClassLoader());
        openPanels();
        SidebarTriggerHook.install(Regression.class.getClassLoader());
        io.github.hypernova.feature.sidebar.SidebarSwipeHook.install(Regression.class.getClassLoader());
        openPanels();
        var manager = new A4.n(); XposedHelpers.callMethod(manager, "L1");
        hidden(manager.e); hidden(manager.f);
        for (var wrapper : new com.miui.dock.sidebar.j[]{manager.e, manager.f}) {
            for (int cycle = 0; cycle < 3; cycle++) {
                XposedHelpers.callMethod(wrapper, "Q");
                check(wrapper.z().params.alpha == 1, "expanded panel must be visible");
                check((wrapper.z().params.flags & 16) == 0, "expanded panel must receive touches");
                wrapper.z().params.flags |= 0x400000;
                XposedHelpers.callMethod(manager, "Z1", wrapper.z(), wrapper.z().params);
                check((wrapper.z().params.flags & 16) == 0, "layout update must not disable open panel");
                XposedHelpers.callMethod(wrapper, "U", false); hidden(wrapper);
                check((wrapper.z().params.flags & 0x400000) != 0, "unrelated flags must survive");
            }
            wrapper.fail = true;
            try { XposedHelpers.callMethod(wrapper, "Q"); throw new AssertionError("expected failure"); }
            catch (RuntimeException expected) { hidden(wrapper); }
            wrapper.fail = false;
            XposedHelpers.callMethod(wrapper, "Q");
            check((wrapper.z().params.flags & 16) == 0, "failed opening must not poison retry");
            XposedHelpers.callMethod(manager, "h1", wrapper);
            check(wrapper.z().params.alpha == 1, "closing animation must remain visible");
            XposedHelpers.callMethod(manager, "K0", false, wrapper.A(), wrapper, wrapper.z());
            hidden(wrapper);
            XposedHelpers.callMethod(wrapper, "Q"); XposedHelpers.callMethod(manager, "h1", wrapper);
            XposedHelpers.callMethod(manager, "k1", wrapper, false); hidden(wrapper);
            check(XposedHelpers.getAdditionalInstanceField(wrapper, "hypernova_sidebar_closing") == null, "immediate close must clear animation state");
            XposedHelpers.callMethod(wrapper, "U", false); hidden(wrapper);
        }
        var unrelated = new View(); XposedHelpers.callMethod(manager, "Z1", unrelated, unrelated.params);
        check(unrelated.params.flags == 0 && unrelated.params.alpha == 1, "unrelated window changed");
        System.out.println("Sidebar window lifecycle OK: both edges, MIUI click pass-down, repeated opens, trigger hiding on/off, layout, failure and retry, animated and immediate closes, unrelated window");
    }
}'''
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--source', type=Path, help='Alternative hook source for regression checks')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = args.source or root / 'app/src/main/java/io/github/hypernova/feature/sidebar/SidebarTriggerHook.java'
    cache = root / '.local'
    cache.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='sidebar-window-test-', dir=cache) as directory:
        temp = Path(directory)
        for name, content in SOURCES.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
        path = temp / 'io/github/hypernova/feature/sidebar/SidebarTriggerHook.java'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(source.read_bytes())
        for name in ('SidebarPanelHook', 'SidebarSwipeHook', 'SidebarDismissGesture'):
            path = temp / ('io/github/hypernova/feature/sidebar/' + name + '.java')
            path.write_bytes((root / ('app/src/main/java/io/github/hypernova/feature/sidebar/' + name + '.java')).read_bytes())
        subprocess.run([str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(temp / 'out')]
                       + [str(path) for path in temp.rglob('*.java')], check=True)
        subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(temp / 'out'), 'Regression'], check=True)


if __name__ == '__main__':
    main()
