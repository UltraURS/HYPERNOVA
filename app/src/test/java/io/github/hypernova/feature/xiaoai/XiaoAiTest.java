// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import javax.crypto.KeyGenerator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class XiaoAiTest {
    private static final String TEST_KEY = "test-key-for-local-fixture";

    @Test public void switchesModelsAndRestoresDefaultAfterDeletingActiveModel() {
        var a = model("a", "第一个模型");
        var b = model("b", "第二个模型");
        var persona = persona(false);
        var config = withActivePersona(XiaoAiConfig.EMPTY.withModel(a).withModel(b), persona);
        assertEquals(XiaoAiConfig.DEFAULT_LABEL, config.selectedLabel());
        assertEquals(a, config.select(a.id()).selected());
        assertEquals(b, config.select(b.id()).selected());
        assertNull(config.select(a.id()).select(XiaoAiConfig.DEFAULT).selected());
        var edited = model(b.id(), "改名后的模型");
        assertEquals(edited, config.select(b.id()).withModel(edited).selected());
        var deleted = config.select(b.id()).withoutModel(b.id());
        assertNull(deleted.selected());
        assertEquals(a, deleted.models().get(a.id()));
        assertEquals(persona, deleted.persona());
        assertTrue(deleted.persona().referencesDefault(false));
        assertFalse(deleted.select(a.id()).persona().referencesDefault(true));
    }

    @Test public void acceptsHttpsBaseAndFullEndpointWithoutAmbiguity() {
        assertEquals("https://example.test/v1/chat/completions", XiaoAiConfig.chatEndpoint(" https://example.test/ "));
        assertEquals("https://example.test:8443/proxy/v1/chat/completions",
                XiaoAiConfig.chatEndpoint("https://example.test:8443/proxy/v1/"));
        String complete = "https://example.test/v1/chat/completions";
        assertEquals(complete, XiaoAiConfig.chatEndpoint(complete));
        for (String invalid : List.of("http://example.test", "https:example.test", "https://u:p@example.test",
                "https://example.test/v1?q=key", "https://example.test/v1#fragment", "https://example.test:65536")) {
            assertThrows(invalid, IllegalArgumentException.class, () -> XiaoAiConfig.chatEndpoint(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> new XiaoAiConfig.Model("a", "a",
                "https://example.test/v1", "foo", "token\r\nOther-Header: x"));
    }

    @Test public void encryptedLocalFileRoundTripsProfilesAndPromptsAndRejectsTampering() throws Exception {
        var profile = model("configured", "自定义");
        var config = withActivePersona(XiaoAiConfig.EMPTY.withModel(profile).select(profile.id()), persona(false));
        byte[] clear = config.toJson(new JSONObject()).toString().getBytes(StandardCharsets.UTF_8);
        var key = KeyGenerator.getInstance("AES").generateKey();
        byte[] sealed = XiaoAiStore.encrypt(key, clear);
        assertFalse(Arrays.equals(sealed, XiaoAiStore.encrypt(key, clear)));
        assertFalse(new String(sealed, StandardCharsets.ISO_8859_1).contains(TEST_KEY));
        byte[] opened = XiaoAiStore.decrypt(key, sealed);
        assertArrayEquals(clear, opened);
        assertEquals(config, XiaoAiConfig.fromJson(new JSONObject(new String(opened, StandardCharsets.UTF_8))));
        sealed[sealed.length - 1] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> XiaoAiStore.decrypt(key, sealed));
    }

    @Test public void sendsOnlyUserPersonalityAndNoAvatarToEachCustomReply() throws Exception {
        var prior = List.of(new ChatCompletions.Turn("你是谁", "我是助手"));
        var payload = new JSONObject(new String(ChatCompletions.body("demo-model", persona(false), prior, "你好"),
                StandardCharsets.UTF_8));
        assertEquals("demo-model", payload.getString("model"));
        assertFalse(payload.getBoolean("stream"));
        assertFalse(payload.has("key"));
        var messages = payload.getJSONArray("messages");
        assertEquals(4, messages.length());
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("身份：学习搭档\n性格：耐心\n表达风格：先说结论", messages.getJSONObject(0).getString("content"));
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        assertEquals("assistant", messages.getJSONObject(2).getString("role"));
        assertEquals("你好", messages.getJSONObject(3).getString("content"));
        assertFalse(payload.toString().contains("avatar-fixture"));
        var ordinary = new JSONObject(new String(ChatCompletions.body("demo-model", XiaoAiPersona.EMPTY, List.of(), "你好"),
                StandardCharsets.UTF_8));
        assertEquals(1, ordinary.getJSONArray("messages").length());
    }

    @Test public void givesCustomInstructionsPriorityOverDefaultReference() {
        String prompt = persona(true).systemPrompt();
        assertTrue(prompt.startsWith("以下用户设定优先"));
        assertTrue(prompt.indexOf("表达风格：先说结论") < prompt.indexOf("不冲突时，参考"));
        assertTrue(prompt.contains("小爱同学的友善、自然、清楚"));
    }

    @Test public void nativeReplyPreservesTheQuestionAndUsesDefaultAsFallbackEvenIfStrictWasSaved() {
        String question = "明天怎么安排？";
        var strict = persona(false);
        assertFalse(strict.referencesDefault(true));
        assertTrue(strict.referencesDefault(false));
        String query = strict.nativeQuery(question);
        assertTrue(query.startsWith(question + "\n\n"));
        assertTrue(query.contains("其余延续小爱同学原有的表达"));
        assertTrue(query.contains("身份：学习搭档"));
        assertEquals(question, XiaoAiPersona.EMPTY.nativeQuery(question));
        assertEquals(query, persona(true).nativeQuery(question));
        assertThrows(IllegalArgumentException.class, () -> new XiaoAiPersona("", " ", "", "", true, true));
    }

    @Test public void nativeQueryEchoHidesOnlyModuleGuidance() {
        String question = "解答题目";
        String echo = persona(false).nativeQuery(question);
        assertTrue(echo.contains("身份：学习搭档"));
        assertEquals(question, XiaoAiPersona.displayQuery(echo));
        assertEquals("", XiaoAiPersona.displayQuery(echo.substring(question.length()).stripLeading()));
        String older = echo.replace("请按原问题作答，系统操作按原指令执行。", "请按原问题作答。");
        assertEquals(question, XiaoAiPersona.displayQuery(older));
        String quoted = "什么是【本次回复的人格偏好】？";
        assertEquals(quoted, XiaoAiPersona.displayQuery(quoted));
        assertNull(XiaoAiPersona.displayQuery(null));
    }

    @Test public void upgradesLegacyModelStoreWithoutActivatingAnOldAgentPrompt() throws Exception {
        JSONObject old = new JSONObject("""
                {"models":[{"id":"id","alias":"旧模型","endpoint":"https://example.test/v1/chat/completions",
                "model":"model","key":"fixture"}],"selected":"id","agents":[{"id":"a","prompt":"旧提示词"}]}
                """);
        var restored = XiaoAiConfig.fromJson(old);
        assertEquals("旧模型", restored.selectedLabel());
        assertEquals(XiaoAiPersona.EMPTY, restored.persona());
        JSONObject saved = withActivePersona(restored, persona(true)).toJson(new JSONObject(old.toString()));
        assertEquals("旧提示词", saved.getJSONArray("agents").getJSONObject(0).getString("prompt"));
        assertFalse(XiaoAiConfig.fromJson(saved).persona().systemPrompt().contains("旧提示词"));
    }

    @Test public void upgradesSinglePersonaWithItsActiveStateAndAvatar() throws Exception {
        var previous = new JSONObject().put("models", new JSONArray()).put("selected", "")
                .put("persona", persona(false).toJson());
        var upgraded = XiaoAiConfig.fromJson(previous);
        assertEquals(persona(false), upgraded.persona());
        var saved = upgraded.toJson(new JSONObject(previous.toString()));
        assertEquals(1, saved.getJSONArray("personas").length());
        assertEquals(upgraded, XiaoAiConfig.fromJson(saved));
        assertFalse(saved.has("persona"));
        var disabled = XiaoAiConfig.fromJson(previous.put("persona", persona(false).toJson().put("enabled", false)));
        assertEquals(XiaoAiPersona.EMPTY, disabled.persona());
        var migrated = disabled.personas().values().iterator().next();
        assertEquals(persona(false), disabled.selectPersona(migrated.id()).persona());
    }

    @Test public void switchesEditsAndDeletesPersonasIndependently() throws Exception {
        var first = new XiaoAiConfig.PersonaProfile("first", "学习", persona(false));
        var second = new XiaoAiConfig.PersonaProfile("second", "写作", persona(true));
        var configured = XiaoAiConfig.EMPTY.withPersona(first).withPersona(second);
        assertEquals(XiaoAiPersona.EMPTY, configured.persona());
        assertEquals(first.persona(), configured.selectPersona(first.id()).persona());
        var edited = new XiaoAiConfig.PersonaProfile(second.id(), "写作搭档", second.persona());
        var selected = configured.selectPersona(second.id()).withPersona(edited).withoutPersona(first.id());
        assertEquals(second.persona(), selected.persona());
        assertEquals(edited, selected.personas().get(second.id()));
        assertEquals(selected, XiaoAiConfig.fromJson(selected.toJson(new JSONObject())));
        assertEquals(XiaoAiPersona.EMPTY, selected.withoutPersona(second.id()).persona());
        assertTrue(selected.withoutPersona(second.id()).personas().isEmpty());
        assertEquals(2, configured.selectPersona(first.id()).selectPersona(XiaoAiConfig.DEFAULT).personas().size());
    }

    @Test public void acceptsTextAndRejectsInvalidAndOversizedResponses() throws Exception {
        String valid = "{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}";
        assertEquals("你好", ChatCompletions.readAnswer(stream(valid)));
        for (String invalid : List.of("{}", "{\"choices\":[]}",
                "{\"choices\":[{\"message\":{\"content\":null}}]}",
                "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}")) {
            assertThrows(JSONException.class, () -> ChatCompletions.readAnswer(stream(invalid)));
        }
        assertThrows(IOException.class, () -> ChatCompletions.readAnswer(new ByteArrayInputStream(new byte[512 * 1024 + 1])));
    }

    private static XiaoAiConfig.Model model(String id, String alias) {
        return new XiaoAiConfig.Model(id, alias, "https://example.test/v1", "demo-model", TEST_KEY);
    }

    private static XiaoAiPersona persona(boolean referenceDefault) {
        return new XiaoAiPersona("学习搭档", "耐心", "先说结论", "avatar-fixture", true, referenceDefault);
    }

    private static XiaoAiConfig withActivePersona(XiaoAiConfig config, XiaoAiPersona persona) {
        var profile = new XiaoAiConfig.PersonaProfile("profile", "学习", persona);
        return config.withPersona(profile).selectPersona(profile.id());
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
