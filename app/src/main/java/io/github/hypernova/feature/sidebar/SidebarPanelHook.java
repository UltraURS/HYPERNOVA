package io.github.hypernova.feature.sidebar;

import android.app.Service;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.app.KeyguardManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Uses the existing signature-protected service; no new exported component or permission bypass. */
public final class SidebarPanelHook {
    public static final String PACKAGE = "com.miui.securitycenter";
    public static final String SERVICE = "com.miui.gamebooster.service.DockWindowManagerService";
    public static final String ACTION = "io.github.hypernova.OPEN_SIDEBAR";
    public static final String SIDE = "hypernova_side", REPLY = "hypernova_reply";
    public static final int ACCEPTED = 1, UNAVAILABLE = 0;
    private static final String VISIBILITY = "hypernova_sidebar_visibility";
    private static final String COVER_VISIBILITY = "hypernova_sidebar_cover_visibility";
    private static final String PENDING = "hypernova_sidebar_pending";

    private SidebarPanelHook() { }
    public static void install(ClassLoader loader) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Class<?> service = XposedHelpers.findClass(SERVICE, loader);
            Class<?> manager = XposedHelpers.findClass("A4.n", loader);
            Class<?> wrapper = XposedHelpers.findClass("com.miui.dock.sidebar.j", loader);
            Method start = XposedHelpers.findMethodExact(service, "onStartCommand", Intent.class, int.class, int.class);
            Method opened = XposedHelpers.findMethodExact(wrapper, "U", boolean.class);
            XposedHelpers.findField(service, "b"); XposedHelpers.findField(service, "c");
            for (String name : new String[]{"e", "f", "p"}) XposedHelpers.findField(manager, name);
            for (String name : new String[]{"L1", "e1"}) XposedHelpers.findMethodExact(manager, name);
            XposedHelpers.findMethodExact(manager, "A1", wrapper, Runnable.class);
            XposedHelpers.findMethodExact(manager, "Q1", wrapper);
            XposedHelpers.findMethodExact(manager, "X0", wrapper);
            for (String name : new String[]{"H", "F", "Q", "z", "u", "w", "A"}) XposedHelpers.findMethodExact(wrapper, name);
            hooks.add(XposedBridge.hookMethod(start, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[0];
                    if (intent == null || !ACTION.equals(intent.getAction())) return;
                    param.setResult(Service.START_STICKY);
                    ResultReceiver reply = null;
                    boolean accepted = false;
                    try {
                        reply = intent.getParcelableExtra(REPLY, ResultReceiver.class);
                        int side = intent.getIntExtra(SIDE, -1);
                        if (reply != null && (side == 0 || side == 1)) {
                            accepted = open((Service) param.thisObject, side == 0, reply);
                        }
                    } catch (Throwable error) { HookLog.error("sidebar-panel request", error); }
                    if (reply != null && !accepted) reply.send(UNAVAILABLE, null);
                }
            }));
            hooks.add(XposedBridge.hookMethod(opened, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(boolean) param.args[0] && !param.hasThrowable() && !SidebarSwipeHook.isClosing(param.thisObject))
                        restoreVisibility(param.thisObject);
                }
            }));
            HookLog.info("sidebar-panel INSTALLED");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("sidebar-panel install", error);
        }
    }

    private static boolean open(Service service, boolean left, ResultReceiver reply) {
        PowerManager power = service.getSystemService(PowerManager.class);
        KeyguardManager keyguard = service.getSystemService(KeyguardManager.class);
        if (!power.isInteractive() || keyguard.isKeyguardLocked()) return unavailable("screen-or-keyguard");
        if (!XposedHelpers.getBooleanField(service, "c")) return unavailable("native-mode-inactive");
        Object manager = XposedHelpers.getObjectField(service, "b");
        if (manager == null) return unavailable("manager-missing");
        if (XposedHelpers.getAdditionalInstanceField(manager, PENDING) != null) return unavailable("open-in-progress");
        // L1 retains the native dock-mode, display, unlock and window-creation checks.
        XposedHelpers.callMethod(manager, "L1");
        if (!XposedHelpers.getBooleanField(manager, "p")) return unavailable("native-windows-unavailable");
        Object main = XposedHelpers.getObjectField(manager, "e");
        Object other = XposedHelpers.getObjectField(manager, "f");
        if (main == null || other == null) return unavailable("wrapper-missing");
        Object target = ((boolean) XposedHelpers.callMethod(main, "H")) == left ? main : other;
        if (((boolean) XposedHelpers.callMethod(target, "H")) != left) return unavailable("edge-mismatch");
        if (!(boolean) XposedHelpers.callMethod(target, "F")) XposedHelpers.callMethod(manager, "e1");
        OpenRequest request = new OpenRequest(service, manager, target, left, reply);
        XposedHelpers.setAdditionalInstanceField(manager, PENDING, request);
        request.start();
        return true; // The request replies after layout and animation, never at attachment alone.
    }

    private static final class OpenRequest implements ViewTreeObserver.OnPreDrawListener {
        final Service service;
        final Object manager, target;
        final View panel, handle, coverWindow;
        final ViewGroup content;
        final boolean left;
        final ResultReceiver reply;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable timeout = () -> finish(false, "layout-or-animation-timeout");
        boolean launched, animated, finished;
        View observed;

        OpenRequest(Service service, Object manager, Object target, boolean left, ResultReceiver reply) {
            this.service = service; this.manager = manager; this.target = target; this.left = left; this.reply = reply;
            panel = (View) XposedHelpers.callMethod(target, "z");
            handle = (View) XposedHelpers.callMethod(target, "w");
            coverWindow = (View) XposedHelpers.callMethod(target, "u");
            content = (ViewGroup) XposedHelpers.callMethod(target, "A");
        }

        void start() {
            try {
                handler.postDelayed(timeout, 2000);
                if (panel.getVisibility() != View.VISIBLE) {
                    XposedHelpers.setAdditionalInstanceField(target, VISIBILITY, panel.getVisibility());
                    panel.setVisibility(View.VISIBLE);
                }
                if (coverWindow.getVisibility() != View.VISIBLE) {
                    XposedHelpers.setAdditionalInstanceField(target, COVER_VISIBILITY, coverWindow.getVisibility());
                    coverWindow.setVisibility(View.VISIBLE);
                }
                if ((boolean) XposedHelpers.callMethod(target, "F")) {
                    launched = animated = true; listenForContent();
                } else {
                    // The native arrow's position is read from this panel window's child handle.
                    observed = panel;
                    observed.getViewTreeObserver().addOnPreDrawListener(this);
                    coverWindow.requestLayout(); coverWindow.invalidate();
                }
                panel.requestLayout(); panel.invalidate();
            } catch (Throwable error) { fail(error); }
        }

        @Override public boolean onPreDraw() {
            if (finished) return true;
            try {
                if (!valid()) { finish(false, "native-mode-or-window-changed"); return true; }
                if (!launched) {
                    if (!handle.isLaidOut() || handle.isLayoutRequested() || !onSide(handle)) return true;
                    removeListener(); launched = true;
                    int[] position = new int[2]; handle.getLocationOnScreen(position);
                    HookLog.info("sidebar-panel LAYOUT_READY side=" + side() + " handleX=" + position[0]
                        + " width=" + handle.getWidth());
                    if ((boolean) XposedHelpers.callMethod(target, "F")) { animated = true; listenForContent(); }
                    else {
                        // Q captures getLocationOnScreen() for the native morph animation.
                        XposedHelpers.callMethod(target, "Q");
                        // Native touch-up clears the MIUI click pass-down bit after Q creates the panel.
                        XposedHelpers.callMethod(manager, "X0", target);
                        XposedHelpers.callMethod(manager, "A1", target, (Runnable) () -> handler.post(() -> {
                            if (finished) return;
                            animated = true;
                            HookLog.info("sidebar-panel ANIMATION_DONE side=" + side());
                            listenForContent();
                        }));
                        XposedHelpers.callMethod(manager, "Q1", target);
                    }
                } else if (animated && content.getChildCount() > 0 && content.getAlpha() > 0.5f
                    && content.isShown() && onSide(content) && touchable()) {
                    XposedHelpers.callMethod(manager, "X0", target);
                    finish(true, "visible");
                }
            } catch (Throwable error) { fail(error); }
            return true;
        }

        boolean valid() {
            return panel.isAttachedToWindow()
                && service.getSystemService(PowerManager.class).isInteractive()
                && !service.getSystemService(KeyguardManager.class).isKeyguardLocked()
                && XposedHelpers.getBooleanField(service, "c");
        }

        boolean touchable() {
            return panel.getLayoutParams() instanceof WindowManager.LayoutParams params
                && params.alpha > 0.5f && (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
        }

        boolean onSide(View view) {
            Rect visible = new Rect();
            int[] position = new int[2]; view.getLocationOnScreen(position);
            int screenWidth = view.getResources().getDisplayMetrics().widthPixels;
            return view.getWidth() > 0 && view.getHeight() > 0 && view.getGlobalVisibleRect(visible)
                && !visible.isEmpty() && (position[0] + view.getWidth() / 2f < screenWidth / 2f) == left;
        }

        void listenForContent() {
            if (finished) return;
            removeListener(); observed = panel;
            panel.getViewTreeObserver().addOnPreDrawListener(this); panel.invalidate();
        }

        void removeListener() {
            if (observed == null) return;
            ViewTreeObserver observer = observed.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        }

        String side() { return left ? "left" : "right"; }

        void fail(Throwable error) {
            HookLog.error("sidebar-panel open", error); finish(false, "open-error");
        }

        void finish(boolean shown, String reason) {
            if (finished) return;
            finished = true; handler.removeCallbacks(timeout); removeListener();
            XposedHelpers.removeAdditionalInstanceField(manager, PENDING);
            try {
                if (!shown) {
                    XposedHelpers.callMethod(manager, "e1");
                    restoreVisibility(target);
                }
            } catch (Throwable error) { HookLog.error("sidebar-panel cleanup", error); }
            HookLog.info("sidebar-panel " + (shown ? "ACCEPTED" : "UNAVAILABLE")
                + " side=" + side() + " reason=" + reason);
            reply.send(shown ? ACCEPTED : UNAVAILABLE, null);
        }
    }

    static void restoreVisibility(Object wrapper) {
        Integer visibility = (Integer) XposedHelpers.removeAdditionalInstanceField(wrapper, VISIBILITY);
        if (visibility != null) ((View) XposedHelpers.callMethod(wrapper, "z")).setVisibility(visibility);
        Integer cover = (Integer) XposedHelpers.removeAdditionalInstanceField(wrapper, COVER_VISIBILITY);
        if (cover != null) ((View) XposedHelpers.callMethod(wrapper, "u")).setVisibility(cover);
    }

    private static boolean unavailable(String reason) {
        HookLog.info("sidebar-panel UNAVAILABLE reason=" + reason);
        return false;
    }
}
