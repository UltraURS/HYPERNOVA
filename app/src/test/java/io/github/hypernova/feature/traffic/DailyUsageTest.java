package io.github.hypernova.feature.traffic;

import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import static org.junit.Assert.*;

public class DailyUsageTest {
    @Test public void midnightRespectsLocalDateAndDst() {
        ZoneId zone = ZoneId.of("America/New_York");
        long spring = Instant.parse("2026-03-09T03:59:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-03-08T05:00:00Z").toEpochMilli(), DailyUsage.startOfDay(spring, zone));
        long fall = Instant.parse("2026-11-02T04:59:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-11-01T04:00:00Z").toEpochMilli(), DailyUsage.startOfDay(fall, zone));
    }
    @Test public void newYearUsesNewLocalDay() {
        long now = Instant.parse("2025-12-31T16:01:00Z").toEpochMilli();
        assertEquals(Instant.parse("2025-12-31T16:00:00Z").toEpochMilli(), DailyUsage.startOfDay(now, ZoneId.of("Asia/Hong_Kong")));
    }
    @Test public void labelsUseBinaryUnitsAndLongCounters() {
        assertEquals("今日 0 B", DailyUsage.label(0));
        assertEquals("今日 1023 B", DailyUsage.label(1023));
        assertEquals("今日 1.0 KB", DailyUsage.label(1024));
        assertEquals("今日 1.0 MB", DailyUsage.label(1024L * 1024));
        assertEquals("今日 5.00 GB", DailyUsage.label(5L * 1024 * 1024 * 1024));
        assertTrue(DailyUsage.label(Long.MAX_VALUE).endsWith(" GB"));
    }
    @Test(expected = IllegalArgumentException.class) public void failureIsNotZero() { DailyUsage.label(-1); }
}
