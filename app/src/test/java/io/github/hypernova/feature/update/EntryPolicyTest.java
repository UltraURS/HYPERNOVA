package io.github.hypernova.feature.update;

import org.junit.Test;
import static org.junit.Assert.*;

public class EntryPolicyTest {
    @Test public void switchesAreIndependent() {
        assertTrue(EntryPolicy.hide("miui_updater", true, false));
        assertFalse(EntryPolicy.hide("factory_reset_key", true, false));
        assertTrue(EntryPolicy.hide("factory_reset_key", false, true));
        assertFalse(EntryPolicy.hide("miui_updater", false, true));
        assertFalse(EntryPolicy.hide("miui_updater", false, false));
        assertFalse(EntryPolicy.hide("factory_reset_key", false, false));
    }
    @Test public void factoryResetRecommendationUsesDestinationNotTitle() {
        assertTrue(EntryPolicy.hideResetRecommendation("com.android.settings", "com.android.settings.SubSettings", "com.android.settings.MiuiMasterClear"));
        assertFalse(EntryPolicy.hideResetRecommendation("com.android.settings", "com.android.settings.SubSettings", "com.android.settings.ResetNetwork"));
        assertFalse(EntryPolicy.hideResetRecommendation("com.android.settings", "com.android.settings.SubSettings", "com.android.settings.backup.PrivacySettings"));
        assertFalse(EntryPolicy.hideResetRecommendation("other.app", "com.android.settings.SubSettings", "com.android.settings.MiuiMasterClear"));
        assertFalse(EntryPolicy.hideResetRecommendation("com.android.settings", "other.app.SubSettings", "com.android.settings.MiuiMasterClear"));
        assertFalse(EntryPolicy.hideResetRecommendation(null, null, null));
        assertFalse(EntryPolicy.hideResetRecommendation("com.android.settings", "com.android.settings.SubSettings", "com.android.settings.MiuiMasterClearSuffix"));
    }
    @Test public void resetSearchUsesExplicitDestinationOrExactOwnedKey() {
        assertTrue(EntryPolicy.hideResetSearch("com.android.settings", "factory_reset_key", null, null, null));
        assertTrue(EntryPolicy.hideResetSearch(null, null, "com.android.settings", "com.android.settings.SubSettings", "com.android.settings.MiuiMasterClear"));
        assertFalse(EntryPolicy.hideResetSearch("other.app", "factory_reset_key", null, null, null));
        assertFalse(EntryPolicy.hideResetSearch("com.android.settings", "cloud_backup", "com.android.settings", "com.android.settings.SubSettings", "com.android.settings.backup.PrivacySettings"));
        assertFalse(EntryPolicy.hideResetSearch("com.android.settings", "reset_network", null, null, null));
        assertFalse(EntryPolicy.hideResetSearch(null, null, null, null, null));
    }
    @Test public void unrelatedSettingsArePreserved() {
        for (String key : new String[]{null, "", "firmware_version", "security_patch", "network_reset", "reset_app_preferences", "factory_reset", "system_app_update", "prefix_miui_updater"})
            assertFalse(EntryPolicy.hide(key, true, true));
    }
}
