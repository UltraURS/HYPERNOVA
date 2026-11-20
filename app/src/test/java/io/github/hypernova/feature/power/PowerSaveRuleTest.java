// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.power;

import org.junit.Test;
import static org.junit.Assert.*;

public class PowerSaveRuleTest {
    @Test
    public void disabledByDefaultDoesNotTrigger() {
        assertFalse(new PowerSaveRule().shouldEnable(false, 20, 5, 100, 0, false));
    }

    @Test
    public void strictlyLessThanTheThresholdAtBothEndpoints() {
        for (int threshold : new int[]{20, 50, 80}) {
            PowerSaveRule rule = new PowerSaveRule();
            assertFalse(rule.shouldEnable(true, threshold, threshold, 100, 0, false));
            assertTrue(rule.shouldEnable(true, threshold, threshold - 1, 100, 0, false));
        }
    }

    @Test
    public void usesTheBatteryScaleWithoutRoundingOrOverflow() {
        PowerSaveRule rule = new PowerSaveRule();
        assertFalse(rule.shouldEnable(true, 20, 2, 10, 0, false));
        assertTrue(rule.shouldEnable(true, 20, 199, 1000, 0, false));
        rule.reset();
        assertFalse(rule.shouldEnable(true, 80, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, false));
        assertTrue(rule.shouldEnable(true, 80, 0, Integer.MAX_VALUE, 0, false));
    }

    @Test
    public void allConnectedPowerSourcesBlockEnabling() {
        PowerSaveRule rule = new PowerSaveRule();
        for (int plugged : new int[]{1, 2, 4, 8, 3}) {
            assertFalse(rule.shouldEnable(true, 80, 10, 100, plugged, false));
        }
        assertTrue(rule.shouldEnable(true, 80, 10, 100, 0, false));
    }

    @Test
    public void invalidBatterySamplesDoNotTriggerOrRearmTheRule() {
        PowerSaveRule rule = new PowerSaveRule();
        assertTrue(rule.shouldEnable(true, 60, 30, 100, 0, false));
        for (int[] sample : new int[][]{{-1, 100, 0}, {101, 100, 0}, {10, 0, 0}, {10, 100, -1}}) {
            assertFalse(rule.shouldEnable(true, 60, sample[0], sample[1], sample[2], false));
        }
        assertFalse(rule.shouldEnable(true, 60, 29, 100, 0, false));
    }

    @Test
    public void manualOffAndRepeatedBatteryEventsStayOffInTheSameInterval() {
        PowerSaveRule rule = new PowerSaveRule();
        assertTrue(rule.shouldEnable(true, 60, 59, 100, 0, false));
        assertFalse(rule.shouldEnable(true, 60, 59, 100, 0, true));
        assertFalse(rule.shouldEnable(true, 60, 58, 100, 0, false));
        assertFalse(rule.shouldEnable(true, 60, 10, 100, 0, false));
    }

    @Test
    public void pluggingInThenUnpluggingRearmsEvenWhileBatteryRemainsLow() {
        PowerSaveRule rule = new PowerSaveRule();
        assertTrue(rule.shouldEnable(true, 60, 30, 100, 0, false));
        assertFalse(rule.shouldEnable(true, 60, 30, 100, 4, false));
        assertTrue(rule.shouldEnable(true, 60, 30, 100, 0, false));
    }

    @Test
    public void recoveringToTheThresholdRearmsForTheNextDrop() {
        PowerSaveRule rule = new PowerSaveRule();
        assertTrue(rule.shouldEnable(true, 60, 59, 100, 0, false));
        assertFalse(rule.shouldEnable(true, 60, 60, 100, 0, false));
        assertTrue(rule.shouldEnable(true, 60, 59, 100, 0, false));
    }

    @Test
    public void anAlreadyActiveSaverIsNotOverwrittenAndStillHonorsLaterManualOff() {
        PowerSaveRule rule = new PowerSaveRule();
        assertFalse(rule.shouldEnable(true, 80, 50, 100, 0, true));
        assertFalse(rule.shouldEnable(true, 80, 49, 100, 0, false));
        rule.reset();
        assertTrue(rule.shouldEnable(true, 80, 49, 100, 0, false));
    }

    @Test
    public void disablingAndEnablingAgainRearms() {
        PowerSaveRule rule = new PowerSaveRule();
        assertTrue(rule.shouldEnable(true, 60, 30, 100, 0, false));
        assertFalse(rule.shouldEnable(false, 60, 30, 100, 0, false));
        assertTrue(rule.shouldEnable(true, 60, 30, 100, 0, false));
    }

    @Test
    public void outOfRangeThresholdsDoNotTrigger() {
        for (int threshold : new int[]{0, 19, 81, Integer.MAX_VALUE}) {
            assertFalse(new PowerSaveRule().shouldEnable(true, threshold, 0, 100, 0, false));
        }
    }

    @Test
    public void queuedRecoveryAndInvalidSamplesCannotLoseOrInventAnInterval() {
        PowerSaveRule rule = new PowerSaveRule();
        assertTrue(rule.shouldEnable(true, 60, 30, 100, 0, false));
        assertFalse(rule.observeRecovery(60, -1, 100, 1));
        assertFalse(rule.shouldEnable(true, 60, 30, 100, 0, false));
        assertTrue(rule.observeRecovery(60, 30, 100, 1));
        assertTrue(rule.shouldEnable(true, 60, 30, 100, 0, false));
        assertTrue(rule.observeRecovery(60, 60, 100, 0));
        assertTrue(rule.shouldEnable(true, 60, 59, 100, 0, false));
    }
}
