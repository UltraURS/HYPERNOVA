package io.github.hypernova.feature.notification;

import android.graphics.Insets;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.util.ArrayList;
import java.util.List;

/** Repositions the native clear control, retaining its shade visibility, click and animation logic. */
public final class NotificationClearAllHook {
    private NotificationClearAllHook() { }

    public static void install(ClassLoader host) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        ThreadLocal<Boolean> checkingVisibility = new ThreadLocal<>();
        try {
            Class<?> panel = XposedHelpers.findClass("com.android.systemui.shade.MiuiNotificationPanelViewController", host);
            Class<?> controller = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController", host);
            Class<?> dismiss = XposedHelpers.findClass("com.android.systemui.shade.NotificationDismissViewController", host);
            XposedHelpers.findField(dismiss, "dismissView");
            XposedHelpers.findField(panel, "dismissViewController");
            var visibility = XposedHelpers.findMethodExact(panel, "getShowDismissView");
            var notifications = XposedHelpers.findMethodExact(controller, "hasNotifications", int.class, boolean.class);
            var init = XposedHelpers.findMethodExact(dismiss, "onInit");
            var orientation = XposedHelpers.findMethodExact(dismiss, "updateOrientation");
            hooks.add(XposedBridge.hookMethod(visibility, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra("hypernova_checking_clear", checkingVisibility.get());
                    checkingVisibility.set(true);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getObjectExtra("hypernova_checking_clear") == null) checkingVisibility.remove();
                    else checkingVisibility.set((Boolean) param.getObjectExtra("hypernova_checking_clear"));
                    if (!param.hasThrowable()) configure(XposedHelpers.getObjectField(param.thisObject, "dismissViewController"));
                }
            }));
            hooks.add(XposedBridge.hookMethod(notifications, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    // Show the control even when the only remaining notification is locked/ongoing.
                    // All other native visibility guards (keyguard, heads-up, QS, switching) remain in force.
                    if (!param.hasThrowable() && Boolean.FALSE.equals(param.getResult())
                        && Boolean.TRUE.equals(checkingVisibility.get()) && (int) param.args[0] == 0 && (boolean) param.args[1])
                        param.setResult(XposedHelpers.callMethod(param.thisObject, "hasNotifications", 0, false));
                }
            }));
            hooks.add(XposedBridge.hookMethod(init, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) configure(param.thisObject);
                }
            }));
            hooks.add(XposedBridge.hookMethod(orientation, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) position((View) XposedHelpers.getObjectField(param.thisObject, "dismissView"));
                }
            }));
            HookLog.info("notification-clear-all INSTALLED");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("notification-clear-all install", error);
        }
    }

    private static void configure(Object controller) {
        if (controller == null) return;
        try {
            View button = (View) XposedHelpers.getObjectField(controller, "dismissView");
            if (XposedHelpers.getAdditionalInstanceField(button, "hypernova_clear_configured") == null) {
                button.setTooltipText("一键清理"); button.setContentDescription("一键清理，保留锁定通知");
                View parent = (View) button.getParent();
                parent.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> position(button));
                XposedHelpers.setAdditionalInstanceField(button, "hypernova_clear_configured", true);
                HookLog.info("notification-clear-all OBSERVED native button");
            }
            position(button);
        } catch (Throwable error) { HookLog.error("notification-clear-all view", error); }
    }

    private static void position(View button) {
        if (!(button.getLayoutParams() instanceof FrameLayout.LayoutParams params) || !(button.getParent() instanceof View parent)) return;
        WindowInsets window = button.getRootWindowInsets();
        Insets safe = window == null ? Insets.NONE : window.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
        int height = parent.getHeight() > 0 ? parent.getHeight() : button.getResources().getDisplayMetrics().heightPixels;
        int bottom = safe.bottom + Math.max(Math.round(24 * button.getResources().getDisplayMetrics().density), Math.round(height * .025f));
        int gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        if (params.gravity == gravity && params.bottomMargin == bottom && params.getMarginStart() == 0 && params.getMarginEnd() == 0) return;
        params.gravity = gravity; params.bottomMargin = bottom; params.setMarginStart(0); params.setMarginEnd(0);
        button.setLayoutParams(params);
    }
}
