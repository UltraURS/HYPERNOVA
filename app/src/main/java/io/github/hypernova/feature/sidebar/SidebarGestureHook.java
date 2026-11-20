// SPDX-License-Identifier: GPL-3.0-only
// Gesture scheduling and temporary icon replacement adapted from Connect Colours.
package io.github.hypernova.feature.sidebar;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class SidebarGestureHook {
    private static final String STATE = "hypernova_sidebar_state", ICON = "hypernova_sidebar_icon";
    private static final String ICON_SOURCE = "hypernova_sidebar_icon_source";
    private static final String ICON_WIDTH = "hypernova_sidebar_icon_width", ICON_HEIGHT = "hypernova_sidebar_icon_height";
    private static final String EVENT = "hypernova_sidebar_event", CANCEL = "hypernova_sidebar_cancel";
    private static final String FEEDBACK = "hypernova_sidebar_feedback";
    private SidebarGestureHook() { }

    public static void install(ClassLoader loader, int holdMillis) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Class<?> stub = XposedHelpers.findClass("com.miui.home.recents.GestureStubView", loader);
            Class<?> processor = XposedHelpers.findClass("com.miui.home.recents.GesturesBackTouchProcessor", loader);
            Class<?> arrow = XposedHelpers.findClass("com.miui.home.recents.GestureBackArrowView", loader);
            Class<?> feedback = XposedHelpers.findClass("com.miui.home.recents.GestureBackArrowView$1", loader);
            Class<?> haptics = XposedHelpers.findClass("com.miui.home.common.hapticfeedback.HapticFeedbackCompat", loader);
            Object haptic = XposedHelpers.findMethodExact(haptics, "getInstance").invoke(null);
            Method readyHaptic = XposedHelpers.findMethodExact(haptics, "performGestureReadyBack");
            Method pointer = XposedHelpers.findMethodExact(processor, "onPointerEvent", MotionEvent.class, stub);
            Method showArrow = XposedHelpers.findMethodExact(processor, "isShowBackArrow", float.class);
            Method nativeFeedback = XposedHelpers.findMethodExact(feedback, "onAnimationSuccess", android.animation.Animator.class);
            Method draw = XposedHelpers.findMethodExact(arrow, "onDraw", Canvas.class);
            Method configuration = XposedHelpers.findMethodExact(arrow, "onConfigurationChanged", android.content.res.Configuration.class);
            Method detach = XposedHelpers.findMethodExact(stub, "onDetachedFromWindow");
            Method touchable = XposedHelpers.findMethodExact(stub, "setBackTouchable", boolean.class);
            for (String name : new String[]{"mDownX", "mSwipeStatus"}) XposedHelpers.findField(processor, name);
            for (String name : new String[]{"mGestureStubPos", "mGestureBackArrowView", "mHandler", "mTouchable", "mShouldRedirectMotionEvent"}) {
                XposedHelpers.findField(stub, name);
            }
            XposedHelpers.findField(arrow, "mArrow");
            XposedHelpers.findField(arrow, "mArrowFeedBackDone");
            XposedHelpers.findField(arrow, "mArrowWidth"); XposedHelpers.findField(arrow, "mArrowHeight");
            XposedHelpers.findField(feedback, "this$0");
            hooks.add(XposedBridge.hookMethod(pointer, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    View view = (View) param.args[1];
                    MotionEvent event = (MotionEvent) param.args[0];
                    Session session = (Session) XposedHelpers.getAdditionalInstanceField(view, STATE);
                    try {
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            if (session == null) {
                                int side = XposedHelpers.getIntField(view, "mGestureStubPos");
                                if (side != 0 && side != 1) return;
                                session = new Session(view, param.thisObject, showArrow, haptic, readyHaptic, holdMillis, side == 0);
                                XposedHelpers.setAdditionalInstanceField(view, STATE, session);
                            }
                            session.clear();
                        }
                        if (session == null) return;
                        param.setObjectExtra(EVENT, event);
                        if (session.before(event)) {
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            param.args[0] = cancel;
                            param.setObjectExtra(CANCEL, cancel);
                        }
                    } catch (Throwable error) {
                        if (session != null) session.clear();
                        HookLog.error("sidebar-gesture before", error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    MotionEvent original = (MotionEvent) param.getObjectExtra(EVENT);
                    if (original == null) return;
                    Session session = (Session) XposedHelpers.getAdditionalInstanceField(param.args[1], STATE);
                    try {
                        if (param.hasThrowable()) session.clear();
                        else session.after(original);
                    } catch (Throwable error) { session.clear(); HookLog.error("sidebar-gesture after", error); }
                    finally {
                        param.args[0] = original;
                        MotionEvent cancel = (MotionEvent) param.getObjectExtra(CANCEL);
                        if (cancel != null) cancel.recycle();
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(nativeFeedback, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object view = XposedHelpers.getObjectField(param.thisObject, "this$0");
                    param.setObjectExtra(FEEDBACK, XposedHelpers.getBooleanField(view, "mArrowFeedBackDone"));
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !Boolean.FALSE.equals(param.getObjectExtra(FEEDBACK))) return;
                    Object view = XposedHelpers.getObjectField(param.thisObject, "this$0");
                    if (!XposedHelpers.getBooleanField(view, "mArrowFeedBackDone")) return;
                    Session session = (Session) XposedHelpers.getAdditionalInstanceField(view, STATE);
                    if (session == null) return;
                    try { session.feedback(SystemClock.uptimeMillis()); }
                    catch (Throwable error) { session.cancel(); HookLog.error("sidebar-gesture feedback", error); }
                }
            }));
            hooks.add(XposedBridge.hookMethod(draw, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Session session = (Session) XposedHelpers.getAdditionalInstanceField(param.thisObject, STATE);
                    if (session == null || session.gesture.state() != SidebarGesture.State.READY) return;
                    try {
                        Bitmap original = (Bitmap) XposedHelpers.getObjectField(param.thisObject, "mArrow");
                        Bitmap icon = (Bitmap) XposedHelpers.getAdditionalInstanceField(param.thisObject, ICON);
                        if (icon == null || XposedHelpers.getAdditionalInstanceField(param.thisObject, ICON_SOURCE) != original) {
                            icon = icon(original, session.left);
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, ICON, icon);
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, ICON_SOURCE, original);
                        }
                        param.setObjectExtra(ICON, original);
                        param.setObjectExtra(ICON_WIDTH, XposedHelpers.getIntField(param.thisObject, "mArrowWidth"));
                        param.setObjectExtra(ICON_HEIGHT, XposedHelpers.getIntField(param.thisObject, "mArrowHeight"));
                        XposedHelpers.setObjectField(param.thisObject, "mArrow", icon);
                        XposedHelpers.setIntField(param.thisObject, "mArrowWidth", icon.getWidth());
                        XposedHelpers.setIntField(param.thisObject, "mArrowHeight", icon.getHeight());
                    } catch (Throwable error) { session.cancel(); HookLog.error("sidebar-gesture icon", error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object original = param.getObjectExtra(ICON);
                    if (original != null) {
                        XposedHelpers.setObjectField(param.thisObject, "mArrow", original);
                        XposedHelpers.setIntField(param.thisObject, "mArrowWidth", (int) param.getObjectExtra(ICON_WIDTH));
                        XposedHelpers.setIntField(param.thisObject, "mArrowHeight", (int) param.getObjectExtra(ICON_HEIGHT));
                    }
                }
            }));
            XC_MethodHook cancel = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 1 && param.args[0] instanceof Boolean && (boolean) param.args[0]) return;
                    Session session = (Session) XposedHelpers.getAdditionalInstanceField(param.thisObject, STATE);
                    if (session != null) session.cancel();
                }
            };
            hooks.add(XposedBridge.hookMethod(configuration, cancel));
            hooks.add(XposedBridge.hookMethod(detach, cancel));
            hooks.add(XposedBridge.hookMethod(touchable, cancel));
            HookLog.info("sidebar-gesture INSTALLED anchor=native-feedback holdMs=" + SidebarGesture.clampHoldMillis(holdMillis));
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("sidebar-gesture install", error);
        }
    }

    private static Bitmap icon(Bitmap original, boolean left) {
        int width = original.getWidth(), height = original.getHeight(), size = height;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(original.getDensity());
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = Color.WHITE, alpha = 0;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int pixel = original.getPixel(x, y);
            if (Color.alpha(pixel) > alpha) { color = pixel; alpha = Color.alpha(pixel); }
        }
        canvas.scale(size / 24f, size / 24f);
        if (!left) { canvas.translate(24, 0); canvas.scale(-1, 1); }
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawRoundRect(3, 3, 21, 21, 3, 3, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(4, 4, 9, 20, 2, 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(13, 9, 17, 9, paint); canvas.drawLine(13, 15, 17, 15, paint);
        return bitmap;
    }

    private static final class Session {
        final View stub, arrow;
        final Object processor, haptic;
        final Method showArrow, readyHaptic;
        final Handler handler;
        final SidebarGesture gesture;
        final boolean left;
        final int slop;
        final Runnable timer = this::onTimer;
        float inward, peak;
        int rotation;
        boolean open;

        Session(View stub, Object processor, Method showArrow, Object haptic, Method readyHaptic, int holdMillis, boolean left) {
            this.stub = stub; this.processor = processor; this.showArrow = showArrow; this.left = left;
            this.haptic = haptic; this.readyHaptic = readyHaptic;
            arrow = (View) XposedHelpers.getObjectField(stub, "mGestureBackArrowView");
            handler = (Handler) XposedHelpers.getObjectField(stub, "mHandler");
            gesture = new SidebarGesture(holdMillis);
            slop = ViewConfiguration.get(stub.getContext()).getScaledTouchSlop();
            XposedHelpers.setAdditionalInstanceField(arrow, STATE, this);
        }

        boolean before(MotionEvent event) throws Exception {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                rotation = stub.getDisplay().getRotation(); return false;
            }
            inward = (event.getRawX() - XposedHelpers.getFloatField(processor, "mDownX")) * (left ? 1 : -1);
            if (action == MotionEvent.ACTION_CANCEL) { cancel(); return false; }
            if (action == MotionEvent.ACTION_POINTER_DOWN && gesture.state() != SidebarGesture.State.IDLE) {
                cancel(); return true;
            }
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                if ((gesture.state() == SidebarGesture.State.HOLDING || gesture.state() == SidebarGesture.State.READY)
                    && peak - inward >= slop) {
                    gesture.reverse(); handler.removeCallbacks(timer); arrow.invalidate();
                    HookLog.info("sidebar-gesture REVERSED"); return true;
                }
                peak = Math.max(peak, inward);
            }
            if (action == MotionEvent.ACTION_UP) {
                if (validEnvironment() && validPosition()) arm(event.getEventTime());
                else cancel();
                boolean canceled = gesture.state() == SidebarGesture.State.CANCELED;
                open = gesture.release(event.getEventTime());
                handler.removeCallbacks(timer);
                return open || canceled;
            }
            return false;
        }

        void after(MotionEvent event) throws Exception {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) arm(event.getEventTime());
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                boolean launch = open;
                clear();
                if (launch) request();
            }
        }

        boolean validPosition() {
            return stub.isAttachedToWindow() && stub.getVisibility() == View.VISIBLE
                && stub.getDisplay() != null && stub.getDisplay().getRotation() == rotation
                && XposedHelpers.getBooleanField(stub, "mTouchable")
                && !XposedHelpers.getBooleanField(stub, "mShouldRedirectMotionEvent")
                && XposedHelpers.getIntField(processor, "mSwipeStatus") == 2;
        }

        boolean validEnvironment() {
            Context context = stub.getContext();
            return context.getSystemService(PowerManager.class).isInteractive()
                && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
        }

        void arm(long now) throws Exception {
            handler.removeCallbacks(timer);
            if (!validPosition()) { cancel(); return; }
            if (gesture.state() == SidebarGesture.State.IDLE) gesture.begin(left);
            if (gesture.advance(now, (boolean) showArrow.invoke(null, inward))) {
                try { readyHaptic.invoke(haptic); }
                catch (Throwable error) { HookLog.error("sidebar-gesture ready-haptic", error); }
                arrow.invalidate();
                HookLog.info("sidebar-gesture READY side=" + (left ? "left" : "right"));
            }
            long remaining = gesture.remainingMillis(now);
            if (remaining >= 0) handler.postAtTime(timer, now + remaining);
        }

        void feedback(long now) throws Exception {
            if (!validPosition() || !validEnvironment() || gesture.state() != SidebarGesture.State.TRACKING) return;
            if (!(boolean) showArrow.invoke(null, inward)) return;
            gesture.feedback(now); peak = inward;
            HookLog.info("sidebar-gesture FEEDBACK side=" + (left ? "left" : "right"));
            arm(now);
        }

        void onTimer() {
            try {
                if (!validEnvironment()) cancel();
                else arm(SystemClock.uptimeMillis());
            } catch (Throwable error) { cancel(); HookLog.error("sidebar-gesture timer", error); }
        }

        void cancel() {
            handler.removeCallbacks(timer); gesture.cancel(); open = false; arrow.invalidate();
        }

        void clear() {
            handler.removeCallbacks(timer); gesture.reset(); open = false; inward = peak = 0; arrow.invalidate();
        }

        void request() {
            Context context = stub.getContext();
            Runnable timeout = () -> Toast.makeText(context, "侧边栏未响应，请检查系统桌面与手机管家作用域并重启", Toast.LENGTH_LONG).show();
            ResultReceiver reply = new ResultReceiver(handler) {
                @Override protected void onReceiveResult(int result, android.os.Bundle data) {
                    handler.removeCallbacks(timeout);
                    if (result != SidebarPanelHook.ACCEPTED) Toast.makeText(context,
                        "侧边栏暂不可用，请先开启系统侧边栏并允许在当前应用显示", Toast.LENGTH_LONG).show();
                }
            };
            // Send the boot-class receiver, so the other app need not load this anonymous subclass.
            ResultReceiver remoteReply;
            Parcel parcel = Parcel.obtain();
            try {
                reply.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                remoteReply = ResultReceiver.CREATOR.createFromParcel(parcel);
            } finally { parcel.recycle(); }
            Intent intent = new Intent(SidebarPanelHook.ACTION)
                .setComponent(new ComponentName(SidebarPanelHook.PACKAGE, SidebarPanelHook.SERVICE))
                .putExtra(SidebarPanelHook.SIDE, left ? 0 : 1).putExtra(SidebarPanelHook.REPLY, remoteReply);
            handler.postDelayed(timeout, 3000);
            HookLog.info("sidebar-gesture REQUEST side=" + (left ? "left" : "right"));
            try {
                if (context.startService(intent) == null) { handler.removeCallbacks(timeout); timeout.run(); }
            } catch (RuntimeException error) {
                handler.removeCallbacks(timeout); HookLog.error("sidebar-gesture request", error);
                Toast.makeText(context, "无法呼出侧边栏，请检查系统侧边栏及模块作用域", Toast.LENGTH_LONG).show();
            }
        }
    }
}
