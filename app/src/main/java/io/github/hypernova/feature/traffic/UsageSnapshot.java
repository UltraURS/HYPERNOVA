package io.github.hypernova.feature.traffic;

import java.time.ZoneId;

public final class UsageSnapshot {
    public final long start, end, elapsed, bytes;
    public final ZoneId zone;
    public UsageSnapshot(long start, long end, long elapsed, ZoneId zone, long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative usage");
        this.start = start; this.end = end; this.elapsed = elapsed; this.zone = zone; this.bytes = bytes;
    }
    public boolean valid(long now, long monotonic, ZoneId currentZone) {
        return zone.equals(currentZone) && start == DailyUsage.startOfDay(now, currentZone)
            && end <= now && monotonic >= elapsed && monotonic - elapsed < 30_000;
    }
    public static long total(long rx, long tx) {
        if (rx < 0 || tx < 0) throw new IllegalArgumentException("Negative bucket");
        return Math.addExact(rx, tx);
    }
}
