// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Connect Colours, Copyright (C) 2026 UltraURS and contributors.
package io.github.hypernova.feature.sidebar;

/** One edge gesture, driven on the gesture handler thread with monotonic time. */
public final class SidebarGesture {
    public static final String HOLD_KEY = "sidebar_hold_millis";
    public static final int MIN_HOLD_MILLIS = 100, MAX_HOLD_MILLIS = 500, DEFAULT_HOLD_MILLIS = 300;
    private final int holdMillis;

    public SidebarGesture() { this(DEFAULT_HOLD_MILLIS); }
    public SidebarGesture(int holdMillis) { this.holdMillis = clampHoldMillis(holdMillis); }
    public static int clampHoldMillis(int value) {
        return Math.max(MIN_HOLD_MILLIS, Math.min(MAX_HOLD_MILLIS, value));
    }

    public enum State { IDLE, TRACKING, HOLDING, READY, CANCELED, FINISHED }
    private State state = State.IDLE;
    private long startedAt = -1;
    private boolean left;

    /** Call after native back recognition, not on the initial stationary touch. */
    public void begin(boolean fromLeft) {
        if (state != State.IDLE && state != State.FINISHED) return;
        startedAt = -1;
        left = fromLeft;
        state = State.TRACKING;
    }

    /** Start at the native ready-back haptic, once per touch. */
    public void feedback(long now) {
        if (state != State.TRACKING) return;
        startedAt = now;
        state = State.HOLDING;
    }

    public boolean advance(long now, boolean atBackPosition) {
        if (state != State.HOLDING) return false;
        if (!atBackPosition) { cancel(); return false; }
        if (now - startedAt < holdMillis) return false;
        state = State.READY;
        return true; // Change the icon once; launch only on release.
    }

    public long remainingMillis(long now) {
        return state == State.HOLDING
            ? Math.max(0, holdMillis - (now - startedAt)) : -1;
    }

    /** Once reversed after native feedback, this touch cannot arm again. */
    public void reverse() { if (state == State.HOLDING || state == State.READY) state = State.CANCELED; }
    public void cancel() {
        if (state == State.TRACKING || state == State.HOLDING || state == State.READY) state = State.CANCELED;
    }

    /** Use the UP event time so a delayed UI timer cannot turn a quick back into a launch. */
    public boolean release(long eventTime) {
        boolean open = state == State.READY && eventTime - startedAt >= holdMillis;
        state = State.FINISHED;
        return open;
    }

    public void reset() { state = State.IDLE; startedAt = -1; }
    public State state() { return state; }
    public boolean fromLeft() { return left; }
}
