// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLException;
import org.json.JSONException;
import io.github.hypernova.feature.xiaoai.XiaoAiConfig.Model;
import io.github.hypernova.hook.HookLog;

final class XiaoAiDialogs {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int MULTILINE = TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    private final XiaoAiStore store;
    private final ChatCompletions completions;
    private final XiaoAiMiuix ui;

    XiaoAiDialogs(XiaoAiStore store, ChatCompletions completions, XiaoAiMiuix ui) {
        this.store = store;
        this.completions = completions;
        this.ui = ui;
    }

    void models(Activity activity, Runnable changed) {
        XiaoAiConfig config = read(activity);
        if (config == null) return;
        List<Model> models = new ArrayList<>(config.models().values());
        String[] labels = new String[models.size() + 1];
        labels[0] = XiaoAiConfig.DEFAULT_LABEL;
        int selected = 0;
        for (int i = 0; i < models.size(); i++) {
            Model model = models.get(i);
            labels[i + 1] = model.alias() + " · " + model.modelId();
            if (model.id().equals(config.selectedId())) selected = i + 1;
        }
        int[] checked = { selected };
        XiaoAiMiuix.Builder builder = ui.dialog(activity, "自定义 API Key")
                .setSingleChoiceItems(labels, selected, (dialog, index) -> {
                    XiaoAiConfig current = read(activity);
                    String id = index == 0 ? XiaoAiConfig.DEFAULT : models.get(index - 1).id();
                    if (current != null && save(activity, current.select(id))) {
                        checked[0] = index;
                        changed.run();
                    } else {
                        XiaoAiMiuix.items(dialog).setItemChecked(checked[0], true);
                    }
                })
                .setPositiveButton("添加模型", (dialog, which) -> modelForm(activity, null, changed))
                .setNegativeButton("关闭", null);
        if (!models.isEmpty()) {
            builder.setNeutralButton("编辑或删除", (dialog, which) ->
                    ui.dialog(activity, "选择要编辑的模型")
                            .setItems(modelLabels(models), (choice, index) -> modelForm(activity, models.get(index), changed))
                            .show(false, null));
        }
        builder.show(false, null);
    }

    private void modelForm(Activity activity, Model existing, Runnable changed) {
        LinearLayout content = column(activity);
        EditText alias = field(content, "模型名称", existing == null ? "" : existing.alias(), TEXT, 64);
        EditText address = field(content, "HTTPS API 地址", existing == null ? "" : existing.endpoint(),
                TEXT | InputType.TYPE_TEXT_VARIATION_URI, 512);
        address.setHint("https://example.com/v1");
        EditText modelId = field(content, "模型 ID", existing == null ? "" : existing.modelId(), TEXT, 256);
        EditText key = field(content, "API Key", "", TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 2048);
        if (existing != null) key.setHint("留空沿用已有 Key");
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        key.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        key.setSaveEnabled(false);
        content.addView(label(activity, "填写兼容 Chat Completions 的接口和服务商提供的模型 ID。"));
        XiaoAiMiuix.Builder builder = ui.dialog(activity, existing == null ? "添加模型" : "编辑模型")
                .setView(scroll(content)).setNegativeButton("返回", (dialog, which) -> models(activity, changed))
                .setPositiveButton("保存", null);
        if (existing != null) {
            builder.setNeutralButton("删除", (dialog, which) -> confirmDelete(activity, existing.alias(), () -> {
                XiaoAiConfig current = read(activity);
                if (current != null && save(activity, current.withoutModel(existing.id()))) changed.run();
                models(activity, changed);
            }, () -> modelForm(activity, existing, changed)));
        }
        Dialog dialog = builder.show(true, null);
        XiaoAiMiuix.button(dialog, DialogInterface.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                String endpoint = XiaoAiConfig.chatEndpoint(value(address));
                String token = value(key);
                if (token.isEmpty() && existing != null) {
                    if (!endpoint.equals(existing.endpoint())) {
                        key.setError("更换 API 地址后请重新填写 Key");
                        return;
                    }
                    token = existing.apiKey();
                }
                String id = existing == null ? UUID.randomUUID().toString() : existing.id();
                Model model = new Model(id, value(alias), endpoint, value(modelId), token);
                XiaoAiConfig current = read(activity);
                if (current == null || !save(activity, current.withModel(model))) return;
                dialog.dismiss();
                changed.run();
                models(activity, changed);
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    void conversation(Activity activity, Model model, XiaoAiPersona persona, String draft) {
        new Conversation(activity, model, persona, draft);
    }

    private final class Conversation {
        private final Activity activity;
        private final Model model;
        private final List<ChatCompletions.Turn> history = new ArrayList<>();
        private final TextView transcript, status, disclosure;
        private final ImageView avatar;
        private final ScrollView messages;
        private final EditText input;
        private final Dialog dialog;
        private final Button sendButton;
        private String speaker;
        private ChatCompletions.Call pending;

        Conversation(Activity activity, Model model, XiaoAiPersona persona, String draft) {
            this.activity = activity;
            this.model = model;
            LinearLayout content = column(activity);
            String destination = URI.create(model.endpoint()).getRawAuthority();
            LinearLayout header = new LinearLayout(activity);
            header.setGravity(Gravity.CENTER_VERTICAL);
            avatar = XiaoAiAvatar.view(activity, "");
            LinearLayout.LayoutParams picture = new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 36));
            picture.setMarginEnd(dp(activity, 10));
            header.addView(avatar, picture);
            header.addView(label(activity, model.modelId() + " · " + destination));
            content.addView(header);
            disclosure = label(activity, "");
            content.addView(disclosure);
            showPersona(persona);
            transcript = label(activity, "");
            transcript.setTextIsSelectable(true);
            messages = scroll(transcript);
            int height = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.22f);
            content.addView(messages, new LinearLayout.LayoutParams(-1, Math.min(dp(activity, 240), height)));
            status = label(activity, "");
            content.addView(status);
            input = field(content, "消息", draft, MULTILINE, 8192);
            input.setMaxLines(3);
            dialog = ui.dialog(activity, model.alias()).setView(scroll(content))
                    .setPositiveButton("发送", null).setNegativeButton("关闭", null).show(false, this::cancel);
            sendButton = XiaoAiMiuix.button(dialog, DialogInterface.BUTTON_POSITIVE);
            sendButton.setOnClickListener(button -> send());
        }

        private void showPersona(XiaoAiPersona persona) {
            speaker = persona.enabled() ? "小爱同学" : model.alias();
            avatar.setVisibility(persona.enabled() ? View.VISIBLE : View.GONE);
            if (persona.enabled()) XiaoAiAvatar.display(avatar, persona.avatar());
            disclosure.setText(persona.enabled() ? "发送后，此窗口的对话和人格设定会交给该 API。"
                    : "发送后，此窗口的对话会交给该 API。");
        }

        private void send() {
            String question = value(input);
            if (pending != null || question.isEmpty()) return;
            XiaoAiConfig current = read(activity);
            if (current == null) return;
            XiaoAiPersona persona = current.persona();
            showPersona(persona);
            pending = completions.new Call();
            ChatCompletions.Call call = pending;
            List<ChatCompletions.Turn> context = List.copyOf(history);
            input.setText("");
            sendButton.setEnabled(false);
            status.setText("等待模型回复…");
            render(question);
            new Thread(() -> {
                try {
                    String answer = call.run(model, persona, context, question);
                    activity.runOnUiThread(() -> finish(call, question, answer, null));
                } catch (Exception error) {
                    String message = failureMessage(error);
                    activity.runOnUiThread(() -> finish(call, question, null, message));
                } catch (LinkageError error) {
                    HookLog.error("XiaoAi request", error);
                    activity.runOnUiThread(() -> finish(call, question, null, "当前版本无法连接自定义模型。"));
                }
            }, "HYPERNOVA-XiaoAi-request").start();
        }

        private void finish(ChatCompletions.Call call, String question, String answer, String error) {
            if (pending != call || !dialog.isShowing() || activity.isDestroyed()) return;
            pending = null;
            sendButton.setEnabled(true);
            if (error == null) {
                history.add(new ChatCompletions.Turn(question, answer));
                if (history.size() > 8) history.remove(0);
                status.setText("");
            } else {
                status.setText(error);
                if (value(input).isEmpty()) input.setText(question);
            }
            render(null);
        }

        private void render(String pendingQuestion) {
            StringBuilder text = new StringBuilder();
            for (ChatCompletions.Turn turn : history) {
                text.append("你\n").append(turn.question()).append("\n\n")
                        .append(speaker).append('\n').append(turn.answer()).append("\n\n");
            }
            if (pendingQuestion != null) text.append("你\n").append(pendingQuestion);
            transcript.setText(text.toString().stripTrailing());
            messages.post(() -> messages.fullScroll(View.FOCUS_DOWN));
        }

        private void cancel() {
            if (pending != null) pending.cancel();
            pending = null;
        }
    }

    private XiaoAiConfig read(Activity activity) {
        try { return store.read(); }
        catch (Exception error) {
            HookLog.error("XiaoAi read configuration", error);
            Toast.makeText(activity, "无法读取本机的小爱配置", Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private boolean save(Activity activity, XiaoAiConfig config) {
        try { store.save(config); return true; }
        catch (Exception error) {
            HookLog.error("XiaoAi save configuration", error);
            Toast.makeText(activity, "无法保存本机的小爱配置", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static String failureMessage(Exception error) {
        if (error instanceof ChatCompletions.HttpFailure http) {
            if (http.status == 401 || http.status == 403) return "API 拒绝了请求，请检查 Key 和模型权限。";
            if (http.status == 429) return "请求受限，请检查服务额度或稍后再试。";
            if (http.status >= 300 && http.status < 400) return "接口发生重定向，请填写最终的 HTTPS 地址。";
            return "接口请求失败（HTTP " + http.status + "）。";
        }
        if (error instanceof JSONException) return "接口未返回可显示的文本，请检查接口是否兼容。";
        if (error instanceof SSLException) return "无法验证 API 的 HTTPS 连接。";
        return "无法连接 API，请检查网络和接口地址后重试。";
    }

    private void confirmDelete(Activity activity, String name, Runnable delete, Runnable back) {
        ui.dialog(activity, "删除「" + name + "」")
                .setPositiveButton("删除", (dialog, which) -> delete.run())
                .setNegativeButton("取消", (dialog, which) -> back.run()).show(false, null);
    }

    private static String[] modelLabels(List<Model> models) {
        return models.stream().map(model -> model.alias() + " · " + model.modelId()).toArray(String[]::new);
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(activity, 24);
        content.setPadding(horizontal, dp(activity, 12), horizontal, dp(activity, 8));
        return content;
    }

    private TextView label(Activity activity, CharSequence text) {
        TextView view = ui.text(activity);
        view.setTextSize(14);
        view.setText(text);
        return view;
    }

    private static ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(child.getContext());
        scroll.addView(child);
        return scroll;
    }

    private EditText field(LinearLayout parent, String label, String initial, int type, int max) {
        Activity activity = (Activity) parent.getContext();
        TextView caption = label(activity, label);
        caption.setPadding(0, dp(activity, 10), 0, 0);
        EditText field = ui.edit(activity);
        field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
        field.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        field.setInputType(type);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        field.setText(initial);
        field.setSelection(field.length());
        parent.addView(caption);
        parent.addView(field);
        return field;
    }

    private static int dp(Activity activity, int dp) {
        return Math.round(activity.getResources().getDisplayMetrics().density * dp);
    }

    private static String value(EditText field) { return field.getText().toString().strip(); }
}
