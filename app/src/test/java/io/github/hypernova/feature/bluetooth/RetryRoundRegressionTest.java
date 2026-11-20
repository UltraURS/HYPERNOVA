// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.bluetooth;

import org.junit.Test;
import static org.junit.Assert.*;

public class RetryRoundRegressionTest {
    @Test
    public void briefProfileConnectionIsNotReportedAsSuccess() {
        RetryRound round = new RetryRound(3, 0, false);
        assertTrue(round.poll(0, false));
        assertFalse(round.poll(100, true));
        assertNotEquals(RetryRound.State.CONNECTED, round.state());
        assertNotEquals(Long.MAX_VALUE, round.nextDeadline());
    }

    @Test
    public void connectionThatDropsDuringConfirmationKeepsItsAttemptBudget() {
        RetryRound round = new RetryRound(3, 0, false);
        assertTrue(round.poll(0, false));
        round.poll(100, true);
        round.disconnected(800);
        assertFalse(round.poll(800, false));
        assertEquals(1, round.attempts());
        round.poll(15_000, false);
        assertFalse(round.poll(24_999, false));
        assertTrue(round.poll(25_000, false));
        assertEquals(2, round.attempts());
    }

    @Test
    public void missingAclDisconnectDoesNotLeaveTheRoundPermanentlyConnected() {
        RetryRound round = new RetryRound(1, 0, true);
        round.poll(5_000, true);
        assertEquals(RetryRound.State.CONNECTED, round.state());
        assertFalse(round.poll(6_000, false));
        assertNotEquals(Long.MAX_VALUE, round.nextDeadline());
        assertFalse(round.poll(15_999, false));
        assertTrue(round.poll(16_000, false));
    }

    @Test
    public void repeatedFlashesCannotRestartAttemptOneForever() {
        RetryRound round = new RetryRound(2, 0, false);
        for (int attempt = 0; attempt < 2; attempt++) {
            long start = attempt * 25_000L;
            assertTrue(round.poll(start, false));
            round.poll(start + 100, true);
            round.disconnected(start + 500);
            round.poll(start + 15_000, false);
        }
        assertEquals(2, round.attempts());
        assertEquals(RetryRound.State.EXHAUSTED, round.state());
        assertFalse(round.poll(100_000, false));
    }

    @Test
    public void confirmationAtTheAttemptDeadlineStillRequiresFiveStableSeconds() {
        RetryRound round = new RetryRound(1, 0, false);
        round.poll(0, false);
        round.poll(14_900, true);
        assertNotEquals(RetryRound.State.CONNECTED, round.state());
        round.poll(19_899, true);
        assertNotEquals(RetryRound.State.CONNECTED, round.state());
        round.poll(19_900, true);
        assertEquals(RetryRound.State.CONNECTED, round.state());
    }

    @Test
    public void pendingSystemConnectionDefersWithoutIssuingOrConsumingAnAttempt() {
        RetryRound round = new RetryRound(2, 0, false);
        assertFalse(round.poll(0, false, true));
        assertEquals(0, round.attempts());
        assertEquals(10_000, round.nextDeadline());
        assertFalse(round.poll(10_000, false, true));
        assertEquals(0, round.attempts());
        assertTrue(round.poll(20_000, false, false));
        assertEquals(1, round.attempts());
    }

    @Test
    public void partialConnectionDoesNotConfirmWhileAnotherProfileIsTransitioning() {
        RetryRound round = new RetryRound(1, 0, false);
        round.poll(0, false);
        round.poll(100, true, false);
        round.poll(1_000, true, true);
        assertNotEquals(RetryRound.State.CONNECTED, round.state());
        round.poll(2_000, true, false);
        round.poll(6_999, true, false);
        assertNotEquals(RetryRound.State.CONNECTED, round.state());
        round.poll(7_000, true, false);
        assertEquals(RetryRound.State.CONNECTED, round.state());
    }

    @Test
    public void profileLossBeforeTheWorkerRunsResetsConfirmationEvenIfAlreadyReconnected() {
        RetryRound round = new RetryRound(1, 0, false);
        round.poll(0, false);
        round.poll(100, true);
        round.disconnected(4_900);
        round.poll(5_100, true);
        assertNotEquals(RetryRound.State.CONNECTED, round.state());
        round.poll(10_100, true);
        assertEquals(RetryRound.State.CONNECTED, round.state());
    }

    @Test
    public void pauseDuringConfirmationPreventsSuccessAndFurtherRequests() {
        RetryRound round = new RetryRound(1, 0, false);
        round.poll(0, false);
        round.poll(100, true);
        round.pause();
        round.poll(10_000, true);
        assertEquals(RetryRound.State.PAUSED, round.state());
        assertEquals(Long.MAX_VALUE, round.nextDeadline());
    }
}
