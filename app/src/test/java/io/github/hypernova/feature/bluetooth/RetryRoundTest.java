// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Connect Colours, Copyright (C) 2026 UltraURS and contributors.
package io.github.hypernova.feature.bluetooth;

import org.junit.Test;
import static org.junit.Assert.*;

public class RetryRoundTest {
    @Test
    public void defaultOneAttemptStopsAfterFifteenSecondsAndIgnoresFailureBroadcasts() {
        RetryRound round = new RetryRound(1, 0, false);
        assertTrue(round.poll(0, false));
        round.disconnected(500);
        assertFalse(round.poll(14_999, false));
        assertFalse(round.poll(15_000, false));
        assertEquals(RetryRound.State.EXHAUSTED, round.state());
        round.disconnected(20_000);
        assertFalse(round.poll(100_000, false));
        assertEquals(1, round.attempts());
    }

    @Test
    public void fiveAttemptsRespectTimeoutAndInterval() {
        RetryRound round = new RetryRound(5, 0, false);
        for (int attempt = 0; attempt < 5; attempt++) {
            long start = attempt * 25_000L;
            assertTrue(round.poll(start, false));
            assertFalse(round.poll(start + 14_999, false));
            assertFalse(round.poll(start + 15_000, false));
            assertFalse(round.poll(start + 24_999, false));
        }
        assertEquals(5, round.attempts());
        assertEquals(Long.MAX_VALUE, round.nextDeadline());
        assertFalse(round.poll(999_999, false));
    }

    @Test
    public void realConnectionThenDisconnectionStartsNewRound() {
        RetryRound round = new RetryRound(1, 0, false);
        assertTrue(round.poll(0, false));
        assertFalse(round.poll(100, true));
        assertFalse(round.poll(5_100, true));
        assertEquals(Long.MAX_VALUE, round.nextDeadline());
        round.disconnected(6_000);
        assertFalse(round.poll(6_000, false));
        assertTrue(round.poll(16_000, false));
        assertEquals(1, round.attempts());
    }

    @Test
    public void manualPauseAndSeparateDevicesDoNotInterfere() {
        RetryRound watch = new RetryRound(2, 0, true);
        RetryRound headphones = new RetryRound(2, 0, false);
        watch.pause();
        watch.disconnected(100);
        assertFalse(watch.poll(100, false));
        assertFalse(watch.poll(200, true));
        assertEquals(RetryRound.State.PAUSED, watch.state());
        assertTrue(headphones.poll(100, false));
    }

    @Test
    public void immediateFailureWaitsTenSecondsBeforeNextRequest() {
        RetryRound round = new RetryRound(2, 0, false);
        assertTrue(round.poll(0, false));
        round.failed(100);
        assertFalse(round.poll(10_099, false));
        assertTrue(round.poll(10_100, false));
        round.failed(10_101);
        assertEquals(RetryRound.State.EXHAUSTED, round.state());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMoreThanFiveAttempts() { new RetryRound(6, 0, false); }
}
