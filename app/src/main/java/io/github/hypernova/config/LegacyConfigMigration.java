// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import io.github.hypernova.feature.sidebar.SidebarGesture;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

public final class LegacyConfigMigration {
    private LegacyConfigMigration() { }

    public static void save(Context context, SharedPreferences preferences) throws IOException, JSONException {
        JSONObject data = new JSONObject();
        data.put(Feature.SCHEMA, 1);
        for (Feature feature : Feature.values()) data.put(feature.key, preferences.getBoolean(feature.key, false));
        data.put(SidebarGesture.HOLD_KEY, preferences.getInt(SidebarGesture.HOLD_KEY, SidebarGesture.DEFAULT_HOLD_MILLIS));
        byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
        AtomicFile file = backupFile(context);
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
        if (!Arrays.equals(bytes, file.readFully())) throw new IOException("Legacy preference backup did not verify");
    }

    public static boolean initialize(Context context, SharedPreferences preferences) throws IOException, JSONException {
        if (preferences.getInt(Feature.SCHEMA, 0) == 1) return true;
        AtomicFile file = backupFile(context);
        JSONObject data = null;
        try (var input = file.openRead()) {
            data = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (FileNotFoundException absent) {
            // A fresh installation starts with the default preferences.
        }
        SharedPreferences.Editor edit = preferences.edit().putInt(Feature.SCHEMA, 1);
        if (data != null) {
            if (data.getInt(Feature.SCHEMA) != 1) throw new IOException("Unsupported legacy configuration schema");
            for (Feature feature : Feature.values()) edit.putBoolean(feature.key, data.getBoolean(feature.key));
            edit.putInt(SidebarGesture.HOLD_KEY, SidebarGesture.clampHoldMillis(data.getInt(SidebarGesture.HOLD_KEY)));
        }
        if (!edit.commit()) return false;
        if (data != null) file.delete();
        return true;
    }

    private static AtomicFile backupFile(Context context) {
        File storage = context.createDeviceProtectedStorageContext().getFilesDir();
        return new AtomicFile(new File(storage, "legacy-feature-settings.json"));
    }
}
