// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationSwipeTest {
    private final NavigationSwipe swipe = new NavigationSwipe(16, 64);

    @Test public void horizontalSwipesSelectAdjacentPages() {
        swipe.start(200, 200, true);
        assertTrue(swipe.intercept(160, 203));
        assertEquals(-40f, swipe.offset(160), 0f);
        assertTrue(swipe.finish(100));
        swipe.start(200, 200, true);
        assertTrue(swipe.intercept(280, 197));
        assertEquals(80f, swipe.offset(280), 0f);
        assertTrue(swipe.finish(280));
    }

    @Test public void followsTheFingerWhenAnAcceptedDragChangesDirection() {
        swipe.start(200, 200, true);
        assertFalse(swipe.intercept(207, 201));
        assertEquals(0f, swipe.offset(207), 0f);
        assertTrue(swipe.intercept(165, 204));
        assertEquals(-35f, swipe.offset(165), 0f);
        assertTrue(swipe.intercept(200, 240));
        assertEquals(0f, swipe.offset(200), 0f);
        assertEquals(48f, swipe.offset(248), 0f);
        assertFalse(swipe.finish(248));
    }

    @Test public void verticalAndShortGesturesLeaveThePageAlone() {
        swipe.start(200, 200, true);
        assertFalse(swipe.intercept(206, 230));
        assertFalse(swipe.intercept(300, 237));
        assertFalse(swipe.finish(300));
        swipe.start(200, 200, true);
        assertTrue(swipe.intercept(240, 202));
        assertFalse(swipe.finish(222));
    }

    @Test public void edgeGesturesAndCancelledTouchesAreNotUsed() {
        swipe.start(10, 200, false);
        assertFalse(swipe.intercept(94, 205));
        assertFalse(swipe.finish(164));
        swipe.start(200, 200, true);
        assertTrue(swipe.intercept(240, 200));
        swipe.cancel();
        assertEquals(0f, swipe.offset(270), 0f);
        assertFalse(swipe.finish(270));
    }
}
