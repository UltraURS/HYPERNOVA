// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

record XiaoAiConfig(Map<String, Model> models, String selectedId, Map<String, PersonaProfile> personas,
                   String activePersonaId) {
    static final String DEFAULT = "";
    static final String DEFAULT_LABEL = "小爱默认模型";
    static final XiaoAiConfig EMPTY = new XiaoAiConfig(Map.of(), DEFAULT, Map.of(), DEFAULT);

    record Model(String id, String alias, String endpoint, String modelId, String apiKey) {
        Model {
            alias = required(alias, 64, "模型名称");
            endpoint = chatEndpoint(endpoint);
            modelId = required(modelId, 256, "模型 ID");
            apiKey = required(apiKey, 2048, "API Key");
            if (!apiKey.chars().allMatch(c -> c >= 33 && c <= 126)) {
                throw new IllegalArgumentException("API Key 中不能有空格或换行");
            }
        }
        @Override public String toString() { return "XiaoAiModel"; }
    }

    record PersonaProfile(String id, String name, XiaoAiPersona persona) {
        static final int NAME_LIMIT = 40;

        PersonaProfile {
            id = required(id, 64, "人格 ID");
            name = required(name, NAME_LIMIT, "人格名称");
        }
    }

    XiaoAiConfig {
        models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
        if (!models.containsKey(selectedId)) selectedId = DEFAULT;
        personas = Collections.unmodifiableMap(new LinkedHashMap<>(personas));
        PersonaProfile active = personas.get(activePersonaId);
        if (active == null || !active.persona().enabled()) activePersonaId = DEFAULT;
    }

    Model selected() { return models.get(selectedId); }
    String selectedLabel() { return selected() == null ? DEFAULT_LABEL : selected().alias(); }

    XiaoAiPersona persona() {
        PersonaProfile active = personas.get(activePersonaId);
        return active == null ? XiaoAiPersona.EMPTY : active.persona();
    }

    XiaoAiConfig select(String id) { return new XiaoAiConfig(models, id, personas, activePersonaId); }

    XiaoAiConfig selectPersona(String id) { return new XiaoAiConfig(models, selectedId, personas, id); }

    XiaoAiConfig withModel(Model model) {
        var next = new LinkedHashMap<>(models);
        next.put(model.id(), model);
        return new XiaoAiConfig(next, selectedId, personas, activePersonaId);
    }

    XiaoAiConfig withoutModel(String id) {
        var next = new LinkedHashMap<>(models);
        next.remove(id);
        return new XiaoAiConfig(next, selectedId, personas, activePersonaId);
    }

    XiaoAiConfig withPersona(PersonaProfile profile) {
        var next = new LinkedHashMap<>(personas);
        next.put(profile.id(), profile);
        return new XiaoAiConfig(models, selectedId, next, activePersonaId);
    }

    XiaoAiConfig withoutPersona(String id) {
        var next = new LinkedHashMap<>(personas);
        next.remove(id);
        return new XiaoAiConfig(models, selectedId, next, activePersonaId);
    }

    JSONObject toJson(JSONObject document) throws JSONException {
        JSONArray modelList = new JSONArray();
        for (Model model : models.values()) {
            modelList.put(new JSONObject().put("id", model.id()).put("alias", model.alias())
                    .put("endpoint", model.endpoint()).put("model", model.modelId()).put("key", model.apiKey()));
        }
        JSONArray personaList = new JSONArray();
        for (PersonaProfile profile : personas.values()) {
            personaList.put(profile.persona().toJson().put("id", profile.id()).put("name", profile.name()));
        }
        document.remove("persona");
        return document.put("models", modelList).put("selected", selectedId)
                .put("personas", personaList).put("activePersona", activePersonaId);
    }

    static XiaoAiConfig fromJson(JSONObject json) throws JSONException {
        Map<String, Model> models = new LinkedHashMap<>();
        JSONArray modelList = json.getJSONArray("models");
        for (int i = 0; i < modelList.length(); i++) {
            JSONObject item = modelList.getJSONObject(i);
            Model model = new Model(item.getString("id"), item.getString("alias"), item.getString("endpoint"),
                    item.getString("model"), item.getString("key"));
            models.put(model.id(), model);
        }
        Map<String, PersonaProfile> personas = new LinkedHashMap<>();
        String active = json.optString("activePersona");
        if (json.has("personas")) {
            JSONArray personaList = json.getJSONArray("personas");
            for (int i = 0; i < personaList.length(); i++) {
                JSONObject item = personaList.getJSONObject(i);
                PersonaProfile profile = new PersonaProfile(item.getString("id"), item.getString("name"),
                        XiaoAiPersona.fromJson(item));
                personas.put(profile.id(), profile);
            }
        } else {
            // A disabled legacy profile keeps its settings and remains unselected after migration.
            XiaoAiPersona previous = XiaoAiPersona.fromJson(json.optJSONObject("persona"));
            if (!previous.equals(XiaoAiPersona.EMPTY)) {
                boolean hasPrompt = !previous.identity().isEmpty() || !previous.character().isEmpty() || !previous.style().isEmpty();
                XiaoAiPersona stored = new XiaoAiPersona(previous.identity(), previous.character(), previous.style(),
                        previous.avatar(), hasPrompt, previous.referenceDefault());
                PersonaProfile profile = new PersonaProfile("legacy", "原有人格", stored);
                personas.put(profile.id(), profile);
                if (previous.enabled()) active = profile.id();
            }
        }
        return new XiaoAiConfig(models, json.getString("selected"), personas, active);
    }

    static String chatEndpoint(String address) {
        try {
            URI uri = new URI(required(address, 512, "API 地址")).normalize();
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getPort() != 0 && uri.getPort() <= 65535) {
                String path = uri.getRawPath().replaceFirst("/+$", "");
                if (path.isEmpty()) path = "/v1";
                if (!path.endsWith("/chat/completions")) path += "/chat/completions";
                return "https://" + uri.getRawAuthority() + path;
            }
        } catch (URISyntaxException ignored) { }
        throw new IllegalArgumentException("请填写有效的 HTTPS API 地址，地址中不要包含账号、查询参数或片段");
    }

    private static String required(String value, int max, String label) {
        String text = value.strip();
        if (text.isEmpty() || text.length() > max) {
            throw new IllegalArgumentException(label + "须填写，最多 " + max + " 个字符");
        }
        return text;
    }
}
