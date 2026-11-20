// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.power;

/** Trigger once per unplugged low-battery interval, without fighting manual mode changes. */
public final class PowerSaveRule {
    private boolean handled;

    public void reset() { handled = false; }

    public boolean observeRecovery(int threshold, int level, int scale, int plugged) {
        boolean recovered = valid(threshold, level, scale, plugged)
                && (plugged != 0 || level * 100L >= threshold * (long) scale);
        if (recovered) reset();
        return recovered;
    }

    public boolean shouldEnable(boolean enabled, int threshold, int level, int scale, int plugged, boolean saving) {
        if (!enabled) { reset(); return false; }
        if (!valid(threshold, level, scale, plugged) || observeRecovery(threshold, level, scale, plugged)) return false;
        if (handled) return false;
        handled = true;
        return !saving;
    }

    private static boolean valid(int threshold, int level, int scale, int plugged) {
        return threshold >= 20 && threshold <= 80 && scale > 0 && level >= 0 && level <= scale && plugged >= 0;
    }
}
