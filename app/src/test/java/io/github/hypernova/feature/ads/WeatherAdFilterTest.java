// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.ads;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class WeatherAdFilterTest {
    @Test public void mixedCachedCardsKeepOrdinaryContentAndOrder() {
        List<String> cached = List.of("2", "7", "3", "10", "6", "11");
        assertEquals(List.of("2", "3", "6", "11"), WeatherAdFilter.withoutAds(cached, value -> value));
        assertEquals(List.of("2", "7", "3", "10", "6", "11"), cached);
    }

    @Test public void allAdCardBecomesEmptySoHostSkipsItsContainer() {
        assertTrue(WeatherAdFilter.withoutAds(List.of("7", "10", "7"), value -> value).isEmpty());
    }

    @Test public void ordinaryCardRetainsItsListAndUnrecognizedTypes() {
        List<String> ordinary = Arrays.asList("1", "17", "107", "unknown", null);
        assertSame(ordinary, WeatherAdFilter.withoutAds(ordinary, value -> value));
    }

    @Test public void filteringPreservesHostNodeIdentityAndNullableItems() {
        Object first = new Object(), ad = new Object(), last = new Object();
        List<Object> filtered = WeatherAdFilter.withoutAds(Arrays.asList(first, ad, null, last),
            node -> node == ad ? "7" : "3");
        assertEquals(3, filtered.size());
        assertSame(first, filtered.get(0)); assertNull(filtered.get(1)); assertSame(last, filtered.get(2));
    }

    @Test public void absentCardsDoNotRequireTypeLookup() {
        assertNull(WeatherAdFilter.withoutAds(null, value -> { throw new AssertionError(); }));
        List<Object> empty = List.of();
        assertSame(empty, WeatherAdFilter.withoutAds(empty, value -> { throw new AssertionError(); }));
    }
}
