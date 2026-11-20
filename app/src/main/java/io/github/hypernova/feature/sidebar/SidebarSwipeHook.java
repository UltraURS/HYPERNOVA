// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.sidebar;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Intercepts only outward drags begun on a registered native sidebar app list. */
public final class SidebarSwipeHook {
    private static final String TRACKER = "hypernova_sidebar_swipe", CLOSING = "hypernova_sidebar_closing";
    private SidebarSwipeHook() { }

    static boolean isClosing(Object wrapper) {
        return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(wrapper, CLOSING));
    }

    public static void install(ClassLoader loader) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Class<?> wrapper = XposedHelpers.findClass("com.miui.dock.sidebar.j", loader);
            Class<?> manager = XposedHelpers.findClass("A4.n", loader);
            Class<?> turbo = XposedHelpers.findClass("com.miui.gamebooster.windowmanager.newbox.TurboLayout", loader);
            var opened = XposedHelpers.findMethodExact(wrapper, "U", boolean.class);
            var close = XposedHelpers.findMethodExact(manager, "i1", wrapper, boolean.class);
            var completed = XposedHelpers.findMethodExact(manager, "K0", boolean.class, turbo, wrapper, View.class);
            var immediate = XposedHelpers.findMethodExact(manager, "k1", wrapper, boolean.class);
            for (String name : new String[]{"A", "F", "H", "o"}) XposedHelpers.findMethodExact(wrapper, name);
            XposedHelpers.findMethodExact(manager, "h1", wrapper);
            XposedHelpers.findMethodExact(turbo, "getDockLayout");
            hooks.add(XposedBridge.hookMethod(opened, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    View view = (View) XposedHelpers.callMethod(param.thisObject, "A");
                    if ((boolean) param.args[0]) {
                        XposedHelpers.setAdditionalInstanceField(view, TRACKER, new Tracker(view, param.thisObject));
                    } else XposedHelpers.removeAdditionalInstanceField(view, TRACKER);
                }
            }));
            hooks.add(XposedBridge.hookMethod(close, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if ((boolean) XposedHelpers.callMethod(param.args[0], "F"))
                        XposedHelpers.setAdditionalInstanceField(param.args[0], CLOSING, true);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || (boolean) XposedHelpers.callMethod(param.args[0], "F")) finishClosing(param.args[0]);
                }
            }));
            hooks.add(XposedBridge.hookMethod(completed, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { finishClosing(param.args[2]); }
            }));
            hooks.add(XposedBridge.hookMethod(immediate, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { finishClosing(param.args[0]); }
            }));
            hooks.add(XposedBridge.hookMethod(XposedHelpers.findMethodExact(ViewGroup.class,
                "onInterceptTouchEvent", MotionEvent.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Tracker tracker = (Tracker) XposedHelpers.getAdditionalInstanceField(param.thisObject, TRACKER);
                    if (tracker == null) return;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) tracker.begin((View) param.thisObject, event);
                    else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                        && tracker.gesture.move(event.getRawX(), event.getRawY(), event.getEventTime(), event.getPointerCount())) param.setResult(true);
                    else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) tracker.gesture.cancel();
                    else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) tracker.gesture.reset();
                    else if (event.getActionMasked() == MotionEvent.ACTION_UP && !tracker.gesture.captured()) tracker.gesture.reset();
                }
            }));
            hooks.add(XposedBridge.hookMethod(XposedHelpers.findMethodExact(View.class,
                "onTouchEvent", MotionEvent.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Tracker tracker = (Tracker) XposedHelpers.getAdditionalInstanceField(param.thisObject, TRACKER);
                    if (tracker == null || !tracker.gesture.captured()) return;
                    MotionEvent event = (MotionEvent) param.args[0]; param.setResult(true);
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_MOVE) tracker.gesture.move(event.getRawX(), event.getRawY(), event.getEventTime(), event.getPointerCount());
                    else if (action == MotionEvent.ACTION_POINTER_DOWN) tracker.gesture.cancel();
                    else if (action == MotionEvent.ACTION_CANCEL) tracker.gesture.reset();
                    else if (action == MotionEvent.ACTION_UP
                        && tracker.gesture.release(event.getRawX(), event.getRawY(), event.getPointerCount())) {
                        Object owner = tracker.owner.get();
                        if (owner != null) ((View) param.thisObject).post(() -> {
                            try {
                                if (!isClosing(owner) && (boolean) XposedHelpers.callMethod(owner, "F")) {
                                    XposedHelpers.callMethod(XposedHelpers.callMethod(owner, "o"), "h1", owner);
                                    HookLog.info("sidebar-swipe DISMISS");
                                }
                            } catch (Throwable error) { HookLog.error("sidebar-swipe close", error); }
                        });
                    }
                }
            }));
            HookLog.info("sidebar-swipe INSTALLED");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("sidebar-swipe install", error);
        }
    }

    private static void finishClosing(Object wrapper) {
        if (XposedHelpers.removeAdditionalInstanceField(wrapper, CLOSING) == null) return;
        if (!(boolean) XposedHelpers.callMethod(wrapper, "F")) SidebarPanelHook.restoreVisibility(wrapper);
        SidebarTriggerHook.refreshTracked(wrapper);
    }

    private static final class Tracker {
        final WeakReference<Object> owner;
        final SidebarDismissGesture gesture;
        Tracker(View view, Object owner) {
            this.owner = new WeakReference<>(owner);
            int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
            gesture = new SidebarDismissGesture(slop, Math.max(slop * 2, 24 * view.getResources().getDisplayMetrics().density),
                ViewConfiguration.getLongPressTimeout());
        }
        void begin(View view, MotionEvent event) {
            gesture.reset(); Object wrapper = owner.get();
            if (wrapper == null || isClosing(wrapper) || event.getPointerCount() != 1 || view.getAlpha() < 0.95f) return;
            View dock = (View) XposedHelpers.callMethod(view, "getDockLayout");
            Rect bounds = new Rect();
            if (dock != null && dock.getGlobalVisibleRect(bounds) && bounds.contains((int) event.getRawX(), (int) event.getRawY()))
                gesture.begin((boolean) XposedHelpers.callMethod(wrapper, "H"), event.getRawX(), event.getRawY(), event.getEventTime());
        }
    }
}
