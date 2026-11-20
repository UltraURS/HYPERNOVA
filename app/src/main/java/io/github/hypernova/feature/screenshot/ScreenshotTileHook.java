package io.github.hypernova.feature.screenshot;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

public final class ScreenshotTileHook {
    private static final String PLUGIN_CALLBACK = "miui.systemui.controlcenter.panel.main.qs.QSRecord$callback$2$1";
    private static final long REGION_TIMEOUT_MS = 120_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final ClassLoader loader;
    private PopupWindow popup;
    private Object screenshotHelper;
    private boolean collapsePending;

    private ScreenshotTileHook(Context context, ClassLoader loader) {
        this.context = context;
        this.loader = loader;
    }

    public static void install(Context context, ClassLoader loader) {
        try {
            new ScreenshotTileHook(context, loader).hookLongPress();
            HookLog.info("screenshot-tile INSTALLED");
        } catch (Throwable error) {
            HookLog.error("screenshot-tile install", error);
        }
    }

    private void hookLongPress() {
        Class<?> tileClass = XposedHelpers.findClass("com.android.systemui.qs.tiles.ScreenShotTile", loader);
        Class<?> base = tileClass.getSuperclass();
        XposedHelpers.findAndHookMethod(base, "handleLongClick",
            XposedHelpers.findClass("com.android.systemui.animation.Expandable", loader), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!tileClass.isInstance(param.thisObject)) return;
                    Object tile = param.thisObject;
                    Object expandable = param.args[0];
                    List<?> callbacks = new ArrayList<>((Collection<?>) XposedHelpers.getObjectField(tile, "mCallbacks"));
                    if (!XposedHelpers.getStaticBooleanField(base, "mInControlCenter")
                        && !(expandable == null && callbacks.stream().anyMatch(ScreenshotTileHook::isPluginCallback))) return;
                    param.setResult(null);
                    main.post(() -> {
                        try {
                            View anchor = findAnchor(expandable, callbacks);
                            if (anchor != null) show(anchor, tile, expandable);
                            else HookLog.info("screenshot-tile anchor not attached");
                        } catch (Throwable error) {
                            HookLog.error("screenshot-tile popup", error);
                        }
                    });
                }
            });
    }

    private static View findAnchor(Object expandable, List<?> callbacks) {
        if (expandable != null && expandable.getClass().getName().equals(
            "com.android.systemui.animation.Expandable$Companion$fromView$1")) {
            View view = (View) XposedHelpers.getObjectField(expandable, "$view");
            if (view != null && view.isAttachedToWindow() && view.isShown()) return view;
        }
        // The HyperOS plugin calls longClick() without passing its tile view.
        for (Object callback : callbacks) {
            if (!isPluginCallback(callback)) continue;
            Object record = XposedHelpers.getObjectField(callback, "this$0");
            Object view = XposedHelpers.callMethod(record, "getTileView");
            if (view instanceof View anchor && anchor.isAttachedToWindow() && anchor.isShown()) return anchor;
        }
        return null;
    }

    private static boolean isPluginCallback(Object callback) {
        return PLUGIN_CALLBACK.equals(callback.getClass().getName());
    }

    private void show(View anchor, Object tile, Object expandable) {
        if (popup != null) popup.dismiss();
        Context themed = anchor.getContext();
        Rect visible = new Rect();
        anchor.getWindowVisibleDisplayFrame(visible);
        if (visible.isEmpty()) return;
        int width = Math.min(dp(themed, 192), visible.width());
        int gap = dp(themed, 6);
        boolean dark = (themed.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        int ink = dark ? 0xfff3f4f6 : 0xff191d24;
        int accent = dark ? 0xff9bc7ff : 0xff1765c2;
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(dark ? 0xff292d34 : 0xfff9faff);
        surface.setCornerRadius(dp(themed, 20));
        LinearLayout rows = new LinearLayout(themed);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(themed, 6), dp(themed, 6), dp(themed, 6), dp(themed, 6));
        ScrollView content = new ScrollView(themed);
        content.setVerticalScrollBarEnabled(false);
        content.addView(rows);
        PopupWindow menu = new PopupWindow(content, width, WindowManager.LayoutParams.WRAP_CONTENT, true);
        menu.setBackgroundDrawable(surface);
        menu.setOutsideTouchable(true);
        menu.setElevation(dp(themed, 9));
        menu.setIsLaidOutInScreen(true);
        menu.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        menu.setAnimationStyle(0);
        String[] labels = {"截屏", "区域截屏", "长截屏"};
        int[] requests = {0, ScreenshotAppHook.REGION_REQUEST, ScreenshotAppHook.LONG_REQUEST};
        for (int index = 0; index < labels.length; index++) {
            int request = requests[index];
            TextView row = new TextView(themed);
            row.setText(labels[index]);
            row.setTextSize(16);
            row.setTextColor(ink);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(themed, 50));
            row.setPaddingRelative(dp(themed, 12), dp(themed, 6), dp(themed, 12), dp(themed, 6));
            row.setCompoundDrawablePadding(dp(themed, 13));
            Drawable icon = new ModeIcon(index, accent);
            icon.setBounds(0, 0, dp(themed, 24), dp(themed, 24));
            row.setCompoundDrawablesRelative(icon, null, null, null);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(dp(themed, 14));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x26ffffff : 0x180b3165), null, mask));
            row.setFocusable(true);
            row.setOnClickListener(view -> {
                menu.dismiss();
                select(tile, expandable, request);
            });
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(visible.height(), View.MeasureSpec.AT_MOST));
        int height = content.getMeasuredHeight();
        menu.setHeight(height);
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int x = Math.max(visible.left, Math.min(
            location[0] + (anchor.getWidth() - width) / 2, visible.right - width));
        int y = location[1] + anchor.getHeight() + gap;
        if (y + height > visible.bottom) y = location[1] - gap - height;
        y = Math.max(visible.top, Math.min(y, visible.bottom - height));

        View.OnAttachStateChangeListener dismissOnDetach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { menu.dismiss(); }
        };
        menu.setOnDismissListener(() -> {
            anchor.removeOnAttachStateChangeListener(dismissOnDetach);
            if (popup == menu) popup = null;
        });
        // A dropdown follows every subsequent anchor layout; this menu uses one screen position.
        menu.showAtLocation(anchor.getRootView(), Gravity.TOP | Gravity.LEFT, x, y);
        anchor.addOnAttachStateChangeListener(dismissOnDetach);
        popup = menu;
    }

    private void select(Object tile, Object expandable, int request) {
        try {
            if (request == 0) {
                XposedHelpers.callMethod(tile, "click", expandable);
                return;
            }
            if (collapsePending) return;
            Class<?> restrictions = XposedHelpers.findClass("miui.enterprise.RestrictionsHelperStub", loader);
            Object helper = XposedHelpers.callStaticMethod(restrictions, "getInstance");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(helper, "isRestriction", "disallow_screencapture"))) {
                Toast.makeText(context, "当前系统策略禁止截屏", Toast.LENGTH_SHORT).show();
                return;
            }
            Object host = XposedHelpers.getObjectField(tile, "mHost");
            XposedHelpers.callMethod(host, "collapsePanels");
            collapsePending = true;
            long timeout = SystemClock.uptimeMillis() + 3000;
            main.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (Boolean.TRUE.equals(XposedHelpers.callMethod(host, "isQSFullyCollapsed"))) {
                            // Match the tile's delay after the control center finishes collapsing.
                            main.postDelayed(() -> {
                                collapsePending = false;
                                capture(request);
                            }, 400);
                        } else if (SystemClock.uptimeMillis() < timeout) {
                            main.postDelayed(this, 50);
                        } else {
                            collapsePending = false;
                            unavailable();
                        }
                    } catch (Throwable error) {
                        collapsePending = false;
                        HookLog.error("screenshot-tile collapse", error);
                        unavailable();
                    }
                }
            });
        } catch (Throwable error) {
            HookLog.error("screenshot-tile select", error);
            unavailable();
        }
    }

    private void capture(int request) {
        try {
            boolean region = request == ScreenshotAppHook.REGION_REQUEST;
            Object helper = screenshotHelper;
            if (region || helper == null) {
                helper = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "com.android.internal.util.ScreenshotHelper", loader), context);
                if (!region) screenshotHelper = helper;
            }
            Class<?> stub = XposedHelpers.findClass("com.android.internal.util.ScreenshotHelperStub", loader);
            Object instance = XposedHelpers.callStaticMethod(stub, "getInstance");
            ComponentName service = ComponentName.unflattenFromString(
                (String) XposedHelpers.callMethod(instance, "getServiceComponent", context));
            if (!new ComponentName(ScreenshotAppHook.PACKAGE, ScreenshotAppHook.SERVICE).equals(service)) {
                unavailable();
                return;
            }
            // Use extension request codes. An unhooked screenshot service ignores them.
            Object builder = XposedHelpers.newInstance(XposedHelpers.findClass(
                "com.android.internal.util.ScreenshotRequest$Builder", loader), request);
            Object captureRequest = XposedHelpers.callMethod(builder, "build");
            if (region) {
                // The region response arrives after the user saves or cancels the native selector.
                XposedHelpers.callMethod(helper, "takeScreenshotInternal", captureRequest, main,
                    (Consumer<Uri>) null, REGION_TIMEOUT_MS);
            } else {
                XposedHelpers.callMethod(helper, "takeScreenshot", captureRequest, main,
                    (Consumer<Uri>) uri -> { if (uri == null) unavailable(); });
            }
        } catch (Throwable error) {
            HookLog.error("screenshot-tile request", error);
            unavailable();
        }
    }

    private void unavailable() {
        Toast.makeText(context, "截屏模式暂不可用", Toast.LENGTH_SHORT).show();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class ModeIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int mode;

        ModeIcon(int mode, int color) {
            this.mode = mode;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            int saved = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            if (mode == 1) {
                canvas.drawLine(7, 3, 7, 17, paint);
                canvas.drawLine(7, 17, 21, 17, paint);
                canvas.drawLine(3, 7, 17, 7, paint);
                canvas.drawLine(17, 7, 17, 21, paint);
            } else if (mode == 2) {
                canvas.drawRoundRect(4, 2.5f, 20, 21.5f, 3, 3, paint);
                canvas.drawLine(12, 7, 12, 17, paint);
                canvas.drawLine(8.5f, 13.5f, 12, 17, paint);
                canvas.drawLine(12, 17, 15.5f, 13.5f, paint);
            } else {
                canvas.drawRoundRect(3, 4, 21, 20, 3, 3, paint);
                canvas.drawCircle(12, 12, 3, paint);
            }
            canvas.restoreToCount(saved);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
