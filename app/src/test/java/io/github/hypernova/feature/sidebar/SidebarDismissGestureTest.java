// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.sidebar;

import org.junit.Test;
import static org.junit.Assert.*;

public class SidebarDismissGestureTest {
    private SidebarDismissGesture gesture() { return new SidebarDismissGesture(8, 24, 500); }

    @Test public void rightPanelDismissesToRightOnlyOnRelease() {
        var g = gesture(); g.begin(false, 100, 100, 0);
        assertFalse(g.move(123, 100, 50, 1)); assertTrue(g.move(124, 100, 60, 1));
        assertTrue(g.release(140, 105, 1)); assertFalse(g.release(140, 105, 1));
    }
    @Test public void leftPanelUsesMirroredPhysicalDirection() {
        var g = gesture(); g.begin(true, 100, 100, 0);
        assertTrue(g.move(76, 100, 60, 1)); assertTrue(g.release(60, 105, 1));
    }
    @Test public void inwardSwipeDoesNotLaterBecomeDismissal() {
        for (boolean left : new boolean[]{true, false}) {
            var g = gesture(); g.begin(left, 100, 100, 0);
            assertFalse(g.move(left ? 110 : 90, 100, 60, 1));
            assertFalse(g.move(left ? 20 : 180, 100, 100, 1));
            assertFalse(g.release(left ? 20 : 180, 100, 1));
        }
    }
    @Test public void verticalScrollStaysVerticalEvenAfterOutwardMovement() {
        var g = gesture(); g.begin(false, 100, 100, 0);
        assertFalse(g.move(102, 110, 50, 1)); assertFalse(g.move(160, 115, 100, 1));
        assertFalse(g.release(160, 115, 1));
    }
    @Test public void diagonalMovementNeedsClearHorizontalIntent() {
        var g = gesture(); g.begin(false, 100, 100, 0);
        assertFalse(g.move(124, 120, 50, 1)); assertTrue(g.move(145, 120, 100, 1));
        assertTrue(g.release(145, 120, 1));
    }
    @Test public void tapsAndJitterRemainAvailableToApps() {
        var g = gesture(); g.begin(false, 100, 100, 0);
        assertFalse(g.move(105, 102, 50, 1)); assertFalse(g.release(105, 102, 1));
    }
    @Test public void longPressIsNotStolenFromAppDragging() {
        var g = gesture(); g.begin(false, 100, 100, 0);
        assertFalse(g.move(150, 100, 500, 1)); assertFalse(g.release(150, 100, 1));
    }
    @Test public void multiTouchCancelsBeforeAndAfterInterception() {
        var g = gesture(); g.begin(false, 100, 100, 0);
        assertFalse(g.move(150, 100, 50, 2)); assertFalse(g.release(150, 100, 1));
        g.begin(false, 100, 100, 100); assertTrue(g.move(150, 100, 150, 1));
        assertTrue(g.move(160, 100, 200, 2)); assertTrue(g.captured());
        assertFalse(g.release(160, 100, 1));
    }
    @Test public void reversalAndCancellationDoNotCloseOrClick() {
        var g = gesture(); g.begin(false, 100, 100, 0);
        assertTrue(g.move(150, 100, 50, 1)); assertFalse(g.release(110, 100, 1));
        g.begin(false, 100, 100, 100); assertTrue(g.move(150, 100, 150, 1));
        g.cancel(); assertTrue(g.captured()); assertFalse(g.release(150, 100, 1));
    }
    @Test public void newTouchResetsCancelledOrCapturedGesture() {
        var g = gesture(); g.begin(false, 100, 100, 0); g.cancel();
        g.begin(true, 100, 100, 100); assertTrue(g.move(50, 100, 150, 1));
        g.reset(); assertFalse(g.captured()); assertFalse(g.release(50, 100, 1));
    }
}
