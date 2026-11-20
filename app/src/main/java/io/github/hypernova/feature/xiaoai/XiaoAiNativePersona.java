// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import android.content.Intent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;

final class XiaoAiNativePersona {
    private static final String MARKER = "io.github.hypernova.xiaoai.PERSONA";
    private static final String REQUEST = "hypernova_xiaoai_persona_request";
    private static final String ASR_ORIGIN = "com.miui.voiceassist.query&&asr_proxy_service";
    private static final String DOUBLE_TAP_ORIGIN = "double_click_fullscreen_gesture_line";
    private final String nonce = UUID.randomUUID().toString();
    private final ThreadLocal<XiaoAiPersona> sending = new ThreadLocal<>();
    private final XiaoAiStore store;

    XiaoAiNativePersona(XiaoAiStore store) { this.store = store; }

    void install(ClassLoader loader) {
        Class<?> query = XposedHelpers.findClass("h30.f0", loader);
        Class<?> queryParams = XposedHelpers.findClass("h30.f0$c", loader);
        Class<?> queryTts = XposedHelpers.findClass("h30.f0$e", loader);
        Class<?> queryService = XposedHelpers.findClass("com.xiaomi.voiceassistant.SpeechQueryService", loader);
        Class<?> dispatcher = XposedHelpers.findClass("m10.b1", loader);
        Class<?> speech = XposedHelpers.findClass("s10.c", loader);
        Class<?> speechPreset = XposedHelpers.findClass("s10.c$a", loader);
        Class<?> nlp = XposedHelpers.findClass("d30.c", loader);
        Class<?> tts = XposedHelpers.findClass("d30.f", loader);
        Class<?> task = XposedHelpers.findClass("t10.x0", loader);
        Class<?> engine = XposedHelpers.findClass("m10.m0", loader);
        Class<?> engineParams = XposedHelpers.findClass("m10.s0$d", loader);
        Class<?> payload = XposedHelpers.findClass("com.xiaomi.ai.api.Nlp$Request", loader);
        Class<?> llmPayload = XposedHelpers.findClass("com.xiaomi.ai.api.Nlp$RequestLargeLanguageModelContent", loader);
        Class<?> message = XposedHelpers.findClass("com.xiaomi.ai.api.common.Message", loader);
        Method buildIntent = XposedHelpers.findMethodExact(query, "e", queryParams);
        Method receiveQuery = XposedHelpers.findMethodExact(queryService, "onStartCommand", Intent.class, int.class, int.class);
        Method dispatchText = XposedHelpers.findMethodExact(dispatcher, "r", Intent.class);
        Method buildLlmEvent = XposedHelpers.findMethodExact(query, "a", String.class, boolean.class, queryTts,
                boolean.class, boolean.class);
        Method getPayload = XposedHelpers.findMethodExact(message, "getPayload");
        Method setQuery = XposedHelpers.findMethodExact(llmPayload, "setQuery", String.class);
        Method parseIntent = XposedHelpers.findMethodExact(speech, "getRequestInfo", Intent.class, speechPreset);
        Method submit = XposedHelpers.findMethodExact(task, "l", nlp, tts, speech);
        Method plain = XposedHelpers.findMethodExact(engine, "I0", engineParams);
        Method spoken = XposedHelpers.findMethodExact(engine, "J0", engineParams);
        XposedHelpers.findField(engineParams, "d");
        Constructor<?> request = XposedHelpers.findConstructorExact(payload, String.class);

        // The app and floating keyboards share this host-owned Intent builder.
        XposedBridge.hookMethod(buildIntent, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof Intent intent)) return;
                String origin = intent.getStringExtra("queryOrigin");
                String source = intent.getStringExtra("voice_assist_start_from_key");
                if (isKeyboardOrigin(origin) || isKeyboardOrigin(source) || "correct_query_list".equals(source)
                        || "llm_query_edit_card".equals(origin) || "llm_re_answer".equals(origin)
                        || "wakeup_guidance_new".equals(origin)) {
                    mark(intent);
                }
            }
        });
        XposedBridge.hookMethod(dispatchText, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // Standard keyboard requests can arrive here directly after gesture wakeup.
                if (param.args[0] instanceof Intent intent
                        && (isKeyboardOrigin(intent.getStringExtra("queryOrigin"))
                            || isKeyboardOrigin(intent.getStringExtra("voice_assist_start_from_key")))) {
                    mark(intent);
                }
            }
        });
        XposedBridge.hookMethod(receiveQuery, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // The ASR provider delivers its finished transcript via the protected query service.
                if (param.args[0] instanceof Intent intent && "com.miui.voiceassist.query".equals(intent.getAction())
                        && ASR_ORIGIN.equals(intent.getStringExtra("queryOrigin"))
                        && "AsrProxyService".equals(intent.getStringExtra("voice_assist_start_from_key"))) {
                    mark(intent);
                }
            }
        });
        XposedBridge.hookMethod(parseIntent, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() == null || !(param.args[0] instanceof Intent intent)
                        || intent.getBooleanExtra("use_offline_nlp", false)
                        || !nonce.equals(intent.getStringExtra(MARKER))) return;
                XposedHelpers.setAdditionalInstanceField(param.getResult(), REQUEST, true);
            }
        });
        XposedBridge.hookMethod(submit, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args[0] == null) return;
                if (param.args[2] != null
                        && Boolean.TRUE.equals(XposedHelpers.removeAdditionalInstanceField(param.args[2], REQUEST))) {
                    XposedHelpers.setAdditionalInstanceField(param.args[0], REQUEST, true);
                } else {
                    XposedHelpers.removeAdditionalInstanceField(param.args[0], REQUEST);
                }
            }
        });
        XC_MethodHook engineScope = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.setObjectExtra(REQUEST, sending.get());
                sending.remove();
                try {
                    Object nlpRequest = XposedHelpers.getObjectField(param.args[0], "d");
                    if (nlpRequest != null
                            && Boolean.TRUE.equals(XposedHelpers.removeAdditionalInstanceField(nlpRequest, REQUEST))) {
                        XiaoAiPersona persona = activePersona();
                        if (persona != null) sending.set(persona);
                    }
                } catch (Throwable error) {
                    HookLog.error("XiaoAi native personality scope", error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getObjectExtra(REQUEST) instanceof XiaoAiPersona previous) sending.set(previous);
                else sending.remove();
            }
        };
        XposedBridge.hookMethod(plain, engineScope);
        XposedBridge.hookMethod(spoken, engineScope);
        XposedBridge.hookMethod(request, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                XiaoAiPersona persona = sending.get();
                if (persona != null && param.args[0] instanceof String question) {
                    param.args[0] = persona.nativeQuery(question);
                }
            }
        });
        XposedBridge.hookMethod(buildLlmEvent, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws ReflectiveOperationException {
                if (param.getResult() == null || !(param.args[0] instanceof String question) || question.isBlank()) return;
                XiaoAiPersona persona = activePersona();
                if (persona == null) return;
                Object eventPayload = getPayload.invoke(param.getResult());
                if (llmPayload.isInstance(eventPayload)) {
                    setQuery.invoke(eventPayload, persona.nativeQuery(question));
                }
            }
        });
    }

    private static boolean isKeyboardOrigin(String origin) {
        return "QueryEditBar".equals(origin) || DOUBLE_TAP_ORIGIN.equals(origin)
                || ("android.intent.action.ASSIST&&" + DOUBLE_TAP_ORIGIN).equals(origin);
    }

    private void mark(Intent intent) {
        String question = intent.getStringExtra("assist_query");
        if (question != null && !question.isBlank() && !intent.getBooleanExtra("use_offline_nlp", false)) {
            intent.putExtra(MARKER, nonce);
        }
    }

    private XiaoAiPersona activePersona() {
        try {
            XiaoAiPersona persona = store.read().persona();
            return persona.enabled() ? persona : null;
        } catch (Exception error) {
            HookLog.error("XiaoAi native personality config", error);
            return null;
        }
    }
}
