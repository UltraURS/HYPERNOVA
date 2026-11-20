package io.github.hypernova.feature.update;

public final class EntryPolicy {
    private EntryPolicy() { }
    public static boolean hide(String key, boolean ota, boolean reset) {
        return (ota && "miui_updater".equals(key)) || (reset && "factory_reset_key".equals(key));
    }

    public static boolean hideResetRecommendation(String packageName, String component, String fragment) {
        return "com.android.settings".equals(packageName)
            && "com.android.settings.SubSettings".equals(component)
            && "com.android.settings.MiuiMasterClear".equals(fragment);
    }

    public static boolean hideResetSearch(String resultPackage, String key, String targetPackage, String component, String fragment) {
        return ("com.android.settings".equals(resultPackage) && "factory_reset_key".equals(key))
            || hideResetRecommendation(targetPackage, component, fragment);
    }
}
