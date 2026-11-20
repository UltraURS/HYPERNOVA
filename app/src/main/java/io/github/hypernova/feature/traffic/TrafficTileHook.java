package io.github.hypernova.feature.traffic;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;

public final class TrafficTileHook {
    private final Map<Object, Session> sessions = new WeakHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HYPERNOVA-usage"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean querying = new AtomicBoolean();
    private volatile UsageSnapshot cache;
    private long lastError = -60_000;
    private boolean observed;
    private Class<?> tileClass;
    private Field contextField, handlerField, listenersField, staleField, labelField, descriptionField;
    private Method refresh;

    public static void install(ClassLoader loader) throws Exception { new TrafficTileHook().attach(loader); }

    private void attach(ClassLoader loader) throws Exception {
        tileClass = XposedHelpers.findClass("com.android.systemui.qs.tiles.MiuiCellularTile", loader);
        Class<?> base = XposedHelpers.findClass("com.android.systemui.qs.tileimpl.QSTileImpl", loader);
        Class<?> state = XposedHelpers.findClass("com.android.systemui.plugins.qs.QSTile$State", loader);
        contextField = XposedHelpers.findField(base, "mContext");
        handlerField = XposedHelpers.findField(base, "mHandler");
        listenersField = XposedHelpers.findField(base, "mListeners");
        staleField = XposedHelpers.findField(base, "mStaleListener");
        labelField = XposedHelpers.findField(state, "label");
        descriptionField = XposedHelpers.findField(state, "contentDescription");
        refresh = base.getDeclaredMethod("refreshState"); refresh.setAccessible(true);
        Method update = tileClass.getDeclaredMethod("handleUpdateState", state, Object.class);
        Method listening = base.getDeclaredMethod("setListening", Object.class, boolean.class);
        Method destroy = base.getDeclaredMethod("handleDestroy");
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        try {
            installed.add(XposedBridge.hookMethod(update, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try {
                        Session session = session(param.thisObject);
                        if (session.destroyed) return;
                        // An existing tile may have begun listening before setup completed.
                        session.handler.post(session::reconcile);
                        UsageSnapshot snapshot = cache;
                        if (snapshot != null && snapshot.valid(System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault())) {
                            String text = DailyUsage.label(snapshot.bytes);
                            Object previous = descriptionField.get(param.args[0]);
                            labelField.set(param.args[0], text);
                            descriptionField.set(param.args[0], previous == null ? text : previous + ", " + text);
                            if (!observed) { observed = true; HookLog.info("traffic OBSERVED label updated"); }
                        }
                    } catch (Throwable error) { error(error); }
                }
            }));
            installed.add(XposedBridge.hookMethod(listening, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!tileClass.isInstance(param.thisObject) || param.hasThrowable()) return;
                    try {
                        Session session = session(param.thisObject);
                        // setListening enqueues its mutation; this runs after that mutation.
                        session.handler.post(session::reconcile);
                    } catch (Throwable error) { error(error); }
                }
            }));
            installed.add(XposedBridge.hookMethod(destroy, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!tileClass.isInstance(param.thisObject)) return;
                    synchronized (sessions) {
                        Session session = sessions.get(param.thisObject);
                        if (session != null) session.stop(true);
                    }
                }
            }));
            HookLog.info("traffic INSTALLED");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : installed) hook.unhook();
            worker.shutdownNow();
            throw new IllegalStateException("Traffic hook installation failed", error);
        }
    }

    private Session session(Object tile) throws IllegalAccessException {
        synchronized (sessions) {
            Session session = sessions.get(tile);
            if (session == null) {
                session = new Session(tile, (Handler) handlerField.get(tile), (Context) contextField.get(tile));
                sessions.put(tile, session);
            }
            return session;
        }
    }

    private final class Session {
        final WeakReference<Object> tile;
        final Handler handler;
        final Context context;
        boolean active, destroyed;
        final Runnable tick = this::tick;
        Session(Object tile, Handler handler, Context context) {
            this.tile = new WeakReference<>(tile); this.handler = handler; this.context = context;
        }
        boolean visible(Object target) throws IllegalAccessException {
            Collection<?> listeners = (Collection<?>) listenersField.get(target);
            Object stale = staleField.get(target);
            for (Object listener : listeners) if (listener != stale) return true;
            return false;
        }
        void reconcile() {
            if (destroyed) return;
            try {
                Object target = tile.get();
                boolean listening = target != null && visible(target);
                if (listening && !active) { active = true; tick(); }
                else if (!listening) stop(false);
            } catch (Throwable error) { stop(false); error(error); }
        }
        void stop(boolean permanent) {
            active = false; destroyed |= permanent; handler.removeCallbacks(tick);
        }
        void tick() {
            if (destroyed || !active) return;
            Object target = tile.get();
            try {
                if (target == null || !visible(target)) { stop(false); return; }
                // Drop expired/yesterday's text before a potentially slow query.
                refresh.invoke(target);
                request(context);
                handler.removeCallbacks(tick);
                handler.postDelayed(tick, 30_000);
            } catch (Throwable error) { stop(false); error(error); }
        }
        void publish() {
            if (destroyed || !active) return;
            Object target = tile.get();
            try {
                if (target != null && visible(target)) refresh.invoke(target);
            } catch (Throwable error) { error(error); }
        }
    }

    @SuppressWarnings("deprecation")
    private void request(Context context) {
        UsageSnapshot previous = cache;
        if (previous != null && previous.valid(System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault())) return;
        if (!querying.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                long elapsed = SystemClock.elapsedRealtime();
                ZoneId zone = ZoneId.systemDefault();
                long start = DailyUsage.startOfDay(now, zone);
                NetworkStatsManager manager = context.getSystemService(NetworkStatsManager.class);
                if (manager == null) throw new IllegalStateException("Network stats unavailable");
                NetworkStats.Bucket bucket = manager.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, now);
                if (bucket == null) throw new IllegalStateException("No usage summary");
                UsageSnapshot result = new UsageSnapshot(start, now, elapsed, zone,
                    UsageSnapshot.total(bucket.getRxBytes(), bucket.getTxBytes()));
                cache = result.valid(System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault()) ? result : null;
            } catch (Throwable error) { cache = null; error(error); }
            finally {
                querying.set(false);
                List<Session> targets;
                synchronized (sessions) { targets = new ArrayList<>(sessions.values()); }
                for (Session session : targets) session.handler.post(session::publish);
            }
        });
    }

    private synchronized void error(Throwable error) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastError >= 60_000) { lastError = now; HookLog.error("traffic", error); }
    }
}
