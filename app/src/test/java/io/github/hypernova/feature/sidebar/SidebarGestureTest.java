// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.sidebar;

import org.junit.Test;
import static org.junit.Assert.*;

public class SidebarGestureTest {
    @Test public void defaultCountsThreeHundredMillisFromNativeFeedback() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(true);
        assertFalse(gesture.advance(1000, true));
        gesture.feedback(1500);
        assertEquals(300, gesture.remainingMillis(1500));
        assertFalse(gesture.advance(1799, true));
        assertTrue(gesture.advance(1800, true));
        assertTrue(gesture.release(1800));
    }

    @Test public void everySliderValueTriggersAtItsOwnFeedbackDeadline() {
        for (int duration = 100; duration <= 500; duration += 10) {
            SidebarGesture gesture = new SidebarGesture(duration);
            gesture.begin(true); gesture.feedback(1000);
            assertFalse(gesture.advance(1000 + duration - 1, true));
            assertEquals(1, gesture.remainingMillis(1000 + duration - 1));
            assertTrue(gesture.advance(1000 + duration, true));
        }
    }

    @Test public void invalidConfigurationIsLimitedToRequestedRange() {
        assertEquals(100, SidebarGesture.clampHoldMillis(Integer.MIN_VALUE));
        assertEquals(500, SidebarGesture.clampHoldMillis(Integer.MAX_VALUE));
        SidebarGesture low = new SidebarGesture(0);
        low.begin(true); low.feedback(0);
        assertFalse(low.advance(99, true)); assertTrue(low.advance(100, true));
        SidebarGesture high = new SidebarGesture(1000);
        high.begin(false); high.feedback(0);
        assertFalse(high.advance(499, true)); assertTrue(high.advance(500, true));
    }

    @Test public void arrowPositionAloneNeverStartsTheTimerWithoutFeedback() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(true);
        for (long now : new long[]{0, 300, 1000, 100000}) {
            assertFalse(gesture.advance(now, true));
            assertEquals(-1, gesture.remainingMillis(now));
        }
        assertFalse(gesture.release(100000));
    }

    @Test public void furtherInwardMovementDoesNotRestartTheFeedbackClock() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(true); gesture.feedback(1000);
        assertFalse(gesture.advance(1100, true));
        assertFalse(gesture.advance(1200, true));
        assertFalse(gesture.advance(1299, true));
        assertTrue(gesture.advance(1300, true));
    }

    @Test public void duplicateFeedbackDoesNotExtendTheDeadlineOrRepeatReadiness() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(true); gesture.feedback(1000); gesture.feedback(1250);
        assertEquals(50, gesture.remainingMillis(1250));
        assertTrue(gesture.advance(1300, true));
        gesture.feedback(1400);
        assertFalse(gesture.advance(1700, true));
        assertTrue(gesture.release(1700));
    }

    @Test public void feedbackBeforeNativeGestureRecognitionIsIgnored() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.feedback(1000);
        assertEquals(SidebarGesture.State.IDLE, gesture.state());
        gesture.begin(false);
        assertEquals(-1, gesture.remainingMillis(2000));
        assertFalse(gesture.advance(2000, true));
    }

    @Test public void leavingBackPositionCancelsRatherThanRestartingWithoutAnotherTouch() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(true); gesture.feedback(1000);
        assertFalse(gesture.advance(1250, false));
        assertEquals(SidebarGesture.State.CANCELED, gesture.state());
        gesture.feedback(2000); gesture.begin(true);
        assertFalse(gesture.advance(2300, true));
        assertEquals(-1, gesture.remainingMillis(2300));
        assertFalse(gesture.release(2300));
    }

    @Test public void timerAndMotionCallbacksProduceOneIconChangeAndOneLaunch() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(true); gesture.feedback(1000);
        assertTrue(gesture.advance(1300, true));
        assertFalse(gesture.advance(1300, true)); assertFalse(gesture.advance(1400, true));
        assertEquals(-1, gesture.remainingMillis(1400));
        assertTrue(gesture.release(1400)); assertFalse(gesture.release(1400));
    }

    @Test public void reverseDuringCountdownOrAfterReadyCancelsTheWholeTouch() {
        for (boolean ready : new boolean[]{false, true}) {
            SidebarGesture gesture = new SidebarGesture();
            gesture.begin(true); gesture.feedback(0);
            if (ready) gesture.advance(300, true);
            gesture.reverse();
            assertEquals(SidebarGesture.State.CANCELED, gesture.state());
            gesture.begin(true); gesture.feedback(1000);
            assertFalse(gesture.advance(2000, true));
            assertFalse(gesture.release(2000));
        }
    }

    @Test public void quickReleasePreservesOrdinaryBackInsteadOfOpeningSidebar() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(false); gesture.feedback(1000);
        assertFalse(gesture.advance(1299, true));
        assertFalse(gesture.release(1299));
        assertFalse(gesture.advance(1300, true));
    }

    @Test public void interruptionCancelsWaitingHoldingAndReadyOnBothEdges() {
        for (boolean left : new boolean[]{true, false}) {
            for (int phase = 0; phase < 3; phase++) {
                SidebarGesture gesture = new SidebarGesture();
                gesture.begin(left);
                if (phase >= 1) gesture.feedback(1000);
                if (phase == 2) gesture.advance(1300, true);
                gesture.cancel();
                assertEquals(-1, gesture.remainingMillis(2000));
                gesture.feedback(2000);
                assertFalse(gesture.advance(3000, true)); assertFalse(gesture.release(3000));
            }
        }
    }

    @Test public void bothEdgesHaveSameTimingAndKeepTheirDirection() {
        for (boolean left : new boolean[]{true, false}) {
            SidebarGesture gesture = new SidebarGesture();
            gesture.begin(left); gesture.feedback(0);
            assertEquals(left, gesture.fromLeft());
            assertTrue(gesture.advance(300, true)); assertTrue(gesture.release(300));
            assertEquals(left, gesture.fromLeft());
        }
    }

    @Test public void newTouchDoesNotInheritFeedbackReadinessOrElapsedTime() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(true); gesture.feedback(0); gesture.advance(300, true);
        gesture.reset(); gesture.begin(false);
        assertFalse(gesture.advance(1000, true)); assertEquals(-1, gesture.remainingMillis(1000));
        gesture.feedback(1100);
        assertFalse(gesture.advance(1399, true));
        assertTrue(gesture.advance(1400, true));
        assertFalse(gesture.fromLeft());
    }

    @Test public void lateUiTimerDoesNotConvertAnEarlierQuickReleaseIntoLaunch() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(true); gesture.feedback(1000);
        assertTrue(gesture.advance(1500, true));
        assertFalse(gesture.release(1299));
        assertFalse(gesture.release(1500));
    }

    @Test public void feedbackAfterReleaseOrCancelCannotStartANewCountdown() {
        SidebarGesture gesture = new SidebarGesture();
        gesture.begin(true); assertFalse(gesture.release(1000));
        gesture.feedback(1001);
        assertFalse(gesture.advance(2000, true));
        gesture.reset(); gesture.begin(false); gesture.cancel(); gesture.feedback(3000);
        assertFalse(gesture.advance(4000, true)); assertFalse(gesture.release(4000));
    }
}
