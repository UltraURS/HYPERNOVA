package io.github.hypernova.feature.traffic;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.*;

public class UsageSnapshotTest {
    private final ZoneId zone = ZoneId.of("Asia/Hong_Kong");
    private final long end = Instant.parse("2026-11-16T15:59:55Z").toEpochMilli();
    private UsageSnapshot sample() { return new UsageSnapshot(DailyUsage.startOfDay(end, zone), end, 1000, zone, 123); }
    @Test public void validOnlyWithinSameDayZoneAndFreshWindow() {
        assertTrue(sample().valid(end + 1000, 2000, zone));
        assertFalse(sample().valid(end + 6000, 7000, zone));
        assertFalse(sample().valid(end, 1000, ZoneId.of("UTC")));
        assertFalse(sample().valid(end, 31_000, zone));
    }
    @Test public void rejectsClockRollbackAndMonotonicReset() {
        assertFalse(sample().valid(end - 1, 2000, zone));
        assertFalse(sample().valid(end, 999, zone));
    }
    @Test public void longTotalsRemainExact() { assertEquals(6_000_000_000L, UsageSnapshot.total(4_000_000_000L, 2_000_000_000L)); }
    @Test(expected = ArithmeticException.class) public void overflowIsNotDisplayed() { UsageSnapshot.total(Long.MAX_VALUE, 1); }
    @Test(expected = IllegalArgumentException.class) public void negativeBucketIsNotDisplayed() { UsageSnapshot.total(-1, 10); }
}
