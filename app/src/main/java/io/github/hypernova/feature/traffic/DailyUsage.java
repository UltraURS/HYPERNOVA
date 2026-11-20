// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Connect Colours, Copyright (C) 2026 UltraURS and contributors.
package io.github.hypernova.feature.traffic;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

/** Calendar-day boundaries, including timezone and daylight-saving changes. */
public final class DailyUsage {
    private DailyUsage() {}

    public static long startOfDay(long now, ZoneId zone) {
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli();
    }

    public static String label(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative usage");
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "今日 %.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.ROOT, "今日 %.1f MB", bytes / (1024.0 * 1024));
        }
        if (bytes >= 1024) {
            return String.format(Locale.ROOT, "今日 %.1f KB", bytes / 1024.0);
        }
        return "今日 " + bytes + " B";
    }
}
