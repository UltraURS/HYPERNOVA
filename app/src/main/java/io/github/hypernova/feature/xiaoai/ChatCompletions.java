// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;

final class ChatCompletions {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private final Class<?> clientBuilder, requestBuilder, requestBody, mediaType;
    private Object client;

    ChatCompletions(ClassLoader loader) {
        clientBuilder = XposedHelpers.findClass("r21.d0$a", loader);
        requestBuilder = XposedHelpers.findClass("r21.f0$a", loader);
        requestBody = XposedHelpers.findClass("r21.g0", loader);
        mediaType = XposedHelpers.findClass("r21.z", loader);
    }

    private synchronized Object client() {
        if (client != null) return client;
        // Start from a fresh builder, whose defaults are NO_COOKIES and no authenticators.
        Object builder = XposedHelpers.newInstance(clientBuilder);
        XposedHelpers.callMethod(builder, "followRedirects", false);
        XposedHelpers.callMethod(builder, "followSslRedirects", false);
        XposedHelpers.callMethod(builder, "retryOnConnectionFailure", false);
        XposedHelpers.callMethod(builder, "connectTimeout", 12L, TimeUnit.SECONDS);
        XposedHelpers.callMethod(builder, "readTimeout", 45L, TimeUnit.SECONDS);
        XposedHelpers.callMethod(builder, "callTimeout", 60L, TimeUnit.SECONDS);
        return client = XposedHelpers.callMethod(builder, "build");
    }

    record Turn(String question, String answer) { }

    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status) { super("HTTP " + status); this.status = status; }
    }

    final class Call {
        private volatile Object requestCall;
        private volatile boolean cancelled;

        String run(XiaoAiConfig.Model model, XiaoAiPersona persona, List<Turn> history, String question)
                throws IOException, JSONException {
            try {
                byte[] body = body(model.modelId(), persona, history, question);
                Object json = XposedHelpers.callStaticMethod(mediaType, "get", "application/json; charset=utf-8");
                Object payload = XposedHelpers.callStaticMethod(requestBody, "create", body, json);
                Object request = XposedHelpers.newInstance(requestBuilder);
                XposedHelpers.callMethod(request, "url", model.endpoint());
                XposedHelpers.callMethod(request, "header", "Authorization", "Bearer " + model.apiKey());
                XposedHelpers.callMethod(request, "header", "Accept", "application/json");
                XposedHelpers.callMethod(request, "post", payload);
                Object call = XposedHelpers.callMethod(client(), "newCall", XposedHelpers.callMethod(request, "build"));
                requestCall = call;
                if (cancelled) throw new InterruptedIOException("Cancelled");
                Object response = XposedHelpers.callMethod(call, "execute");
                try (Closeable ignored = (Closeable) response) {
                    int status = (int) XposedHelpers.callMethod(response, "code");
                    if (status < 200 || status >= 300) throw new HttpFailure(status);
                    Object responseBody = XposedHelpers.callMethod(response, "body");
                    if (responseBody == null) throw new JSONException("Missing response body");
                    return readAnswer((InputStream) XposedHelpers.callMethod(responseBody, "byteStream"));
                }
            } catch (XposedHelpers.InvocationTargetError error) {
                if (error.getCause() instanceof IOException io) throw io;
                throw new IOException("Request failed", error);
            } finally {
                requestCall = null;
            }
        }

        void cancel() {
            cancelled = true;
            Object call = requestCall;
            if (call == null) return;
            try { XposedHelpers.callMethod(call, "cancel"); }
            catch (Throwable error) { HookLog.error("XiaoAi cancel request", error); }
        }
    }

    static byte[] body(String model, XiaoAiPersona persona, List<Turn> history, String question) throws JSONException {
        JSONArray messages = new JSONArray();
        String prompt = persona.systemPrompt();
        if (!prompt.isBlank()) messages.put(message("system", prompt));
        for (Turn turn : history) {
            messages.put(message("user", turn.question()));
            messages.put(message("assistant", turn.answer()));
        }
        messages.put(message("user", question));
        return new JSONObject().put("model", model).put("messages", messages).put("stream", false)
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JSONObject message(String role, String content) throws JSONException {
        return new JSONObject().put("role", role).put("content", content);
    }

    static String readAnswer(InputStream in) throws IOException, JSONException {
        byte[] data = in.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (data.length > MAX_RESPONSE_BYTES) throw new IOException("Response too large");
        JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
        Object content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").get("content");
        if (!(content instanceof String text) || text.isBlank()) throw new JSONException("No text response");
        return text;
    }
}
