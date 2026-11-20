// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.freeform;

import org.junit.Test;
import static org.junit.Assert.*;

public class FreeformClosePlacementTest {
    @Test public void alignsWithDotsAndHasEqualTopAndRightGaps() {
        var p = FreeformClosePlacement.at(800, 1200, 3, 1, 36);
        assertNotNull(p);
        assertEquals(36, p.y() + p.size() / 2f, 0.001f);
        assertEquals(p.y(), 800 - p.x() - p.size(), 0.001f);
        assertEquals(22, p.size());
    }
    @Test public void visualTargetSizeAndEdgeInsetSurviveScaling() {
        for (float scale : new float[]{0.5f, 0.7f, 1, 1.2f}) {
            var p = FreeformClosePlacement.at(1200, 1800, 3, scale, 48);
            assertNotNull(p);
            assertEquals(22.44f, p.size() * scale, 1);
            assertEquals(48, p.y() + p.size() / 2f, 0.001f);
            assertEquals(p.y(), 1200 - p.x() - p.size(), 0.001f);
        }
    }
    @Test public void followsChangedBoundsAndCaptionCenter() {
        var before = FreeformClosePlacement.at(800, 1200, 3, 1, 36);
        var after = FreeformClosePlacement.at(1000, 800, 3, 1, 56);
        assertEquals(36, before.y() + before.size() / 2f, 0.001f);
        assertEquals(56, after.y() + after.size() / 2f, 0.001f);
        assertEquals(after.y(), 1000 - after.x() - after.size(), 0.001f);
    }
    @Test public void oddPixelIconKeepsExactCenterAndEqualGaps() {
        var p = FreeformClosePlacement.at(800, 1200, 2.8f, 1, 33);
        assertNotNull(p); assertEquals(21, p.size());
        assertEquals(33, p.y() + p.size() / 2f, 0.001f);
        assertEquals(p.y(), 800 - p.x() - p.size(), 0.001f);
    }
    @Test public void invalidOrMiniatureGeometryDoesNotCreateAnInputWindow() {
        for (float scale : new float[]{0, -1, Float.NaN, Float.POSITIVE_INFINITY})
            assertNull(FreeformClosePlacement.at(800, 1200, 3, scale, 36));
        assertNull(FreeformClosePlacement.at(0, 1200, 3, 1, 36));
        assertNull(FreeformClosePlacement.at(60, 1200, 3, 1, 36));
        assertNull(FreeformClosePlacement.at(800, 50, 3, 1, 48));
        assertNull(FreeformClosePlacement.at(800, 1200, 3, 1, 5));
        assertNull(FreeformClosePlacement.at(800, 1200, Float.NaN, 1, 36));
    }
}
