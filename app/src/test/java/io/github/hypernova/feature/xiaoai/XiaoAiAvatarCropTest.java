// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class XiaoAiAvatarCropTest {
    private static final float TOLERANCE = 0.001f;

    @Test public void startsWithCenteredSquareInEitherOrientation() {
        assertRegion(new XiaoAiAvatarCrop(800, 300), 250, 0, 300);
        assertRegion(new XiaoAiAvatarCrop(300, 800), 0, 250, 300);
        assertRegion(new XiaoAiAvatarCrop(300, 300), 0, 0, 300);
    }

    @Test public void pinchKeepsTheSourcePixelUnderItsPivot() {
        var crop = new XiaoAiAvatarCrop(400, 240);
        crop.zoom(2, 0.25f, 0.75f);
        assertRegion(crop, 110, 90, 120);
        crop.zoom(0.5f, 0.25f, 0.75f);
        assertRegion(crop, 80, 0, 240);
    }

    @Test public void dragMovesTheImageAndStopsAtAllSourceEdges() {
        var crop = new XiaoAiAvatarCrop(400, 240);
        crop.zoom(2, 0.5f, 0.5f);
        crop.pan(0.5f, -0.25f);
        assertRegion(crop, 80, 90, 120);
        crop.pan(10, -10);
        assertRegion(crop, 0, 120, 120);
        crop.pan(-10, 10);
        assertRegion(crop, 280, 0, 120);
    }

    @Test public void zoomOutNearAnEdgeNeverExposesAnEmptyBorder() {
        var crop = new XiaoAiAvatarCrop(400, 240);
        crop.zoom(8, 1, 1);
        crop.pan(-10, -10);
        crop.zoom(0.01f, 0, 0);
        assertRegion(crop, 160, 0, 240);
    }

    @Test public void handlesExtremelySmallPhotosAndDegeneratePinches() {
        var crop = new XiaoAiAvatarCrop(1, 40);
        crop.zoom(100, 1, 1);
        crop.zoom(Float.NaN, 0.5f, 0.5f);
        crop.zoom(0, 0.5f, 0.5f);
        crop.pan(-10, -100);
        assertRegion(crop, 0, 39, 1);
        assertTrue(crop.size() > 0);
    }

    private static void assertRegion(XiaoAiAvatarCrop crop, float left, float top, float size) {
        assertEquals(left, crop.left(), TOLERANCE);
        assertEquals(top, crop.top(), TOLERANCE);
        assertEquals(size, crop.size(), TOLERANCE);
    }
}
