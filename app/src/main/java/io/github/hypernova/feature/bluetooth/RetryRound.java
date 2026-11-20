// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Connect Colours, Copyright (C) 2026 UltraURS and contributors.
package io.github.hypernova.feature.bluetooth;

/** One bounded reconnection round, driven by elapsed realtime. */
public final class RetryRound {
    public enum State { READY, WAITING, VERIFYING, DELAY, CONNECTED, EXHAUSTED, PAUSED }
    public static final long TIMEOUT = 15_000;
    public static final long INTERVAL = 10_000;
    public static final long STABLE = 5_000;
    private static final long CHECK_INTERVAL = 1_000;
    private State state;
    private int attempts;
    private final int limit;
    private long deadline;
    private long stableSince;
    private long checkedAt;

    public RetryRound(int limit, long now, boolean connected) {
        if (limit < 1 || limit > 5) throw new IllegalArgumentException("Invalid attempt limit");
        this.limit = limit;
        state = connected ? State.VERIFYING : State.READY;
        deadline = now;
        stableSince = now;
        checkedAt = now;
    }

    public boolean poll(long now, boolean connected) {
        return poll(now, connected, false);
    }

    public boolean poll(long now, boolean connected, boolean busy) {
        checkedAt = now;
        if (state == State.PAUSED) return false;
        if (connected && (state == State.CONNECTED || !busy)) {
            if (state != State.CONNECTED && state != State.VERIFYING) {
                state = State.VERIFYING;
                stableSince = now;
            }
            if (state == State.VERIFYING && now - stableSince >= STABLE) state = State.CONNECTED;
            return false;
        }
        // A profile can disappear while its ACL link remains; no ACL broadcast is required.
        disconnected(now);
        if (state == State.WAITING && now >= deadline) {
            failed(now);
        }
        if ((state == State.READY || state == State.DELAY) && now >= deadline) {
            if (busy) {
                // Let the system's pending connect/disconnect finish before issuing another request.
                deadline = now + INTERVAL;
                return false;
            }
            attempts++;
            state = State.WAITING;
            deadline = now + TIMEOUT;
            return true;
        }
        return false;
    }

    public void disconnected(long now) {
        // Failed connection broadcasts cannot reset a round or its exhausted budget.
        if (state == State.CONNECTED) {
            attempts = 0;
            state = State.DELAY;
            deadline = now + INTERVAL;
        } else if (state == State.VERIFYING) {
            if (attempts == 0) {
                state = State.DELAY;
                deadline = now + INTERVAL;
            } else state = State.WAITING;
        }
    }

    public void pause() { state = State.PAUSED; }
    public void failed(long now) {
        if (state != State.WAITING) return;
        state = attempts >= limit ? State.EXHAUSTED : State.DELAY;
        deadline = now + INTERVAL;
    }
    public State state() { return state; }
    public int attempts() { return attempts; }
    public long nextDeadline() {
        if (state == State.VERIFYING) return Math.min(stableSince + STABLE, checkedAt + CHECK_INTERVAL);
        if (state == State.WAITING) return Math.min(deadline, checkedAt + CHECK_INTERVAL);
        return state == State.READY || state == State.DELAY ? deadline : Long.MAX_VALUE;
    }
}
