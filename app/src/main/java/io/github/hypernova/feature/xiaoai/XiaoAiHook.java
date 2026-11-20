// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;

public final class XiaoAiHook {
    public static final String PACKAGE = "com.miui.voiceassist";
    private static final String SETTINGS_KEY = "hypernova_xiaoai_models";
    private static final String PERSONA_ROW = "hypernova_xiaoai_persona_row";
    private static final int AGENT_TITLE = 0x7f130cd5;
    private static final String HOST = "com.xiaomi.voiceassistant.";
    private final XiaoAiStore store;
    private final XiaoAiMiuix ui;
    private final XiaoAiDialogs dialogs;

    private XiaoAiHook(Context context, ClassLoader loader) {
        store = new XiaoAiStore(context);
        ui = new XiaoAiMiuix(loader);
        dialogs = new XiaoAiDialogs(store, new ChatCompletions(loader), ui);
    }

    public static void install(Context context, ClassLoader loader) {
        try {
            XiaoAiHook hook = new XiaoAiHook(context, loader);
            hook.installSettings(loader);
            hook.installPersonaRow(loader);
            hook.installText(loader);
            new XiaoAiNativePersona(hook.store).install(loader);
            XiaoAiQueryDisplay.install(loader);
            HookLog.info("xiaoai-custom INSTALLED");
        } catch (Throwable error) { HookLog.error("xiaoai-custom install", error); }
    }

    private void installSettings(ClassLoader loader) {
        Class<?> fragment = XposedHelpers.findClass(HOST + "settings.MiuiVoiceSettingActivity$a", loader);
        Class<?> preference = XposedHelpers.findClass(HOST + "settings.preference.StyleableTextPreference", loader);
        Class<?> click = XposedHelpers.findClass("androidx.preference.Preference$OnPreferenceClickListener", loader);
        Method create = XposedHelpers.findMethodExact(fragment, "onCreatePreferences", Bundle.class, String.class);
        Method resume = XposedHelpers.findMethodExact(fragment, "onResume");
        XC_MethodHook add = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                try {
                    Activity activity = (Activity) XposedHelpers.callMethod(param.thisObject, "getActivity");
                    Object screen = XposedHelpers.callMethod(param.thisObject, "getPreferenceScreen");
                    if (activity == null || activity.isFinishing() || screen == null) return;
                    Object row = XposedHelpers.callMethod(screen, "findPreference", SETTINGS_KEY);
                    if (row == null) {
                        Context themed = (Context) XposedHelpers.callMethod(screen, "getContext");
                        row = XposedHelpers.newInstance(preference, themed);
                        XposedHelpers.callMethod(row, "setKey", SETTINGS_KEY);
                        XposedHelpers.callMethod(row, "setTitle", "自定义 API Key");
                        XposedHelpers.callMethod(row, "setOrder", 10000);
                        XposedHelpers.callMethod(row, "setPersistent", false);
                        XposedHelpers.callMethod(row, "setShowRightArrow", true);
                        Object setting = row;
                        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{click}, (self, method, args) ->
                                switch (method.getName()) {
                                    case "onPreferenceClick" -> {
                                        dialogs.models(activity, () -> updateSetting(setting));
                                        yield true;
                                    }
                                    case "equals" -> self == args[0];
                                    case "hashCode" -> System.identityHashCode(self);
                                    case "toString" -> "HYPERNOVAXiaoAiPreference";
                                    default -> throw new UnsupportedOperationException(method.getName());
                                });
                        XposedHelpers.callMethod(row, "setOnPreferenceClickListener", listener);
                        XposedHelpers.callMethod(screen, "addPreference", row);
                    }
                    updateSetting(row);
                } catch (Throwable error) { HookLog.error("XiaoAi settings entry", error); }
            }
        };
        XposedBridge.hookMethod(create, add);
        XposedBridge.hookMethod(resume, add);
    }

    private void updateSetting(Object row) {
        try {
            String label = store.read().selectedLabel();
            XposedHelpers.callMethod(row, "setSummary", "当前模型 " + label);
        } catch (Throwable error) {
            HookLog.error("XiaoAi model summary", error);
        }
    }

    private void installPersonaRow(ClassLoader loader) {
        Class<?> drawer = XposedHelpers.findClass("yf0.g0", loader);
        Class<?> adapter = XposedHelpers.findClass("yf0.m0", loader);
        Class<?> row = XposedHelpers.findClass("zf0.a", loader);
        Class<?> rowType = XposedHelpers.findClass("yf0.o", loader);
        Class<?> home = XposedHelpers.findClass(HOST + "TabHostMainActivity", loader);
        XposedHelpers.findConstructorExact(row, int.class, String.class, View.OnClickListener.class, rowType);
        XposedHelpers.findMethodExact(adapter, "addItem", row, int.class);
        XposedHelpers.findField(drawer, "r");
        XposedHelpers.findField(home, "R");
        Object skill = XposedHelpers.getStaticObjectField(rowType, "SKILL");
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(drawer, "J"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                addPersonaRow(param.thisObject, row, skill);
            }
        });
        // Covers an already constructed drawer when verification finishes after the home layout.
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(home, "t0", home, View.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                addPersonaRow(XposedHelpers.getObjectField(param.args[0], "R"), row, skill);
            }
        });
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(home, "onBackPressed"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (XiaoAiPersonaPage.handleBack((Activity) param.thisObject)) param.setResult(null);
            }
        });
    }

    private void addPersonaRow(Object drawer, Class<?> row, Object skill) {
        if (drawer == null) return;
        try {
            Object adapter = XposedHelpers.getObjectField(drawer, "r");
            if (adapter == null || XposedHelpers.getAdditionalInstanceField(adapter, PERSONA_ROW) != null
                    || !(XposedHelpers.getObjectField(drawer, "a") instanceof Activity activity)
                    || !(XposedHelpers.getObjectField(adapter, "a") instanceof List<?> rows)) return;
            String agentTitle = activity.getString(AGENT_TITLE);
            for (int i = 0; i < rows.size(); i++) {
                if (!agentTitle.equals(XposedHelpers.callMethod(rows.get(i), "getTitle"))) continue;
                View.OnClickListener click = view -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    try { XiaoAiPersonaPage.open(activity, store, ui); }
                    catch (Throwable error) {
                        HookLog.error("XiaoAi persona page", error);
                        Toast.makeText(activity, "无法打开人格页面", Toast.LENGTH_SHORT).show();
                    }
                };
                Object persona = XposedHelpers.newInstance(row, XiaoAiAvatar.ICON, "人格", click, skill);
                XposedHelpers.callMethod(adapter, "addItem", persona, i + 1);
                XposedHelpers.setAdditionalInstanceField(adapter, PERSONA_ROW, true);
                return;
            }
        } catch (Throwable error) { HookLog.error("XiaoAi persona entry", error); }
    }

    private void installText(ClassLoader loader) {
        Class<?> callback = XposedHelpers.findClass("ff0.e0", loader);
        Class<?> mainInput = XposedHelpers.findClass(HOST + "ConversationFragment$d", loader);
        XposedHelpers.findField(mainInput, "j"); // Owning ConversationFragment
        XposedHelpers.findField(callback, "d"); // Host multimodal state
        XposedBridge.hookMethod(XposedHelpers.findMethodExact(callback, "onSendClick", String.class), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!mainInput.isInstance(param.thisObject) || Looper.myLooper() != Looper.getMainLooper()
                        || !(param.args[0] instanceof String text) || text.isBlank()) return;
                Activity activity = null;
                try {
                    Object fragment = XposedHelpers.getObjectField(param.thisObject, "j");
                    activity = (Activity) XposedHelpers.callMethod(fragment, "getActivity");
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()
                            || !(HOST + "TabHostMainActivity").equals(activity.getClass().getName())) return;
                    XiaoAiConfig config = store.read();
                    XiaoAiConfig.Model selected = config.selected();
                    if (selected == null) return;
                    Object floating = XposedHelpers.getObjectField(param.thisObject, "d");
                    if ((boolean) XposedHelpers.callMethod(floating, "isMultiModalUploadShow")
                            || (boolean) XposedHelpers.callMethod(floating, "isMultiModalUploadSuccess")) return;
                    param.setResult(null);
                    dialogs.conversation(activity, selected, config.persona(), text);
                } catch (Throwable error) {
                    HookLog.error("XiaoAi text routing", error);
                    param.setResult(null);
                    if (activity != null) Toast.makeText(activity, "无法打开自定义对话，请检查小爱模型设置。", Toast.LENGTH_LONG).show();
                }
            }
        });
    }
}
