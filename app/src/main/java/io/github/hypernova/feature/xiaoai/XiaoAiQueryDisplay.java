// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class XiaoAiQueryDisplay {
    private XiaoAiQueryDisplay() {}

    static void install(ClassLoader loader) {
        Class<?> query = XposedHelpers.findClass("com.xiaomi.ai.api.Template$Query", loader);
        Class<?> toast = XposedHelpers.findClass("com.xiaomi.ai.api.Template$Toast", loader);
        Class<?> optional = XposedHelpers.findClass("iw.a", loader);
        Class<?> card = XposedHelpers.findClass("com.xiaomi.voiceassistant.instruction.card.stream.a", loader);
        Class<?> cardMode = XposedHelpers.findClass("j70.c0", loader);
        Method isPresent = XposedHelpers.findMethodExact(optional, "isPresent");
        Method get = XposedHelpers.findMethodExact(optional, "get");
        Method ofNullable = XposedHelpers.findMethodExact(optional, "ofNullable", Object.class);

        // Query echoes feed the native card, edit box, and saved dialogue.
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(query, "getText"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() instanceof String text) param.setResult(XiaoAiPersona.displayQuery(text));
            }
        });
        XC_MethodHook cleanOptional = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws ReflectiveOperationException {
                Object value = param.getResult();
                if (value == null || !(boolean) isPresent.invoke(value) || !(get.invoke(value) instanceof String text)) return;
                String visible = XiaoAiPersona.displayQuery(text);
                if (!visible.equals(text)) param.setResult(ofNullable.invoke(null, visible.isEmpty() ? null : visible));
            }
        };
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(query, "getTextInfo"), cleanOptional);
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(toast, "getQuery"), cleanOptional);
        XposedBridge.hookMethod(XposedHelpers.findConstructorExact(card, int.class, String.class, String.class,
                boolean.class, String.class, boolean.class, cardMode, boolean.class, boolean.class, String.class),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        param.args[1] = XiaoAiPersona.displayQuery((String) param.args[1]);
                        param.args[9] = XiaoAiPersona.displayQuery((String) param.args[9]);
                    }
                });
    }
}
