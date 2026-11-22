// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.ui;

import io.github.hypernova.config.Feature;
import org.junit.Test;
import static org.junit.Assert.*;

public class FeatureSearchModeTest {
    @Test public void nameAndDescriptionAreIndependentlySearchable() {
        Feature feature = Feature.STK_WELCOME;
        assertTrue(FeatureSearchMode.NAME.matches(feature, "运营商欢迎弹窗"));
        assertFalse(FeatureSearchMode.DESCRIPTION.matches(feature, "运营商欢迎弹窗"));
        assertFalse(FeatureSearchMode.NAME.matches(feature, "usim"));
        assertTrue(FeatureSearchMode.DESCRIPTION.matches(feature, "  usim  "));
        assertTrue(FeatureSearchMode.ALL.matches(feature, "运营商欢迎弹窗"));
        assertTrue(FeatureSearchMode.ALL.matches(feature, "usim"));
    }

    @Test public void searchesEachSwitchIncludingIdenticalNamesInDifferentPackages() {
        for (Feature feature : Feature.values()) {
            assertTrue(feature.key, FeatureSearchMode.NAME.matches(feature, feature.title));
            assertTrue(feature.key, FeatureSearchMode.DESCRIPTION.matches(feature, feature.description));
        }
        assertTrue(FeatureSearchMode.NAME.matches(Feature.SYSTEM_HELPER_APP_STATS, "停用应用统计"));
        assertTrue(FeatureSearchMode.NAME.matches(Feature.REG_SERVICE_APP_STATS, "停用应用统计"));
    }

    @Test public void emptyAndUnrelatedQueriesDoNotMatch() {
        for (FeatureSearchMode mode : FeatureSearchMode.values()) {
            assertFalse(mode.matches(Feature.STK_WELCOME, " \t"));
            assertFalse(mode.matches(Feature.STK_WELCOME, "蓝牙"));
        }
    }
}
