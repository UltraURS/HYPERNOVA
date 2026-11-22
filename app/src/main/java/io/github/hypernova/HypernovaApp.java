// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova;

import android.app.Application;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import io.github.hypernova.config.Feature;
import io.github.hypernova.config.LegacyConfigMigration;
import io.github.hypernova.config.ScopeTargets;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HypernovaApp extends Application implements XposedServiceHelper.OnServiceListener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private XposedService service;
    private SharedPreferences preferences;
    private int api;
    private volatile ScopeRequest pending;

    @Override public void onCreate() {
        super.onCreate();
        if (!BuildConfig.LEGACY_CONFIG_MIGRATION) XposedServiceHelper.registerListener(this);
    }

    @Override public void onServiceBind(XposedService connected) {
        worker.execute(() -> {
            int version = 0;
            SharedPreferences config = null;
            try {
                version = connected.getApiVersion();
                SharedPreferences remote = connected.getRemotePreferences(Feature.PREFS);
                if (LegacyConfigMigration.initialize(this, remote)) config = remote;
            } catch (Exception error) {
                Log.w("HYPERNOVA", "Could not initialize module configuration", error);
            }
            int frameworkApi = version;
            SharedPreferences loaded = config;
            main.post(() -> updateConnection(connected, frameworkApi, loaded));
        });
    }

    @Override public void onServiceDied(XposedService disconnected) {
        worker.execute(() -> main.post(() -> {
            if (service == disconnected) updateConnection(null, 0, null);
        }));
    }

    private void updateConnection(XposedService connected, int frameworkApi, SharedPreferences config) {
        if (service != connected) pending = null;
        service = connected;
        api = frameworkApi;
        preferences = config;
        for (Runnable listener : Set.copyOf(listeners)) listener.run();
    }

    public int frameworkApi() { return api; }
    public SharedPreferences preferences() { return preferences; }
    public void addConnectionListener(Runnable listener) { listeners.add(listener); }
    public void removeConnectionListener(Runnable listener) { listeners.remove(listener); }

    public void requestScopes(Set<String> packages) {
        if (preferences == null || !preferences.getBoolean(ScopeTargets.SYNC_KEY, false)) return;
        Set<String> requested = new LinkedHashSet<>(packages);
        ScopeRequest previous = pending;
        if (previous != null) requested.addAll(previous.packages);
        requested.retainAll(ScopeTargets.enabledPackages(feature -> preferences.getBoolean(feature.key, false)));
        if (requested.isEmpty() || (previous != null && previous.packages.equals(requested))) return;
        ScopeRequest request = new ScopeRequest(service, Set.copyOf(requested));
        pending = request;
        worker.execute(() -> submitScopeRequest(request));
    }

    public void forgetScopeRequest() {
        pending = null;
    }

    private void submitScopeRequest(ScopeRequest request) {
        if (pending != request) return;
        try {
            List<String> missing = ScopeTargets.missing(request.packages, request.service.getScope(), this::isInstalled);
            if (pending != request) return;
            if (missing.isEmpty()) {
                finishScopeRequest(request, true);
                return;
            }
            request.service.requestScope(missing, new XposedService.OnScopeEventListener() {
                @Override public void onScopeRequestApproved(List<String> approved) {
                    finishScopeRequest(request, true);
                }

                @Override public void onScopeRequestFailed(String message) {
                    finishScopeRequest(request, false);
                }
            });
            main.post(() -> {
                if (pending == request) Toast.makeText(this, "请在 LSPosed 通知中确认作用域申请", Toast.LENGTH_SHORT).show();
            });
        } catch (RuntimeException error) {
            Log.w("HYPERNOVA", "Could not request module scope", error);
            finishScopeRequest(request, false);
        }
    }

    private void finishScopeRequest(ScopeRequest request, boolean completed) {
        main.post(() -> {
            if (pending != request) return;
            pending = null;
            if (!completed) Toast.makeText(this, "作用域申请未通过", Toast.LENGTH_SHORT).show();
        });
    }

    private boolean isInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    private record ScopeRequest(XposedService service, Set<String> packages) { }
}
