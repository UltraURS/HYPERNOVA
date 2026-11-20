package io.github.hypernova.feature.sidebar;

import android.content.Intent;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Keeps the collapsed native windows laid out, transparent and unable to intercept edge gestures. */
public final class SidebarTriggerHook {
    private static final String OWNER = "hypernova_sidebar_window_owner", SAVED = "hypernova_sidebar_window_saved";
    private static final String OPENING = "hypernova_sidebar_window_opening";
    private SidebarTriggerHook() { }

    public static void install(ClassLoader loader) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Class<?> manager = XposedHelpers.findClass("A4.n", loader);
            Class<?> wrapper = XposedHelpers.findClass("com.miui.dock.sidebar.j", loader);
            Class<?> service = XposedHelpers.findClass(SidebarPanelHook.SERVICE, loader);
            var show = XposedHelpers.findMethodExact(manager, "L1");
            var layout = XposedHelpers.findMethodExact(manager, "Z1", View.class, WindowManager.LayoutParams.class);
            var opened = XposedHelpers.findMethodExact(wrapper, "U", boolean.class);
            var prepare = XposedHelpers.findMethodExact(wrapper, "Q");
            var start = XposedHelpers.findMethodExact(service, "onStartCommand", Intent.class, int.class, int.class);
            XposedHelpers.findField(service, "b");
            XposedHelpers.findField(manager, "e"); XposedHelpers.findField(manager, "f");
            for (String name : new String[]{"F", "u", "z"}) XposedHelpers.findMethodExact(wrapper, name);
            hooks.add(XposedBridge.hookMethod(show, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) refreshManager(param.thisObject);
                }
            }));
            hooks.add(XposedBridge.hookMethod(start, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) refreshManager(XposedHelpers.getObjectField(param.thisObject, "b"));
                }
            }));
            hooks.add(XposedBridge.hookMethod(opened, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) refresh(param.thisObject);
                }
            }));
            hooks.add(XposedBridge.hookMethod(prepare, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    // Q resizes the window and clears the native collapsed NOT_TOUCHABLE flag before U(true).
                    XposedHelpers.setAdditionalInstanceField(param.thisObject, OPENING, true);
                    refresh(param.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    XposedHelpers.removeAdditionalInstanceField(param.thisObject, OPENING);
                    refresh(param.thisObject);
                }
            }));
            hooks.add(XposedBridge.hookMethod(layout, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    View view = (View) param.args[0];
                    Object owner = owner(view);
                    if (owner == null) { refreshManager(param.thisObject); owner = owner(view); }
                    if (owner != null) mask(view, (WindowManager.LayoutParams) param.args[1], hidden(owner));
                }
            }));
            HookLog.info("sidebar-trigger INSTALLED (collapsed windows transparent + non-touchable)");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("sidebar-trigger install", error);
        }
    }

    private static Object owner(View view) {
        WeakReference<?> reference = (WeakReference<?>) XposedHelpers.getAdditionalInstanceField(view, OWNER);
        return reference == null ? null : reference.get();
    }

    private static boolean hidden(Object wrapper) {
        return !Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(wrapper, OPENING))
            && !SidebarSwipeHook.isClosing(wrapper)
            && !(boolean) XposedHelpers.callMethod(wrapper, "F");
    }

    static void refreshTracked(Object wrapper) {
        View view = (View) XposedHelpers.callMethod(wrapper, "z");
        if (owner(view) == wrapper) refresh(wrapper);
    }

    private static void refreshManager(Object manager) {
        if (manager == null) return;
        try {
            for (String field : new String[]{"e", "f"}) {
                Object wrapper = XposedHelpers.getObjectField(manager, field);
                if (wrapper != null) refresh(wrapper);
            }
        } catch (Throwable error) { HookLog.error("sidebar-trigger manager", error); }
    }

    private static void refresh(Object wrapper) {
        try {
            boolean hidden = hidden(wrapper);
            for (String method : new String[]{"u", "z"}) {
                View view = (View) XposedHelpers.callMethod(wrapper, method);
                XposedHelpers.setAdditionalInstanceField(view, OWNER, new WeakReference<>(wrapper));
                if (view.getParent() != null && view.getLayoutParams() instanceof WindowManager.LayoutParams params
                    && mask(view, params, hidden)) view.getContext().getSystemService(WindowManager.class).updateViewLayout(view, params);
            }
        } catch (Throwable error) { HookLog.error("sidebar-trigger window", error); }
    }

    private static boolean mask(View view, WindowManager.LayoutParams params, boolean hidden) {
        int flags = params.flags; float alpha = params.alpha;
        WindowState saved = (WindowState) XposedHelpers.getAdditionalInstanceField(view, SAVED);
        if (hidden) {
            if (saved == null) XposedHelpers.setAdditionalInstanceField(view, SAVED, new WindowState(flags, alpha));
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            // Window alpha, rather than only child-view alpha, also permits unobstructed touch pass-through.
            params.alpha = 0;
        } else if (saved != null) {
            params.flags = (flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) | saved.touchFlag;
            params.alpha = saved.alpha;
            XposedHelpers.removeAdditionalInstanceField(view, SAVED);
        }
        return flags != params.flags || alpha != params.alpha;
    }

    private static final class WindowState {
        final int touchFlag;
        final float alpha;
        WindowState(int flags, float alpha) { touchFlag = flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; this.alpha = alpha; }
    }
}
