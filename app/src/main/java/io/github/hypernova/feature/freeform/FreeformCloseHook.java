// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.freeform;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A task-child surface; no application overlay permission or change to the original caption window. */
public final class FreeformCloseHook {
    private static final String PREFIX = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.";
    private static final String STATE = "hypernova_freeform_close";
    private FreeformCloseHook() { }

    public static void install(ClassLoader loader) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Api api = new Api(loader);
            var relayout = XposedHelpers.findMethodExact(api.top, "relayout", SurfaceControl.Transaction.class,
                SurfaceControl.Transaction.class, XposedHelpers.findClass("android.window.WindowContainerTransaction", loader), boolean.class);
            var visibility = XposedHelpers.findMethodExact(api.top, "updateVisibility", boolean.class, SurfaceControl.Transaction.class);
            var release = XposedHelpers.findMethodExact(api.top, "releaseViews");
            var resizing = XposedHelpers.findMethodExact(api.base, "onMiuiFreeformResizeStart", boolean.class);
            var scale = XposedHelpers.findMethodExact(api.base, "onMiuiFreeformScaleChanged", float.class);
            hooks.add(XposedBridge.hookMethod(relayout, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    update(api, param.thisObject, (SurfaceControl.Transaction) param.args[0]);
                }
            }));
            hooks.add(XposedBridge.hookMethod(visibility, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    State state = state(param.thisObject);
                    if (!param.hasThrowable() && state != null) {
                        try { state.visible((boolean) param.args[0] && eligible(param.thisObject), (SurfaceControl.Transaction) param.args[1]); }
                        catch (Throwable error) { failed(param.thisObject, error); }
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(release, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) { discard(param.thisObject); }
            }));
            hooks.add(XposedBridge.hookMethod(resizing, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!api.top.isInstance(param.thisObject)) return;
                    State state = state(param.thisObject);
                    if (state != null) try (var transaction = new SurfaceControl.Transaction()) {
                        state.resizing = true; state.visible(false, transaction); transaction.apply();
                    } catch (Throwable error) { failed(param.thisObject, error); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(scale, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!api.top.isInstance(param.thisObject) || param.hasThrowable()) return;
                    State state = state(param.thisObject);
                    if (state != null) { state.resizing = false; state.scale = (float) param.args[0]; }
                    ownUpdate(api, param.thisObject);
                }
            }));
            HookLog.info("freeform-close INSTALLED");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("freeform-close install", error);
        }
    }

    private static State state(Object owner) { return (State) XposedHelpers.getAdditionalInstanceField(owner, STATE); }
    private static boolean eligible(Object owner) {
        Object task = XposedHelpers.getObjectField(owner, "mRunningTaskInfo");
        Object result = XposedHelpers.callMethod(owner, "getResult");
        boolean visible = XposedHelpers.getIntField(owner, "mWindowingMode") == 5
            && XposedHelpers.getIntField(task, "displayId") == 0 && XposedHelpers.getBooleanField(task, "isVisible")
            && XposedHelpers.getBooleanField(owner, "mCaptionVisible")
            && XposedHelpers.getObjectField(result, "mRootView") != null;
        if (!visible) return false;
        Object freeform = XposedHelpers.callMethod(XposedHelpers.getObjectField(owner, "mMultiTaskingTaskRepository"),
            "getMiuiFreeformTaskInfo", XposedHelpers.getIntField(task, "taskId"));
        return freeform != null && (boolean) XposedHelpers.callMethod(freeform, "isNormalState");
    }
    private static void ownUpdate(Api api, Object owner) {
        if (state(owner) == null) return;
        try (var transaction = new SurfaceControl.Transaction()) { update(api, owner, transaction); transaction.apply(); }
    }
    private static void update(Api api, Object owner, SurfaceControl.Transaction transaction) {
        try {
            if (!eligible(owner)) { discard(owner); return; }
            SurfaceControl parent = (SurfaceControl) XposedHelpers.getObjectField(XposedHelpers.getObjectField(owner, "mDecoration"), "mTaskSurface");
            if (parent == null || !parent.isValid()) { discard(owner); return; }
            State state = state(owner);
            if (state != null && state.parent != parent) { discard(owner); state = null; }
            if (state == null) {
                state = new State(api, owner, parent);
                XposedHelpers.setAdditionalInstanceField(owner, STATE, state);
            }
            state.update(transaction);
        } catch (Throwable error) { failed(owner, error); }
    }
    private static void discard(Object owner) {
        State state = (State) XposedHelpers.removeAdditionalInstanceField(owner, STATE);
        if (state != null) state.release();
    }
    private static void failed(Object owner, Throwable error) { discard(owner); HookLog.error("freeform-close window", error); }

    private static final class Api {
        final Class<?> top, base;
        final Constructor<?> windowManager;
        final Method factory, setView, relayout, trustedWindow, container, trustedSurface, remove, nativeClose;
        Api(ClassLoader loader) {
            top = XposedHelpers.findClass(PREFIX + "MiuiTopDecoration", loader);
            base = XposedHelpers.findClass(PREFIX + "MiuiBaseWindowDecoration", loader);
            Class<?> decoration = XposedHelpers.findClass(PREFIX + "MiuiWindowDecoration", loader);
            Class<?> click = XposedHelpers.findClass(PREFIX + "MiuiCaptionButtonClickListener", loader);
            Class<?> model = XposedHelpers.findClass(PREFIX + "MulWinSwitchDecorViewModel", loader);
            Class<?> organizer = XposedHelpers.findClass("com.android.wm.shell.ShellTaskOrganizer", loader);
            Class<?> repository = XposedHelpers.findClass("com.android.wm.shell.multitasking.taskmanager.MultiTaskingTaskRepository", loader);
            Class<?> freeform = XposedHelpers.findClass("com.android.wm.shell.multitasking.taskmanager.MiuiFreeformModeTaskInfo", loader);
            Class<?> manager = XposedHelpers.findClass("android.view.WindowlessWindowManager", loader);
            Class<?> token = XposedHelpers.findClass("android.window.InputTransferToken", loader);
            windowManager = XposedHelpers.findConstructorExact(manager, Configuration.class, SurfaceControl.class, token);
            factory = XposedHelpers.findMethodExact(XposedHelpers.findClass(PREFIX + "MiuiBaseWindowDecoration$SurfaceControlViewHostFactory", loader),
                "create", Context.class, Display.class, manager);
            setView = XposedHelpers.findMethodExact(SurfaceControlViewHost.class, "setView", View.class, WindowManager.LayoutParams.class);
            relayout = XposedHelpers.findMethodExact(SurfaceControlViewHost.class, "relayout", WindowManager.LayoutParams.class);
            trustedWindow = XposedHelpers.findMethodExact(WindowManager.LayoutParams.class, "setTrustedOverlay");
            container = XposedHelpers.findMethodExact(SurfaceControl.Builder.class, "setContainerLayer");
            trustedSurface = XposedHelpers.findMethodExact(SurfaceControl.Transaction.class, "setTrustedOverlay", SurfaceControl.class, boolean.class);
            remove = XposedHelpers.findMethodExact(SurfaceControl.Transaction.class, "remove", SurfaceControl.class);
            nativeClose = XposedHelpers.findMethodExact(click, "handleCloseClicked", decoration);
            XposedHelpers.findMethodExact(model, "isInLockTaskMode");
            XposedHelpers.findMethodExact(organizer, "getRunningTaskInfo", int.class);
            XposedHelpers.findMethodExact(repository, "getMiuiFreeformTaskInfo", int.class);
            XposedHelpers.findMethodExact(freeform, "isNormalState"); XposedHelpers.findField(freeform, "mDestinationScaleX");
            XposedHelpers.findMethodExact(top, "getResult"); XposedHelpers.findMethodExact(top, "closeHandleMenu", boolean.class);
            for (String name : new String[]{"mContext", "mDecoration", "mRunningTaskInfo", "mWindowingMode", "mTaskBounds", "mCaptionVisible",
                "mSurfaceControlBuilderSupplier", "mSurfaceControlViewHostFactory", "mWindowDecorViewModel", "mTaskOrganizer", "mMultiTaskingTaskRepository"})
                XposedHelpers.findField(base, name);
            XposedHelpers.findField(top, "mOnCaptionButtonClickListener");
            for (String name : new String[]{"mDisplay", "mTaskSurface"}) XposedHelpers.findField(decoration, name);
            XposedHelpers.findField(XposedHelpers.findClass(PREFIX + "MiuiBaseWindowDecoration$RelayoutResult", loader), "mRootView");
            XposedHelpers.findField(XposedHelpers.findClass(PREFIX + "MiuiBaseWindowDecoration$RelayoutResult", loader), "mCaptionY");
            XposedHelpers.findMethodExact(XposedHelpers.findClass(PREFIX + "MiuiDotView", loader), "getDotY");
            XposedHelpers.findField(XposedHelpers.findClass(PREFIX + "MiuiDecorationRootView", loader), "mPaint");
            for (String name : new String[]{"taskId", "displayId", "isVisible", "configuration"})
                XposedHelpers.findField(XposedHelpers.findClass("android.app.TaskInfo", loader), name);
            XposedHelpers.findMethodExact(XposedHelpers.findClass("android.app.TaskInfo", loader), "getWindowingMode");
        }
    }

    private static final class State {
        final Api api;
        final WeakReference<Object> owner;
        final SurfaceControl parent;
        SurfaceControl surface;
        SurfaceControlViewHost host;
        CloseButton button;
        WindowManager.LayoutParams params;
        boolean resizing, requested, shown, placed;
        float scale = 1;
        State(Api api, Object owner, SurfaceControl parent) { this.api = api; this.owner = new WeakReference<>(owner); this.parent = parent; }

        @SuppressWarnings("unchecked") void update(SurfaceControl.Transaction transaction) throws Exception {
            Object decoration = owner.get(); if (decoration == null) return;
            Context context = (Context) XposedHelpers.getObjectField(decoration, "mContext");
            Rect bounds = (Rect) XposedHelpers.getObjectField(decoration, "mTaskBounds");
            Object task = XposedHelpers.getObjectField(decoration, "mRunningTaskInfo");
            Object freeform = XposedHelpers.callMethod(XposedHelpers.getObjectField(decoration, "mMultiTaskingTaskRepository"),
                "getMiuiFreeformTaskInfo", XposedHelpers.getIntField(task, "taskId"));
            placed = false;
            if (freeform == null || !(boolean) XposedHelpers.callMethod(freeform, "isNormalState")) { visible(false, transaction); return; }
            if (!resizing) scale = XposedHelpers.getFloatField(freeform, "mDestinationScaleX");
            Object result = XposedHelpers.callMethod(decoration, "getResult");
            Object root = XposedHelpers.getObjectField(result, "mRootView");
            float dotCenterY = XposedHelpers.getIntField(result, "mCaptionY") + (float) XposedHelpers.callMethod(root, "getDotY");
            var placement = FreeformClosePlacement.at(bounds.width(), bounds.height(), context.getResources().getDisplayMetrics().density,
                scale, dotCenterY);
            if (placement == null) { visible(false, transaction); return; }
            placed = true;
            if (surface == null) {
                SurfaceControl.Builder builder = ((Supplier<SurfaceControl.Builder>) XposedHelpers.getObjectField(decoration, "mSurfaceControlBuilderSupplier")).get();
                builder.setName("HYPERNOVA freeform close").setParent(parent).setHidden(true); api.container.invoke(builder);
                surface = builder.build();
                Object manager = api.windowManager.newInstance(XposedHelpers.getObjectField(task, "configuration"), surface, null);
                Object window = XposedHelpers.getObjectField(decoration, "mDecoration");
                host = (SurfaceControlViewHost) api.factory.invoke(XposedHelpers.getObjectField(decoration, "mSurfaceControlViewHostFactory"),
                    context, XposedHelpers.getObjectField(window, "mDisplay"), manager);
                button = new CloseButton(context); button.setOnClickListener(view -> click());
                params = new WindowManager.LayoutParams(placement.size(), placement.size(), 2,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
                params.setTitle("HYPERNOVA freeform close"); api.trustedWindow.invoke(params); api.setView.invoke(host, button, params);
                api.trustedSurface.invoke(transaction, surface, true);
            }
            if (params.width != placement.size() || params.height != placement.size()) {
                params.width = params.height = placement.size(); api.relayout.invoke(host, params);
            }
            transaction.setPosition(surface, placement.x(), placement.y()).setCrop(surface, new Rect(0, 0, placement.size(), placement.size())).setLayer(surface, 20001);
            button.ink = ((Paint) XposedHelpers.getObjectField(root, "mPaint")).getColor() | 0xff000000; button.invalidate();
            visible(!resizing && eligible(decoration), transaction);
        }
        void visible(boolean visible, SurfaceControl.Transaction transaction) throws Exception {
            if (surface == null || !surface.isValid()) return;
            visible = visible && placed && !resizing;
            transaction.setVisibility(surface, visible);
            if (shown != visible) {
                shown = visible;
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | (visible ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                button.setVisibility(visible ? View.VISIBLE : View.GONE); api.relayout.invoke(host, params);
            }
        }
        void click() {
            Object decoration = owner.get();
            if (requested || decoration == null) return;
            try {
                if (!eligible(decoration)) return;
                Object model = XposedHelpers.getObjectField(decoration, "mWindowDecorViewModel");
                if ((boolean) XposedHelpers.callMethod(model, "isInLockTaskMode")) return;
                Object task = XposedHelpers.getObjectField(decoration, "mRunningTaskInfo");
                Object current = XposedHelpers.callMethod(XposedHelpers.getObjectField(decoration, "mTaskOrganizer"), "getRunningTaskInfo", XposedHelpers.getIntField(task, "taskId"));
                if (current == null || (int) XposedHelpers.callMethod(current, "getWindowingMode") != 5) return;
                requested = true; button.setEnabled(false);
                api.nativeClose.invoke(XposedHelpers.getObjectField(decoration, "mOnCaptionButtonClickListener"), XposedHelpers.getObjectField(decoration, "mDecoration"));
                XposedHelpers.callMethod(decoration, "closeHandleMenu", true);
                HookLog.info("freeform-close CLOSE_REQUESTED");
            } catch (Throwable error) { requested = false; button.setEnabled(true); HookLog.error("freeform-close click", error); }
        }
        void release() {
            try {
                if (host != null) { host.release(); host = null; }
            } finally {
                if (surface != null) {
                    try (var transaction = new SurfaceControl.Transaction()) {
                        api.remove.invoke(transaction, surface); transaction.apply();
                    } catch (Throwable error) { HookLog.error("freeform-close release", error); }
                    finally { surface.release(); surface = null; }
                }
            }
        }
    }

    private static final class CloseButton extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int ink = Color.DKGRAY;
        CloseButton(Context context) {
            super(context); setContentDescription("关闭小窗"); setFocusable(true);
            GradientDrawable mask = new GradientDrawable(); mask.setShape(GradientDrawable.OVAL); mask.setColor(Color.WHITE);
            setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), null, mask));
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStyle(Paint.Style.STROKE);
        }
        @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float center = getWidth() / 2f, stroke = getWidth() / FreeformClosePlacement.ICON_SIZE_DP;
            float radius = (getWidth() - stroke) / 2f;
            paint.setColor(ink); paint.setStrokeWidth(stroke);
            canvas.drawLine(center - radius, center - radius, center + radius, center + radius, paint);
            canvas.drawLine(center - radius, center + radius, center + radius, center - radius, paint);
        }
    }
}
