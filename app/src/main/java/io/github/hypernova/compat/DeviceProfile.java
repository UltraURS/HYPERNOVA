package io.github.hypernova.compat;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.FileInputStream;
import java.security.MessageDigest;

public final class DeviceProfile {
    public static final String FINGERPRINT = "Redmi/pearl/pearl:15/AP3A.240905.015.A2/OS3.0.3.0.VLHCNXM:user/release-keys";
    private DeviceProfile() { }
    public static boolean matchesBuild() {
        return Build.VERSION.SDK_INT == 35 && FINGERPRINT.equals(Build.FINGERPRINT);
    }
    public static boolean verifyPowerCenter(Context context) throws Exception {
        return matchesBuild() && verifyApk(context, "com.miui.securitycenter", 40001213L,
                "9b532d84e0206b3e1bc42ee9bdb14840b45e637becbdf84dd4a4f2577c06ddf4");
    }
    // Run once during process setup, never in a UI or tile callback.
    public static boolean verify(Context context, String packageName) throws Exception {
        if (!matchesBuild()) return false;
        if ("com.android.calendar".equals(packageName)) {
            return verifyApk(context, packageName, 17000626L,
                "bd5f85837e95cb80c54bebc598814ee4166002101a1d26b7193698192fb0de26");
        }
        if ("com.miui.weather2".equals(packageName)) {
            return verifyApk(context, packageName, 17000315L,
                "a8af7c8494c4c9ad882ad96adb96c2779ac60b7bbbb7f0d176e566d4c7469ec1");
        }
        if ("com.mi.health".equals(packageName)) {
            return verifyApk(context, packageName, 348003L,
                "5bee70a93ddb380b779f1c58d8d2c6313242886556c5ee077eb60a1557accac1");
        }
        if ("com.android.stk".equals(packageName)) {
            return verifyApk(context, packageName, 35L,
                "4eaf4bbc10ef1c05e98f0950344144add18952a9dc525f9bc7733c06b5f65cd8");
        }
        if ("com.mobiletools.systemhelper".equals(packageName)) {
            return verifyApk(context, packageName, 31L,
                "5267008ede808c467988df588915791d21a6f1f3fbe219c57b125a235000df4f");
        }
        if ("com.miui.dmregservice".equals(packageName)) {
            return verifyApk(context, packageName, 34L,
                "5c847426082037163b8e414da4e3e7b51388414b3e3c413de83c1583304db82d");
        }
        if ("com.miui.analytics".equals(packageName)) {
            return verifyApk(context, packageName, 2026091500L,
                "a3656199f013e2f68bc20022f941122a13ce99e33c5df1cef054c9267189d73f");
        }
        if ("com.miui.voiceassist".equals(packageName)) {
            return verifyApk(context, packageName, 507009011L,
                "dd75a0d9b1c0803906eafe72126dc44a150f16337e26c8fcf53304f13d74140b");
        }
        if ("com.miui.screenshot".equals(packageName)) {
            return verifyApk(context, packageName, 10503051L,
                "0d9e88df4945465ed6d6d77a5e9b4df37f56c42206a9c759a7601cacd3af9ad3");
        }
        if ("com.android.systemui".equals(packageName)) {
            return verifyApk(context, packageName, 202408080L,
                "e93e374b93ce676ffa4eaef841517134832f7fe9b79c4711a796549d9ed41ec1")
                && verifyApk(context, "miui.systemui.plugin", 165034200L,
                "81f35e4964cd5679b915f23e898020833f5513a7b30ec0d3f11d0f7808f11639");
        }
        if ("com.miui.home".equals(packageName) || "com.miui.securitycenter".equals(packageName)) {
            return verifyApk(context, "com.miui.home", 601031943L,
                "36323641c5af2bc0178fb30dad28b6e8e6a35a3aee98e51a2384e84d3e22981f")
                && verifyApk(context, "com.miui.securitycenter", 40001213L,
                "9b532d84e0206b3e1bc42ee9bdb14840b45e637becbdf84dd4a4f2577c06ddf4");
        }
        return "com.android.settings".equals(packageName)
            && verifyApk(context, packageName, 35L,
                "43f5e03fed66dab94abd1857afd9b3657c2773ebd08e36a1bfa6f4481e9ca67a");
    }
    private static boolean verifyApk(Context context, String name, long version, String expected) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(name, 0);
        if (info.getLongVersionCode() != version || info.applicationInfo == null) return false;
        String[] splits = info.applicationInfo.splitSourceDirs;
        if (splits != null && splits.length != 0) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(info.applicationInfo.sourceDir)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return expected.equals(hex.toString());
    }
}
