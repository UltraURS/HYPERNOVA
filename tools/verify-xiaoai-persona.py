# SPDX-License-Identifier: GPL-3.0-only
"""Run the real native persona hooks against the supplied APK's request handoffs."""
import argparse
from pathlib import Path
import runpy
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BASE = runpy.run_path(str(ROOT / 'tools/verify-sidebar-window-lifecycle.py'))['SOURCES']
PACKAGE = 'io/github/hypernova/feature/xiaoai/'

SOURCES = {
    'de/robv/android/xposed/XC_MethodHook.java': BASE['de/robv/android/xposed/XC_MethodHook.java'].replace(
        'public Object result;', '''final java.util.Map<String,Object> extras = new java.util.HashMap<>();
        public void setObjectExtra(String key, Object value) { extras.put(key, value); }
        public Object getObjectExtra(String key) { return extras.get(key); }
        public Object getResult() { return result; }
        public Object result;'''),
    'de/robv/android/xposed/XposedBridge.java': BASE['de/robv/android/xposed/XposedBridge.java']
        .replace('Map<Method,', 'Map<Executable,')
        .replace('hookMethod(Method method,', 'hookMethod(Executable method,')
        .replace('invoke(Method method,', 'invoke(Executable method,')
        .replace('method.invoke(object, args);', '''method instanceof Method target ? target.invoke(object, args)
                    : ((Constructor<?>) method).newInstance(args); p.result = result;''')
        .replace('return result;', 'return p.result;'),
    'de/robv/android/xposed/XposedHelpers.java': BASE['de/robv/android/xposed/XposedHelpers.java'].replace(
        '    public static Method findMethodExact(', '''    public static Constructor<?> findConstructorExact(Class<?> type, Class<?>... args) {
        try { return type.getConstructor(args); } catch (Exception error) { throw new RuntimeException(error); }
    }
    public static Method findMethodExact('''),
    'io/github/hypernova/hook/HookLog.java': BASE['io/github/hypernova/hook/HookLog.java'],
    'android/content/Intent.java': '''package android.content;
import java.util.*;
public class Intent {
    private final Map<String,Object> data = new HashMap<>();
    private String action;
    public Intent() {}
    public Intent(Intent original) { data.putAll(original.data); action = original.action; }
    public Intent setAction(String value) { action = value; return this; }
    public String getAction() { return action; }
    public Intent putExtra(String key, String value) { data.put(key,value); return this; }
    public Intent putExtra(String key, boolean value) { data.put(key,value); return this; }
    public String getStringExtra(String key) { return (String)data.get(key); }
    public boolean getBooleanExtra(String key, boolean fallback) { return (boolean)data.getOrDefault(key,fallback); }
}''',
    'h30/f0.java': '''package h30;
import android.content.Intent;
public class f0 {
    public record c(String text, String origin, String source, boolean offline) {}
    public enum e { DEFAULT }
    public Intent e(c params) { return new Intent().putExtra("assist_query",params.text())
        .putExtra("queryOrigin",params.origin()).putExtra("voice_assist_start_from_key",params.source())
        .putExtra("use_offline_nlp",params.offline()); }
    public com.xiaomi.ai.api.common.Event a(String text, boolean changeVendor, e tts, boolean renew, boolean search) {
        return new com.xiaomi.ai.api.common.Event(new com.xiaomi.ai.api.Nlp.RequestLargeLanguageModelContent(text));
    }
}''',
    's10/c.java': '''package s10;
import android.content.Intent;
public record c(String query) {
    public static class a {}
    public static c getRequestInfo(Intent params, a preset) { return new c(params.getStringExtra("assist_query")); }
}''',
    'com/xiaomi/voiceassistant/SpeechQueryService.java': '''package com.xiaomi.voiceassistant;
public class SpeechQueryService {
    public int onStartCommand(android.content.Intent intent, int flags, int startId) { return 2; }
}''',
    'd30/c.java': 'package d30; public record c(String query) {}',
    'd30/f.java': 'package d30; public class f {}',
    't10/x0.java': '''package t10;
public class x0 {
    public m10.s0.d next;
    public void l(d30.c request, d30.f tts, s10.c parsed) { next = new m10.s0.d(request); }
}''',
    'm10/s0.java': 'package m10; public class s0 { public static class d { public d30.c d; public d(d30.c d) { this.d=d; } } }',
    'm10/b1.java': '''package m10;
public class b1 {
    public void r(android.content.Intent intent) {}
}''',
    'com/xiaomi/ai/api/Nlp.java': '''package com.xiaomi.ai.api;
public class Nlp {
    public record Request(String query) {}
    public static class RequestLargeLanguageModelContent {
        private String query;
        public RequestLargeLanguageModelContent(String query) { this.query=query; }
        public String getQuery() { return query; }
        public RequestLargeLanguageModelContent setQuery(String query) { this.query=query; return this; }
    }
}''',
    'com/xiaomi/ai/api/common/Message.java': '''package com.xiaomi.ai.api.common;
public class Message {
    private final Object payload;
    public Message(Object payload) { this.payload=payload; }
    public Object getPayload() { return payload; }
}''',
    'com/xiaomi/ai/api/common/Event.java': '''package com.xiaomi.ai.api.common;
public class Event extends Message {
    public Event(Object payload) { super(payload); }
}''',
    'iw/a.java': '''package iw;
public record a(Object value) {
    public boolean isPresent() { return value != null; }
    public Object get() { return value; }
    public static a ofNullable(Object value) { return new a(value); }
}''',
    'com/xiaomi/ai/api/Template.java': '''package com.xiaomi.ai.api;
public class Template {
    public record Query(String text, String info) {
        public String getText() { return text; }
        public iw.a getTextInfo() { return iw.a.ofNullable(info); }
    }
    public record Toast(String query) {
        public iw.a getQuery() { return iw.a.ofNullable(query); }
    }
}''',
    'j70/c0.java': 'package j70; public class c0 {}',
    'com/xiaomi/voiceassistant/instruction/card/stream/a.java': '''package com.xiaomi.voiceassistant.instruction.card.stream;
public class a {
    public final String question, info;
    public a(int i, String question, String id, boolean origin, String app, boolean flag,
            j70.c0 mode, boolean lazy, boolean visible, String info) {
        this.question = question;
        this.info = info;
    }
}''',
    'm10/m0.java': '''package m10;
import com.xiaomi.ai.api.Nlp;
import de.robv.android.xposed.XposedBridge;
public class m0 {
    public Nlp.Request request;
    public boolean I0(s0.d params) throws Exception {
        request = (Nlp.Request)XposedBridge.invoke(Nlp.Request.class.getConstructor(String.class), null, params.d.query());
        return true;
    }
    public boolean J0(s0.d params) throws Exception {
        request = (Nlp.Request)XposedBridge.invoke(Nlp.Request.class.getConstructor(String.class), null, params.d.query());
        return true;
    }
}''',
    PACKAGE + 'XiaoAiStore.java': '''package io.github.hypernova.feature.xiaoai;
final class XiaoAiStore {
    volatile XiaoAiConfig state;
    XiaoAiConfig read() { return state; }
}''',
    PACKAGE + 'Regression.java': '''package io.github.hypernova.feature.xiaoai;
import android.content.Intent;
import de.robv.android.xposed.*;
import java.util.concurrent.*;
public class Regression {
    static final String QUESTION = "What is a palindrome?";
    static final String IDENTITY = "EXAMPLE_PERSONA";
    static final XiaoAiPersona PERSONA = new XiaoAiPersona(IDENTITY,"patient","brief","avatar",true,false);
    static final String MARKER = "io.github.hypernova.xiaoai.PERSONA";
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static Intent typed(String origin, boolean offline) {
        return query(origin, "", offline);
    }
    static Intent query(String origin, String source, boolean offline) {
        return (Intent) XposedHelpers.callMethod(new h30.f0(), "e", new h30.f0.c(QUESTION,origin,source,offline));
    }
    static String largeModel() {
        var event = (com.xiaomi.ai.api.common.Event) XposedHelpers.callMethod(new h30.f0(), "a",
            QUESTION, false, h30.f0.e.DEFAULT, false, false);
        return ((com.xiaomi.ai.api.Nlp.RequestLargeLanguageModelContent)event.getPayload()).getQuery();
    }
    static Intent recognizedSpeech(String source) {
        return new Intent().setAction("com.miui.voiceassist.query").putExtra("assist_query",QUESTION)
            .putExtra("queryOrigin","com.miui.voiceassist.query&&asr_proxy_service")
            .putExtra("voice_assist_start_from_key",source).putExtra("use_offline_nlp",false);
    }
    static String deliver(Intent original, String method) throws Exception {
        Intent copy = new Intent(original);
        s10.c info = (s10.c)XposedBridge.invoke(s10.c.class.getMethod("getRequestInfo",Intent.class,s10.c.a.class),
            null,copy,null);
        t10.x0 task = new t10.x0();
        XposedHelpers.callMethod(task,"l",new d30.c(info.query()),null,info);
        m10.m0 engine = new m10.m0();
        XposedHelpers.callMethod(engine,method,task.next);
        check(info.query().equals(QUESTION) && task.next.d.query().equals(QUESTION),
            "Persona must not edit the query the native UI sees");
        String outgoing = engine.request.query();
        XposedHelpers.callMethod(engine,method,new m10.s0.d(new d30.c("another request")));
        check(engine.request.query().equals("another request"),"No persona may leak to the next unrelated engine request");
        return outgoing;
    }
    public static void main(String[] args) throws Exception {
        var profile = new XiaoAiConfig.PersonaProfile("example", "Example", PERSONA);
        var store = new XiaoAiStore(); store.state = XiaoAiConfig.EMPTY.withPersona(profile).selectPersona(profile.id());
        var hook = new XiaoAiNativePersona(store);
        hook.install(Regression.class.getClassLoader());
        XiaoAiQueryDisplay.install(Regression.class.getClassLoader());
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Intent floatingKeyboard = typed("QueryEditBar",false);
            String prompt = worker.submit(() -> deliver(floatingKeyboard,"I0")).get();
            check(prompt.startsWith(QUESTION) && prompt.contains(IDENTITY) && prompt.contains("原有的表达"),
                "The floating keyboard must use the saved persona even before the app UI opens");
            var reply = new com.xiaomi.ai.api.Template.Query(prompt, prompt.substring(QUESTION.length()).stripLeading());
            check(QUESTION.equals(XposedHelpers.callMethod(reply,"getText")),"The visible query card leaked the prompt");
            check(!((iw.a)XposedHelpers.callMethod(reply,"getTextInfo")).isPresent(),"A prompt-only subtitle must be removed");
            var toast = new com.xiaomi.ai.api.Template.Toast(prompt);
            check(QUESTION.equals(((iw.a)XposedHelpers.callMethod(toast,"getQuery")).get()),"The floating result leaked the prompt");
            var ctor = com.xiaomi.voiceassistant.instruction.card.stream.a.class.getConstructors()[0];
            var card = (com.xiaomi.voiceassistant.instruction.card.stream.a) XposedBridge.invoke(ctor, null,
                0, prompt, "answer-id", false, "", false, new j70.c0(), false, true, prompt);
            check(card.question.equals(QUESTION) && card.info.equals(QUESTION),"Restored query cards must hide the prompt");
            Intent tts = typed("QueryEditBar",false);
            check(worker.submit(() -> deliver(tts,"J0")).get().equals(prompt),"TTS text requests use the same style");
            Intent legacyKeyboard = query(null,"QueryEditBar",false);
            check(worker.submit(() -> deliver(legacyKeyboard,"I0")).get().equals(prompt),"Legacy floating keyboard was missed");
            Intent gestureKeyboard = new Intent().putExtra("assist_query",QUESTION)
                .putExtra("queryOrigin","android.intent.action.ASSIST&&double_click_fullscreen_gesture_line");
            XposedHelpers.callMethod(new m10.b1(),"r",gestureKeyboard);
            check(worker.submit(() -> deliver(gestureKeyboard,"I0")).get().equals(prompt),
                "A double-tap keyboard query must be tagged at text dispatch when it bypasses the Intent builder");
            Intent offlineGesture = new Intent().putExtra("assist_query",QUESTION)
                .putExtra("voice_assist_start_from_key","double_click_fullscreen_gesture_line").putExtra("use_offline_nlp",true);
            XposedHelpers.callMethod(new m10.b1(),"r",offlineGesture);
            check(deliver(offlineGesture,"I0").equals(QUESTION),"Explicitly offline gesture query was touched");
            Intent correctedSpeech = query(null,"correct_query_list",false);
            check(worker.submit(() -> deliver(correctedSpeech,"J0")).get().equals(prompt),"Corrected speech text was missed");
            Intent speech = recognizedSpeech("AsrProxyService");
            check(deliver(speech,"J0").equals(QUESTION),"An ASR origin alone must not attach a persona");
            var service = new com.xiaomi.voiceassistant.SpeechQueryService();
            XposedHelpers.callMethod(service,"onStartCommand",speech,0,1);
            check(worker.submit(() -> deliver(speech,"J0")).get().equals(prompt),"ASR transcript query was missed");
            Intent unrelated = recognizedSpeech("OtherService");
            XposedHelpers.callMethod(service,"onStartCommand",unrelated,0,2);
            check(deliver(unrelated,"I0").equals(QUESTION),"Unrelated service query was touched");
            check(worker.submit(Regression::largeModel).get().equals(prompt),"Direct LLM replies must use the persona");
            check(deliver(typed("QueryEditBar",true),"I0").equals(QUESTION),"Offline request was touched");
            check(deliver(typed("AiPush",false),"I0").equals(QUESTION),"Background origin was touched");
            Intent forged = new Intent().putExtra("assist_query",QUESTION).putExtra("queryOrigin","QueryEditBar")
                .putExtra("use_offline_nlp",false).putExtra(MARKER,"forged");
            check(deliver(forged,"I0").equals(QUESTION),
                "Unrecognized marker must not attach a persona");
            store.state = store.state.withModel(new XiaoAiConfig.Model("a","Example","https://example.test/v1","test","dummy")).select("a");
            check(deliver(typed("QueryEditBar",false),"I0").equals(prompt),
                "The floating native request must supplement the default personality when a custom model is selected in the app");
            Intent queued = typed("QueryEditBar",false);
            store.state = store.state.selectPersona(XiaoAiConfig.DEFAULT);
            check(worker.submit(() -> deliver(queued,"I0")).get().equals(QUESTION) && largeModel().equals(QUESTION),
                "Disabled persona must not be attached to a queued request");
        }
        System.out.println("XiaoAi persona OK: gesture keyboard, ASR transcripts, both NLP payloads, LLM events, query echoes");
    }
}''',
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--json', type=Path, required=True, help='org.json test dependency JAR')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='xiaoai-persona-', dir=ROOT / '.local') as directory:
        temp = Path(directory)
        for name, code in SOURCES.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(code, encoding='utf-8')
        main_java = ROOT / 'app/src/main/java' / PACKAGE
        java_sources = [main_java / (name + '.java') for name in
                        ('XiaoAiNativePersona', 'XiaoAiQueryDisplay', 'XiaoAiPersona', 'XiaoAiConfig')]
        subprocess.run([str(args.jdk / 'bin/javac.exe'), '-encoding', 'UTF-8', '-cp', str(args.json), '-d', str(temp / 'out')]
                       + [str(path) for path in java_sources + list(temp.rglob('*.java'))], check=True)
        subprocess.run([str(args.jdk / 'bin/java.exe'), '-cp', str(temp / 'out') + ';' + str(args.json),
                        'io.github.hypernova.feature.xiaoai.Regression'], check=True)


if __name__ == '__main__':
    main()
