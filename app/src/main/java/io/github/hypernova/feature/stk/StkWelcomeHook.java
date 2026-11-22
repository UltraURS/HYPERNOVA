// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.stk;

import android.content.Intent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.regex.Pattern;

public final class StkWelcomeHook {
    private static final String CARRIER = "(?:中国移动|中国联通|中国电信|中国广电|小米移动)";
    private static final String SEPARATORS = "[，、。！：,!:]*";
    private static final Pattern WELCOME = Pattern.compile(
        "^(?:尊敬的" + CARRIER + "?(?:用户|客户)" + SEPARATORS + "(?:您好" + SEPARATORS + ")?)?"
        + "(?:感谢您(?:使用|选择)|欢迎您?使用)" + CARRIER);
    private static final Pattern ACTION = Pattern.compile("验证码|密码|余额|欠费|停机|充值|扣费|扣款|收费|资费|订购|退订|授权|确认|办理成功");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    private StkWelcomeHook() { }

    public static void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> stub = XposedHelpers.findClass("com.android.stk.MiStkStubImpl", loader);
        Class<?> message = XposedHelpers.findClass("com.android.internal.telephony.cat.TextMessage", loader);
        Method dialog = XposedHelpers.findMethodExact(stub, "launchTextDialog", Intent.class, int.class, message);
        // Keep the native caller on the hookable path even when ART has inlined its private filter.
        XposedBridge.class.getMethod("deoptimizeMethod", Member.class).invoke(null, dialog);

        Method filter = XposedHelpers.findMethodExact(stub, "filterDisplayText", String.class);
        XC_MethodHook.Unhook hook = XposedBridge.hookMethod(filter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                // Let MiStkStubImpl complete the SIM response through its native suppression branch.
                if (Boolean.FALSE.equals(param.getResult()) && isWelcome((String) param.args[0])) {
                    param.setResult(true);
                    HookLog.info("stk-welcome FILTER_MATCH");
                }
            }
        });
        if (hook != null) HookLog.info("stk-welcome INSTALLED");
    }

    static boolean isWelcome(String message) {
        if (message == null) return false;
        String text = WHITESPACE.matcher(message).replaceAll("");
        return WELCOME.matcher(text).find() && !ACTION.matcher(text).find();
    }
}
