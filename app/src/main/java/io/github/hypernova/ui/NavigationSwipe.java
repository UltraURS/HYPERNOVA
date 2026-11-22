// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.ui;

final class NavigationSwipe {
    private final float slop, distance;
    private float startX, startY;
    private boolean tracking, dragging;

    NavigationSwipe(float slop, float distance) {
        this.slop = slop;
        this.distance = distance;
    }

    void start(float x, float y, boolean eligible) {
        startX = x;
        startY = y;
        tracking = eligible;
        dragging = false;
    }

    boolean intercept(float x, float y) {
        if (!tracking || dragging) return dragging;
        float horizontal = Math.abs(x - startX), vertical = Math.abs(y - startY);
        if (vertical >= horizontal && vertical > slop) tracking = false;
        dragging = tracking && horizontal > slop && horizontal > vertical * 1.5f;
        return dragging;
    }

    float offset(float x) { return dragging ? x - startX : 0f; }

    boolean finish(float x) {
        boolean accepted = Math.abs(offset(x)) >= distance;
        cancel();
        return accepted;
    }

    void cancel() { tracking = dragging = false; }
}
