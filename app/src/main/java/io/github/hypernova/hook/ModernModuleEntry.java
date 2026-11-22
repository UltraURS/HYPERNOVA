// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.hook;

import io.github.hypernova.config.Feature;
import io.github.libxposed.api.XposedModule;

public final class ModernModuleEntry extends XposedModule {
    private String processName;

    @Override public void onModuleLoaded(ModuleLoadedParam module) {
        processName = module.getProcessName();
    }

    @Override public void onPackageReady(PackageReadyParam pkg) {
        ModuleEntry.install(pkg.getPackageName(), processName, pkg.getClassLoader(),
            () -> getRemotePreferences(Feature.PREFS));
    }
}
