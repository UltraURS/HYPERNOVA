// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.freeform;

/** Coordinates in the task surface, whose parent supplies the actual freeform scale. */
public record FreeformClosePlacement(float x, float y, int size) {
    // Preserve the existing cross's outer bounds, including its 1 dp round stroke.
    static final float ICON_SIZE_DP = 7.48f;

    public static FreeformClosePlacement at(int width, int height, float density, float scale, float dotCenterY) {
        if (width <= 0 || height <= 0 || !Float.isFinite(density) || density <= 0
            || !Float.isFinite(scale) || scale <= 0 || !Float.isFinite(dotCenterY)) return null;
        int size = Math.round(ICON_SIZE_DP * density / scale);
        float inset = dotCenterY - size / 2f;
        if (size <= 0 || width < size * 3L || inset < 0 || inset + size > height || inset + size > width) return null;
        return new FreeformClosePlacement(width - size - inset, inset, size);
    }
}
