package io.github.hypernova.feature.notification;

import android.content.Context;
import android.app.Notification;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dalvik.system.InMemoryDexClassLoader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;

/** All host mutations run on SystemUI's main thread; no notification payloads are stored. */
public final class NotificationControlsHook {
    // Resource IDs belong to the hash-verified SystemUI APK, not this module's R class.
    private static final int HOST_MENU_LAYOUT = 0x7f0d0217;
    private static final int HOST_MENU_ICON = 0x7f0a073e, HOST_MENU_TITLE = 0x7f0a073f;
    private static final int HOST_PANEL_WIDTH = 0x7f071338, HOST_SIDE_PADDING = 0x7f071353;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private final WeakHashMap<Object, WeakReference<Menu>> menus = new WeakHashMap<>();
    private volatile NotificationSelection selection;
    private String previousPin;
    private Object builder, prioritySectioner;
    private Object clearController;
    private int priorityBucket = Integer.MIN_VALUE;
    private Class<?> rowClass, entryClass, listEntryClass, builderClass, dependencyClass, modalClass;
    private Constructor<?> sectionConstructor;
    private final List<Class<?>> stabilityClasses = new ArrayList<>();
    private long lastError;
    private boolean swipeObserved, pinObserved, detachObserved;

    private NotificationControlsHook(Context context) {
        this.context = context;
        preferences = context.createDeviceProtectedStorageContext().getSharedPreferences("hypernova_notification_controls", Context.MODE_PRIVATE);
        selection = new NotificationSelection(preferences.getString("locked_notification", null),
            preferences.getString("pinned_notification", null));
    }

    public static void install(Context context, ClassLoader host) {
        try { new NotificationControlsHook(context).attach(host); }
        catch (Throwable error) { HookLog.error("notification-controls setup", error); }
    }

    private void attach(ClassLoader host) {
        try {
            rowClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", host);
            entryClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.NotificationEntry", host);
            listEntryClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.ListEntry", host);
            builderClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.ShadeListBuilder", host);
            dependencyClass = XposedHelpers.findClass("com.android.systemui.Dependency", host);
            modalClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.modal.ModalController", host);
            Class<?> menuClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow", host);
            Class<?> swipeClass = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.MiuiNotificationSwipeCallback", host);
            Class<?> stackCallback = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController$9", host);
            Method create = menuClass.getDeclaredMethod("createMenuViews", boolean.class);
            Method dismiss = rowClass.getDeclaredMethod("canViewBeDismissed");
            Method swipe = swipeClass.getDeclaredMethod("canChildBeDismissed", View.class);
            Method direction = swipeClass.getDeclaredMethod("canChildBeDismissedInDirection", View.class);
            Method sections = builderClass.getDeclaredMethod("setSectioners", List.class);
            Method build = builderClass.getDeclaredMethod("buildList");
            Method isGroup = StatusBarNotification.class.getDeclaredMethod("isGroup");
            Class<?> collection = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.NotifCollection", host);
            Method remove = collection.getDeclaredMethod("tryRemoveNotification", entryClass);
            // These exact fields are part of the firmware profile, and are resolved before installing anything.
            XposedHelpers.findField(menuClass, "mMenuContainer"); XposedHelpers.findField(menuClass, "mInfoItem");
            XposedHelpers.findField(rowClass, "mEntry");
            XposedHelpers.findField(entryClass, "mSbn");
            XposedHelpers.findField(builderClass, "mNotifSections"); XposedHelpers.findField(builderClass, "mIterationCount");
            rowClass.getDeclaredMethod("getAttachedChildren");
            Context module = context.createPackageContext("io.github.hypernova", Context.CONTEXT_IGNORE_SECURITY);
            byte[] dex;
            try (java.io.InputStream input = module.getAssets().open("classes.dex")) { dex = input.readAllBytes(); }
            ClassLoader bridge = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), host);
            sectionConstructor = bridge.loadClass("io.github.hypernova.bridge.PinSectioner").getConstructor(int.class, Predicate.class);
            installClearProtection(host, collection);
            hooks.add(XposedBridge.hookMethod(create, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) safely(() -> createMenu(param.thisObject));
                }
            }));
            XC_MethodHook lockedRow = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !Boolean.TRUE.equals(param.getResult())) return;
                    Object row = param.args.length == 0 ? param.thisObject : param.args[0];
                    if (rowClass.isInstance(row) && lockedRow(row)) {
                        param.setResult(false);
                        if (!swipeObserved) { swipeObserved = true; HookLog.info("notification-lock OBSERVED"); }
                    }
                }
            };
            hooks.add(XposedBridge.hookMethod(dismiss, lockedRow));
            hooks.add(XposedBridge.hookMethod(swipe, lockedRow));
            hooks.add(XposedBridge.hookMethod(direction, lockedRow));
            hooks.add(XposedBridge.hookMethod(stackCallback.getDeclaredMethod("canChildBeDismissed", View.class), lockedRow));
            hooks.add(XposedBridge.hookMethod(stackCallback.getDeclaredMethod("canChildBeDismissedInDirection", View.class), lockedRow));
            hooks.add(XposedBridge.hookMethod(sections, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    safely(() -> {
                        builder = param.thisObject;
                        List<?> original = (List<?>) param.args[0];
                        List<Object> replacement = new ArrayList<>();
                        for (Object section : original) if (section != prioritySectioner) replacement.add(section);
                        Object fallback = XposedHelpers.getStaticObjectField(builderClass, "DEFAULT_SECTIONER");
                        int bucket = XposedHelpers.getIntField(replacement.isEmpty() ? fallback : replacement.get(0), "mBucket");
                        if (prioritySectioner == null || priorityBucket != bucket) {
                            prioritySectioner = sectionConstructor.newInstance(bucket, (Predicate<Object>) NotificationControlsHook.this::pinnedListEntry);
                            priorityBucket = bucket;
                        }
                        replacement.add(0, prioritySectioner);
                        param.args[0] = replacement;
                    });
                }
            }));
            hooks.add(XposedBridge.hookMethod(build, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    safely(() -> {
                        builder = param.thisObject;
                        param.setObjectExtra("hypernova_iteration", XposedHelpers.getIntField(builder, "mIterationCount"));
                        ensureSections();
                        allowSelectedReorder();
                    });
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object iteration = param.getObjectExtra("hypernova_iteration");
                    if (!param.hasThrowable() && iteration instanceof Integer
                        && XposedHelpers.getIntField(param.thisObject, "mIterationCount") > (Integer) iteration) previousPin = null;
                }
            }));
            hooks.add(XposedBridge.hookMethod(isGroup, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable() && selection.pinned(((StatusBarNotification) param.thisObject).getKey())) {
                        // The native grouping stage then puts this exact entry under ROOT_ENTRY.
                        // Server-side group metadata and all sibling notifications remain intact.
                        param.setResult(false);
                        if (!detachObserved) { detachObserved = true; HookLog.info("notification-detach OBSERVED"); }
                    }
                }
            }));
            hooks.add(XposedBridge.hookMethod(remove, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !Boolean.TRUE.equals(param.getResult())) return;
                    String removed = key(param.args[0]);
                    NotificationSelection updated = selection.removed(removed);
                    if (updated != selection) { selection = updated; persist(); updateMenus(); }
                }
            }));
            // ModalController owns the existing pipeline even if it was constructed before module setup.
            connectPipeline();
            HookLog.info("notification-controls INSTALLED (single notification lock + pin)");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("notification-controls install", error);
        }
    }

    private void connectPipeline() {
        safely(() -> {
            Object dependency = XposedHelpers.getStaticObjectField(dependencyClass, "sDependency");
            Object modal = XposedHelpers.callMethod(dependency, "getDependencyInner", modalClass);
            Object pipeline = XposedHelpers.getObjectField(modal, "pipeline");
            builder = XposedHelpers.getObjectField(pipeline, "mShadeListBuilder");
            if (idle()) { ensureSections(); allowSelectedReorder(); XposedHelpers.callMethod(builder, "rebuildListIfBefore", 1); }
        });
    }

    private void installClearProtection(ClassLoader host, Class<?> collection) throws ReflectiveOperationException {
        Class<?> stack = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout", host);
        Class<?> controller = XposedHelpers.findClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController", host);
        Class<?> visibility = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.provider.NotificationVisibilityProviderImpl", host);
        Class<?> stats = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats", host);
        Class<?> visibilityValue = XposedHelpers.findClass("com.android.internal.statusbar.NotificationVisibility", host);
        Method clear = stack.getDeclaredMethod("clearNotifications", int.class, boolean.class, boolean.class);
        Method include = stack.getDeclaredMethod("includeChildInClearAll", rowClass, int.class);
        Method all = collection.getDeclaredMethod("dismissAllNotifications", int.class);
        Method dismiss = collection.getDeclaredMethod("dismissNotifications", List.class);
        Method obtain = visibility.getDeclaredMethod("obtain", entryClass, boolean.class);
        Constructor<?> statsConstructor = stats.getConstructor(int.class, visibilityValue);
        collection.getDeclaredMethod("getEntry", String.class); entryClass.getDeclaredMethod("isClearable");
        XposedHelpers.findField(stack, "mController");
        XposedHelpers.findField(controller, "mNotifCollection"); XposedHelpers.findField(controller, "mVisibilityProvider");
        XposedHelpers.findField(collection, "mReadOnlyNotificationSet"); XposedHelpers.findField(entryClass, "mDismissState");
        hooks.add(XposedBridge.hookMethod(clear, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                clearController = XposedHelpers.getObjectField(param.thisObject, "mController");
            }
        }));
        hooks.add(XposedBridge.hookMethod(include, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !Boolean.TRUE.equals(param.getResult()) || selection.locked == null) return;
                try {
                    Object row = param.args[0];
                    StatusBarNotification locked = clearController == null ? null
                        : lockedNotification(XposedHelpers.getObjectField(clearController, "mNotifCollection"));
                    if (lockedRow(row) || protectedFromClear(XposedHelpers.getObjectField(row, "mEntry"), locked)) param.setResult(false);
                } catch (Throwable error) { param.setResult(false); report(error); }
            }
        }));
        hooks.add(XposedBridge.hookMethod(dismiss, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (selection.locked == null) return;
                try {
                    StatusBarNotification locked = lockedNotification(param.thisObject);
                    if (locked == null) return;
                    List<Object> filtered = new ArrayList<>();
                    for (Object item : (List<?>) param.args[0])
                        if (!protectedFromClear(((Pair<?, ?>) item).first, locked)) filtered.add(item);
                    param.args[0] = filtered;
                } catch (Throwable error) { param.setResult(null); report(error); }
            }
        }));
        hooks.add(XposedBridge.hookMethod(all, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (selection.locked == null) return;
                try {
                    StatusBarNotification locked = lockedNotification(param.thisObject);
                    if (locked == null) return;
                    // Stop the server-wide clear before making individual requests; row filtering alone is insufficient.
                    param.setResult(null);
                    if (clearController == null) throw new IllegalStateException("Clear controller unavailable");
                    Object provider = XposedHelpers.getObjectField(clearController, "mVisibilityProvider");
                    int user = (int) param.args[0];
                    List<Object> eligible = new ArrayList<>();
                    Collection<?> entries = (Collection<?>) XposedHelpers.getObjectField(param.thisObject, "mReadOnlyNotificationSet");
                    for (Object entry : new ArrayList<>(entries)) {
                        StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.getObjectField(entry, "mSbn");
                        int owner = sbn.getUserId();
                        // Preserve the firmware's user, clearability, bubble and already-dismissed checks.
                        if ((user != -1 && owner != -1 && owner != user) || protectedFromClear(entry, locked)
                            || !(boolean) XposedHelpers.callMethod(entry, "isClearable")
                            || (sbn.getNotification().flags & Notification.FLAG_BUBBLE) != 0
                            || "DISMISSED".equals(((Enum<?>) XposedHelpers.getObjectField(entry, "mDismissState")).name())) continue;
                        eligible.add(new Pair<>(entry, statsConstructor.newInstance(3, obtain.invoke(provider, entry, true))));
                    }
                    XposedHelpers.callMethod(param.thisObject, "dismissNotifications", eligible);
                    HookLog.info("notification-clear-all PROTECTED count=" + eligible.size());
                } catch (Throwable error) {
                    param.setResult(null);
                    report(error);
                    Toast.makeText(context, "清理暂不可用，已保留锁定通知", Toast.LENGTH_SHORT).show();
                }
            }
        }));
    }

    private StatusBarNotification lockedNotification(Object collection) {
        Object entry = XposedHelpers.callMethod(collection, "getEntry", selection.locked);
        return entry == null ? null : (StatusBarNotification) XposedHelpers.getObjectField(entry, "mSbn");
    }

    private boolean protectedFromClear(Object entry, StatusBarNotification locked) {
        StatusBarNotification sbn = (StatusBarNotification) XposedHelpers.getObjectField(entry, "mSbn");
        return selection.protectsClear(sbn.getKey(), sbn.getGroupKey(), (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0,
            locked == null ? null : locked.getGroupKey(), locked != null && (locked.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0);
    }

    private boolean idle() {
        return builder != null && XposedHelpers.getIntField(XposedHelpers.getObjectField(builder, "mPipelineState"), "mState") == 0;
    }
    private void ensureSections() {
        if (!idle()) return;
        List<?> sections = (List<?>) XposedHelpers.getObjectField(builder, "mNotifSections");
        List<Object> originals = new ArrayList<>();
        Object fallback = XposedHelpers.getStaticObjectField(builderClass, "DEFAULT_SECTIONER");
        for (Object section : sections) {
            Object sectioner = XposedHelpers.getObjectField(section, "sectioner");
            if (sectioner == prioritySectioner) return;
            if (sectioner != fallback) originals.add(sectioner);
        }
        XposedHelpers.callMethod(builder, "setSectioners", originals);
    }

    private void allowSelectedReorder() {
        Object stability = XposedHelpers.getObjectField(builder, "mNotifStabilityManager");
        if (stability == null || stabilityClasses.contains(stability.getClass())) return;
        List<XC_MethodHook.Unhook> added = new ArrayList<>();
        try {
            for (String name : new String[]{"isSectionChangeAllowed", "isEntryReorderingAllowed", "isGroupChangeAllowed"}) {
                Class<?> argument = name.equals("isEntryReorderingAllowed") ? listEntryClass : entryClass;
                Class<?> owner = stability.getClass();
                Method method = null;
                while (owner != null) {
                    try { method = owner.getDeclaredMethod(name, argument); break; }
                    catch (NoSuchMethodException absent) { owner = owner.getSuperclass(); }
                }
                if (method == null || Modifier.isAbstract(method.getModifiers())) throw new NoSuchMethodException(name);
                added.add(XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable()) return;
                        if (pinnedListEntry(param.args[0]) || matchesListEntry(param.args[0], previousPin)) param.setResult(true);
                    }
                }));
            }
            hooks.addAll(added); stabilityClasses.add(stability.getClass());
        } catch (Throwable error) { for (XC_MethodHook.Unhook hook : added) hook.unhook(); report(error); }
    }

    private String key(Object entry) {
        if (entry == null) return null;
        try {
            return ((StatusBarNotification) XposedHelpers.getObjectField(entry, "mSbn")).getKey();
        } catch (Throwable error) { report(error); return null; }
    }
    private boolean lockedRow(Object row) {
        if (selection.locked == null) return false;
        try {
            if (selection.locked(key(XposedHelpers.getObjectField(row, "mEntry")))) return true;
            List<?> children = (List<?>) XposedHelpers.callMethod(row, "getAttachedChildren");
            if (children != null) for (Object child : children)
                if (selection.locked(key(XposedHelpers.getObjectField(child, "mEntry")))) return true;
        } catch (Throwable error) { report(error); }
        return false;
    }
    private boolean pinnedListEntry(Object entry) {
        boolean matches = matchesListEntry(entry, selection.pinned);
        if (matches && !pinObserved) { pinObserved = true; HookLog.info("notification-pin OBSERVED"); }
        return matches;
    }
    private boolean matchesListEntry(Object entry, String target) {
        return target != null && entryClass.isInstance(entry) && target.equals(key(entry));
    }

    @android.annotation.SuppressLint("ResourceType")
    private void createMenu(Object owner) {
        Object parent = XposedHelpers.getObjectField(owner, "mParent");
        String target = parent == null ? null : key(XposedHelpers.getObjectField(parent, "mEntry"));
        if (target == null) return;
        ViewGroup container = (ViewGroup) XposedHelpers.getObjectField(owner, "mMenuContainer");
        Object info = XposedHelpers.getObjectField(owner, "mInfoItem");
        View more = (View) XposedHelpers.callMethod(info, "getMenuView");
        if (container == null || !(container instanceof LinearLayout) || more == null || more.getParent() != container) return;
        WeakReference<Menu> oldReference = menus.remove(owner);
        Menu old = oldReference == null ? null : oldReference.get();
        if (old != null) { container.removeView(old.lock); container.removeView(old.pin); }
        Menu menu = new Menu(target, container.getContext(), more);
        int position = container.indexOfChild(more);
        container.addView(menu.lock, position);
        container.addView(menu.pin, position + 2);
        int width = context.getResources().getDimensionPixelSize(HOST_PANEL_WIDTH);
        if (width <= 0) width = context.getResources().getDisplayMetrics().widthPixels;
        width -= context.getResources().getDimensionPixelSize(HOST_SIDE_PADDING) * 2;
        int slot = width / container.getChildCount();
        for (int i = 0; i < container.getChildCount(); i++) {
            View item = container.getChildAt(i);
            item.setLayoutParams(new LinearLayout.LayoutParams(slot, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView title = item.findViewById(HOST_MENU_TITLE);
            if (title != null) { title.setMaxWidth(slot); title.setMaxLines(2); }
            ImageView icon = item.findViewById(HOST_MENU_ICON);
            if (icon != null) {
                int side = Math.min(dp(container.getContext(), 64), Math.max(dp(container.getContext(), 48), slot));
                icon.setLayoutParams(new LinearLayout.LayoutParams(side, side));
            }
        }
        menus.put(owner, new WeakReference<>(menu)); menu.update();
    }
    private final class Menu {
        final String target;
        final View lock, pin;
        Menu(String target, Context themed, View more) {
            this.target = target;
            lock = action(themed, false, more); pin = action(themed, true, more);
            lock.setOnClickListener(view -> toggle(false, target));
            pin.setOnClickListener(view -> toggle(true, target));
        }
        void update() {
            updateAction(lock, selection.lockLabel(target), selection.locked(target));
            updateAction(pin, selection.pinLabel(target), selection.pinned(target));
        }
    }
    @android.annotation.SuppressLint("ResourceType")
    private View action(Context themed, boolean pin, View more) {
        View item = LayoutInflater.from(themed).inflate(HOST_MENU_LAYOUT, null, false);
        ImageView image = item.findViewById(HOST_MENU_ICON);
        image.setImageDrawable(new Glyph(pin, dp(themed, 64)));
        ImageView original = more.findViewById(HOST_MENU_ICON);
        Drawable background = original.getBackground();
        if (background != null && background.getConstantState() != null) image.setBackground(background.getConstantState().newDrawable().mutate());
        image.setOnClickListener(view -> item.performClick());
        TextView title = item.findViewById(HOST_MENU_TITLE);
        TextView source = more.findViewById(HOST_MENU_TITLE);
        title.setTextColor(source.getTextColors()); title.setTextSize(TypedValue.COMPLEX_UNIT_PX, source.getTextSize());
        item.setFocusable(true);
        return item;
    }
    @android.annotation.SuppressLint("ResourceType")
    private void updateAction(View item, String text, boolean selected) {
        ((TextView) item.findViewById(HOST_MENU_TITLE)).setText(text);
        item.setContentDescription(text); item.setSelected(selected);
        ImageView icon = item.findViewById(HOST_MENU_ICON);
        icon.setContentDescription(text); icon.setAlpha(selected ? 1f : .85f);
    }
    private void toggle(boolean pin, String target) {
        NotificationSelection old = selection;
        selection = pin ? old.togglePin(target) : old.toggleLock(target);
        if (pin) previousPin = old.pinned;
        persist();
        updateMenus();
        if (pin) {
            if (builder == null) connectPipeline();
            main.post(() -> safely(() -> { if (builder != null) XposedHelpers.callMethod(builder, "rebuildListIfBefore", 1); }));
        }
        Toast.makeText(context, pin ? (selection.pinned(target) ? "已置顶此条通知" : "已取消置顶")
            : (selection.locked(target) ? "已锁定此条通知" : "已取消锁定"), Toast.LENGTH_SHORT).show();
        HookLog.info(pin ? "notification-pin CHANGED" : "notification-lock CHANGED");
    }
    private void persist() {
        SharedPreferences.Editor editor = preferences.edit();
        if (selection.locked == null) editor.remove("locked_notification"); else editor.putString("locked_notification", selection.locked);
        if (selection.pinned == null) editor.remove("pinned_notification"); else editor.putString("pinned_notification", selection.pinned);
        editor.apply();
    }
    private void updateMenus() {
        for (WeakReference<Menu> reference : new ArrayList<>(menus.values())) {
            Menu menu = reference.get();
            if (menu != null) menu.update();
        }
    }
    private interface HostAction { void run() throws Throwable; }
    private void safely(HostAction action) { try { action.run(); } catch (Throwable error) { report(error); } }
    private void report(Throwable error) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastError == 0 || now - lastError > 60_000) { lastError = now; HookLog.error("notification-controls", error); }
    }
    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static final class Glyph extends Drawable {
        private final boolean pin;
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Glyph(boolean pin, int size) { this.pin = pin; this.size = size; paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); }
        @Override public void draw(Canvas canvas) {
            int save = canvas.save();
            canvas.translate(getBounds().left, getBounds().top); canvas.scale(getBounds().width() / 48f, getBounds().height() / 48f);
            if (pin) {
                Path path = new Path(); path.moveTo(18, 16); path.lineTo(30, 16); path.moveTo(20, 16); path.lineTo(20, 23);
                path.lineTo(17, 27); path.lineTo(31, 27); path.lineTo(28, 23); path.lineTo(28, 16); path.moveTo(24, 27); path.lineTo(24, 34); canvas.drawPath(path, paint);
            } else {
                canvas.drawRoundRect(new RectF(17, 23, 31, 33), 2, 2, paint);
                canvas.drawArc(new RectF(19, 15, 29, 27), 180, 180, false, paint); canvas.drawLine(19, 21, 19, 23, paint); canvas.drawLine(29, 21, 29, 23, paint);
            }
            canvas.restoreToCount(save);
        }
        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
