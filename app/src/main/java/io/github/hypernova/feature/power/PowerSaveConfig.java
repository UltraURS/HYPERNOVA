// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.power;

import android.content.Context;
import android.net.Uri;
import android.provider.Settings;

public record PowerSaveConfig(boolean enabled, int threshold) {
    private static final String KEY = "hypernova_power_auto";
    public static final Uri URI = Settings.Secure.getUriFor(KEY);

    public PowerSaveConfig {
        if (threshold < 20 || threshold > 80) throw new IllegalArgumentException("Invalid battery threshold");
    }

    public static PowerSaveConfig read(Context context) {
        String stored = Settings.Secure.getString(context.getContentResolver(), KEY);
        int value = stored == null ? -20 : Integer.parseInt(stored);
        return new PowerSaveConfig(value > 0, Math.abs(value));
    }

    public void save(Context context) {
        if (!Settings.Secure.putString(context.getContentResolver(), KEY,
                Integer.toString(enabled ? threshold : -threshold))) {
            throw new IllegalStateException("Automatic power saver configuration could not be saved");
        }
    }

    public static boolean isConfigChange(Uri uri) {
        return uri == null || URI.equals(uri) || Settings.Secure.CONTENT_URI.equals(uri);
    }
}
