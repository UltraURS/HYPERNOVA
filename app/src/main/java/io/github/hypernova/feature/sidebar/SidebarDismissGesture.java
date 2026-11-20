// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.sidebar;

/** Classifies an outward swipe without turning vertical scrolling or a long press into dismissal. */
public final class SidebarDismissGesture {
    private final float slop, distance;
    private final long longPressMillis;
    private float downX, downY;
    private long downTime;
    private boolean left, tracking, captured, cancelled;

    public SidebarDismissGesture(float slop, float distance, long longPressMillis) {
        this.slop = slop; this.distance = distance; this.longPressMillis = longPressMillis;
    }

    public void begin(boolean left, float x, float y, long time) {
        reset(); this.left = left; downX = x; downY = y; downTime = time; tracking = true;
    }

    public boolean move(float x, float y, long time, int pointers) {
        if (!tracking || cancelled) return captured;
        if (pointers != 1 || (!captured && time - downTime >= longPressMillis)) {
            cancelled = true; return captured;
        }
        float outward = (x - downX) * (left ? -1 : 1), vertical = Math.abs(y - downY);
        if (!captured && (outward < -slop || (vertical > slop && vertical >= Math.abs(x - downX)))) {
            cancelled = true;
        } else if (outward >= distance && outward > vertical * 1.5f) captured = true;
        return captured;
    }

    public boolean release(float x, float y, int pointers) {
        float outward = (x - downX) * (left ? -1 : 1);
        boolean dismiss = tracking && captured && !cancelled && pointers == 1
            && outward >= distance && outward > Math.abs(y - downY) * 1.5f;
        reset(); return dismiss;
    }

    public boolean captured() { return captured; }
    public void cancel() { cancelled = true; }
    public void reset() { tracking = captured = cancelled = false; }
}
