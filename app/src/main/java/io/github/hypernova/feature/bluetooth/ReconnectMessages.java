// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Connect Colours, Copyright (C) 2026 UltraURS and contributors.
package io.github.hypernova.feature.bluetooth;

public final class ReconnectMessages {
    private ReconnectMessages() {}

    public static String attempt(String name, int attempt, int limit) {
        return "正在重连 " + deviceName(name) + "（" + attempt + "/" + limit + "）";
    }

    public static String outcome(RetryRound.State before, RetryRound.State after, int attempts, String name) {
        if (before == after || attempts == 0 || before == RetryRound.State.PAUSED) return null;
        if (after == RetryRound.State.CONNECTED) return deviceName(name) + "：重连成功";
        if (after == RetryRound.State.EXHAUSTED) return deviceName(name) + "：重连失败，已尝试 " + attempts + " 次";
        return null;
    }

    private static String deviceName(String name) {
        return name == null || name.trim().isEmpty() ? "蓝牙设备" : name;
    }
}
