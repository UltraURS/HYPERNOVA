// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

final class XiaoAiAvatarCrop {
    private final int width, height;
    private final float largest, smallest;
    private float left, top, size;

    XiaoAiAvatarCrop(int width, int height) {
        this.width = width;
        this.height = height;
        largest = Math.min(width, height);
        smallest = Math.max(1f, largest / 8f);
        size = largest;
        left = (width - size) / 2f;
        top = (height - size) / 2f;
    }

    float left() { return left; }
    float top() { return top; }
    float size() { return size; }

    // Deltas and zoom pivots are measured in fractions of the visible square.
    void pan(float dx, float dy) {
        position(left - dx * size, top - dy * size);
    }

    void zoom(float factor, float pivotX, float pivotY) {
        if (factor <= 0 || !Float.isFinite(factor)) return;
        float span = size;
        size = clamp(span / factor, smallest, largest);
        position(left + (span - size) * clamp(pivotX, 0, 1), top + (span - size) * clamp(pivotY, 0, 1));
    }

    private void position(float x, float y) {
        left = clamp(x, 0, width - size);
        top = clamp(y, 0, height - size);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
