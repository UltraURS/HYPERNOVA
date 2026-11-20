// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import org.json.JSONException;
import org.json.JSONObject;

record XiaoAiPersona(String identity, String character, String style, String avatar,
                     boolean enabled, boolean referenceDefault) {
    static final int TEXT_LIMIT = 1600;
    static final int AVATAR_LIMIT = 192 * 1024;
    static final XiaoAiPersona EMPTY = new XiaoAiPersona("", "", "", "", false, true);
    private static final String DEFAULT_STYLE = "以小爱同学的友善、自然、清楚的表达帮助用户。";
    private static final String NATIVE_HEADING = "【本次回复的人格偏好】";
    private static final String NATIVE_GUIDANCE = "请按原问题作答，系统操作按原指令执行。表达时优先使用以下设定，其余延续小爱同学原有的表达。";
    private static final String LEGACY_GUIDANCE = "请按原问题作答。表达时优先使用以下设定，其余延续小爱同学原有的表达。";

    XiaoAiPersona {
        identity = trim(identity);
        character = trim(character);
        style = trim(style);
        if (enabled && identity.isEmpty() && character.isEmpty() && style.isEmpty()) {
            throw new IllegalArgumentException("请至少填写一项人格设定");
        }
        if (avatar.length() > AVATAR_LIMIT) throw new IllegalArgumentException("头像文件过大");
    }

    boolean referencesDefault(boolean customModel) { return !customModel || referenceDefault; }

    String systemPrompt() {
        if (!enabled) return "";
        String custom = instructions();
        if (!referenceDefault) return custom;
        return "以下用户设定优先。\n" + custom
                + "\n\n仅在用户设定没有涉及且不冲突时，参考以下默认人格。\n" + DEFAULT_STYLE;
    }

    String nativeQuery(String question) {
        if (!enabled || question.isBlank()) return question;
        return question + "\n\n" + NATIVE_HEADING + "\n" + NATIVE_GUIDANCE + "\n" + instructions();
    }

    static String displayQuery(String query) {
        if (query == null) return null;
        int heading = query.indexOf(NATIVE_HEADING);
        while (heading >= 0) {
            String suffix = query.substring(heading + NATIVE_HEADING.length()).stripLeading();
            if (suffix.startsWith(NATIVE_GUIDANCE) || suffix.startsWith(LEGACY_GUIDANCE)) {
                return query.substring(0, heading).stripTrailing();
            }
            heading = query.indexOf(NATIVE_HEADING, heading + NATIVE_HEADING.length());
        }
        return query;
    }

    private String instructions() {
        StringBuilder result = new StringBuilder();
        append(result, "身份", identity);
        append(result, "性格", character);
        append(result, "表达风格", style);
        return result.toString().stripTrailing();
    }

    private static void append(StringBuilder result, String field, String value) {
        if (!value.isEmpty()) result.append(field).append("：").append(value).append('\n');
    }

    private static String trim(String value) {
        String text = value.strip();
        if (text.length() > TEXT_LIMIT) throw new IllegalArgumentException("每项人格设定最多 1600 字");
        return text;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("identity", identity).put("character", character).put("style", style)
                .put("avatar", avatar).put("enabled", enabled).put("referenceDefault", referenceDefault);
    }

    static XiaoAiPersona fromJson(JSONObject json) {
        if (json == null) return EMPTY;
        return new XiaoAiPersona(json.optString("identity"), json.optString("character"),
                json.optString("style"), json.optString("avatar"), json.optBoolean("enabled"),
                json.optBoolean("referenceDefault", true));
    }

    @Override public String toString() { return "XiaoAiPersona"; }
}
