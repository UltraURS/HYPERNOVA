package io.github.hypernova.feature.update;

import android.content.Context;
import android.content.pm.PackageManager;

/** Reports applicability only; never represents a missing package as a blocked request. */
public final class OtaClientStatus {
    public static final String PACKAGE = "com.android.updater";
    private OtaClientStatus() { }

    public static String describe(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return "待适配：检测到系统更新器。尚未核验其请求链，未启用服务器阻断。";
        } catch (PackageManager.NameNotFoundException absent) {
            return "不适用：当前用户下未检测到 com.android.updater。仅限定 OTA 客户端，不启用设备级网络封锁。";
        } catch (SecurityException unavailable) {
            return "无法确认：无法读取目标更新器信息。未启用服务器阻断。";
        }
    }
}
