// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.config;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScopeTargetsTest {
    @Test public void includesAllEnabledFeaturePackagesOnce() {
        Set<Feature> selected = Set.of(Feature.SCREENSHOT_MENU, Feature.BLUETOOTH_RECONNECT, Feature.TRAFFIC);
        assertEquals(Set.of("com.android.systemui", "com.android.settings", "com.miui.screenshot"),
            ScopeTargets.enabledPackages(selected::contains));
        assertTrue(ScopeTargets.enabledPackages(feature -> false).isEmpty());
    }

    @Test public void requestsOnlyMissingInstalledPackages() {
        Set<String> targets = Set.of("com.android.systemui", "com.miui.screenshot", "com.miui.home", "com.android.updater");
        assertEquals(List.of("com.miui.home", "com.miui.screenshot"),
            ScopeTargets.missing(targets, Set.of("com.android.systemui"),
                name -> !name.equals("com.android.updater")));
        assertTrue(ScopeTargets.missing(targets, targets, name -> true).isEmpty());
    }
}
