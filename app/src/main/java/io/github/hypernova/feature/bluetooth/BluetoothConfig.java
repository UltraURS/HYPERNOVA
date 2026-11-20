// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Connect Colours, Copyright (C) 2026 UltraURS and contributors.
package io.github.hypernova.feature.bluetooth;

import android.content.Context;
import android.bluetooth.BluetoothAdapter;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import java.util.UUID;

public final class BluetoothConfig {
    public static final Uri URI = Settings.Secure.CONTENT_URI;
    private static final String PREFIX = "hypernova_bluetooth_";
    private static final String SESSION = PREFIX + "session";

    private BluetoothConfig() {}

    // Called only inside Settings/SystemUI. SettingsProvider owns persistence and
    // enforces WRITE_SECURE_SETTINGS; no module process or exported module endpoint is needed.
    public static Bundle read(Context context, String address) {
        String key = key(address);
        String stored = Settings.Secure.getString(context.getContentResolver(), key);
        int value = stored == null ? -1 : Integer.parseInt(stored);
        if (value == 0 || value < -5 || value > 5) {
            throw new IllegalStateException("Invalid Bluetooth configuration");
        }
        Bundle result = new Bundle();
        result.putBoolean("enabled", value > 0);
        result.putInt("attempts", Math.abs(value));
        result.putBoolean("paused", session(context).equals(
                Settings.Secure.getString(context.getContentResolver(), key + "_paused")));
        return result;
    }

    public static void save(Context context, String address, Bundle values) {
        int attempts = values.getInt("attempts", 1);
        if (attempts < 1 || attempts > 5) throw new IllegalArgumentException("Invalid attempt limit");
        // One atomic value for enabled + attempts; never overwrite a concurrent manual pause.
        put(context, key(address), String.valueOf(values.getBoolean("enabled") ? attempts : -attempts));
    }

    public static void pause(Context context, String address) {
        String key = key(address);
        if (Settings.Secure.getString(context.getContentResolver(), key) != null) {
            put(context, key + "_paused", session(context));
        }
    }

    public static void reset(Context context) {
        put(context, SESSION, UUID.randomUUID().toString());
    }

    public static void remove(Context context, String address) {
        String key = key(address);
        put(context, key, null);
        put(context, key + "_paused", null);
    }

    public static boolean isConfigChange(Uri uri) {
        return uri == null || URI.equals(uri) || ("settings".equals(uri.getAuthority())
                && uri.getPath() != null && uri.getPath().startsWith("/secure/" + PREFIX));
    }

    private static String key(String address) {
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new IllegalArgumentException("Invalid Bluetooth address");
        }
        return PREFIX + address.replace(":", "");
    }

    private static String session(Context context) {
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        return boot + ":" + Settings.Secure.getString(context.getContentResolver(), SESSION);
    }

    private static void put(Context context, String key, String value) {
        if (!Settings.Secure.putString(context.getContentResolver(), key, value)) {
            throw new IllegalStateException("Bluetooth configuration could not be saved");
        }
    }
}
