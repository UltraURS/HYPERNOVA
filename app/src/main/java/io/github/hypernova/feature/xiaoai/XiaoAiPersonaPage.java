// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.util.UUID;
import java.util.function.Consumer;
import io.github.hypernova.feature.xiaoai.XiaoAiConfig.PersonaProfile;
import io.github.hypernova.hook.HookLog;

@SuppressLint("ViewConstructor") // Created in the host with its Activity and configuration store.
final class XiaoAiPersonaPage extends FrameLayout {
    private static final String TAG = "hypernova_xiaoai_persona_page";
    private static final PathInterpolator EASE = new PathInterpolator(0.22f, 0.68f, 0.26f, 1f);
    private final Activity activity;
    private final XiaoAiStore store;
    private final XiaoAiMiuix ui;
    private final View hostContent;
    private final int hostAccessibility;
    private final int background, surface, primary, secondary, accent, accentSurface, danger, divider;
    private final boolean night;
    private LinearLayout visiblePage, editorPage, cropPage;
    private AnimatorSet transition;
    private boolean closing;
    private XiaoAiAvatar picker;

    @SuppressLint("ResourceType") // These colors belong to the verified XiaoAi APK.
    private XiaoAiPersonaPage(Activity activity, XiaoAiStore store, XiaoAiMiuix ui, View hostContent) {
        super(activity);
        this.activity = activity;
        this.store = store;
        this.ui = ui;
        this.hostContent = hostContent;
        hostAccessibility = hostContent.getImportantForAccessibility();
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        background = activity.getColor(0x7f060a11); // miuix_settings_window_bg_color
        surface = activity.getColor(0x7f060cf4); // xm_common_white_dialog_bg
        primary = activity.getColor(0x7f060ceb); // xm_common_text_color_black
        secondary = activity.getColor(0x7f060ce9); // xm_common_text_color_666
        accentSurface = activity.getColor(0x7f060054); // ai_settings_sub_item_selected_bg_color
        accent = night ? 0xffa1c5ff : 0xff1a61c6;
        danger = night ? 0xffff8c88 : 0xffbd2826;
        divider = night ? 0x16ffffff : 0x0f000000;
        setTag(TAG);
        setBackgroundColor(background);
        setClickable(true);
        setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
    }

    static void open(Activity activity, XiaoAiStore store, XiaoAiMiuix ui) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0 || content.findViewWithTag(TAG) != null) return;
        var page = new XiaoAiPersonaPage(activity, store, ui, content.getChildAt(0));
        XiaoAiConfig config = page.read();
        if (config == null) return;
        page.showList(config, 0);
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.hostContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        page.requestApplyInsets();
        if (ValueAnimator.areAnimatorsEnabled()) page.slide(null, page.visiblePage, 1);
    }

    static boolean handleBack(Activity activity) {
        if (!(activity.getWindow().getDecorView().findViewWithTag(TAG) instanceof XiaoAiPersonaPage page)) return false;
        page.back();
        return true;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return closing || transition != null || super.onInterceptTouchEvent(event);
    }

    private void back() {
        if (closing) return;
        if (cropPage != null) {
            finishCrop();
        } else if (editorPage != null) {
            XiaoAiConfig config = read();
            if (config != null) showList(config, -1);
        } else if (getParent() instanceof ViewGroup parent) {
            closing = true;
            if (transition != null) transition.cancel();
            if (!ValueAnimator.areAnimatorsEnabled()) {
                parent.removeView(this);
                return;
            }
            visiblePage.animate().translationX(pageOffset()).setDuration(200).setInterpolator(EASE).start();
            animate().alpha(0f).setDuration(200).setInterpolator(EASE)
                    .withEndAction(() -> parent.removeView(this)).start();
        }
    }

    @Override protected void onDetachedFromWindow() {
        closing = true;
        if (transition != null) transition.cancel();
        animate().cancel();
        closePicker();
        hostContent.setImportantForAccessibility(hostAccessibility);
        super.onDetachedFromWindow();
    }

    private void navigate(LinearLayout page, int direction) {
        if (transition != null) transition.cancel();
        LinearLayout previous = visiblePage;
        visiblePage = page;
        page.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        addView(page, new FrameLayout.LayoutParams(-1, -1));
        page.requestFocus();
        if (previous != null && direction != 0 && ValueAnimator.areAnimatorsEnabled()) {
            slide(previous, page, direction);
        } else if (previous != null) {
            removeView(previous);
        }
    }

    private void slide(View previous, View current, int direction) {
        float offset = pageOffset() * direction;
        current.setTranslationX(offset);
        current.setAlpha(0.72f);
        var moveIn = ObjectAnimator.ofFloat(current, View.TRANSLATION_X, 0f);
        var fadeIn = ObjectAnimator.ofFloat(current, View.ALPHA, 1f);
        var animation = new AnimatorSet();
        if (previous != null) {
            previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            animation.playTogether(moveIn, fadeIn, ObjectAnimator.ofFloat(previous, View.TRANSLATION_X, -offset * 0.38f));
        } else {
            animation.playTogether(moveIn, fadeIn);
        }
        animation.setDuration(240);
        animation.setInterpolator(EASE);
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                if (previous != null) {
                    removeView(previous);
                    previous.setTranslationX(0f);
                }
                current.setTranslationX(0f);
                current.setAlpha(1f);
                transition = null;
            }
        });
        transition = animation;
        animation.start();
    }

    private float pageOffset() {
        return dp(getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -38 : 38);
    }

    private void showList(XiaoAiConfig config, int direction) {
        if (editorPage != null) hideKeyboard();
        closePicker();
        editorPage = null;
        cropPage = null;
        boolean highlight = visiblePage != null && direction == 0;
        Button create = action("新建", accent, accentSurface);
        click(create, () -> showEditor(null));
        LinearLayout page = screen("人格", create);
        LinearLayout body = scrollBody(page);
        LinearLayout defaults = group(body);
        addProfile(defaults, "小爱默认人格", "", config.activePersonaId().isEmpty(), highlight,
                () -> select(XiaoAiConfig.DEFAULT), null);

        section(body, "我的人格");
        LinearLayout profiles = group(body);
        if (config.personas().isEmpty()) {
            TextView empty = text("还没有自定义人格", 15, secondary);
            empty.setPaddingRelative(dp(16), dp(18), dp(16), dp(18));
            profiles.addView(empty);
        }
        for (PersonaProfile profile : config.personas().values()) {
            if (profiles.getChildCount() != 0) addDivider(profiles);
            Runnable edit = () -> showEditor(profile);
            Runnable use = profile.persona().enabled() ? () -> select(profile.id()) : edit;
            addProfile(profiles, profile.name(), profile.persona().avatar(), profile.id().equals(config.activePersonaId()),
                    highlight, use, edit);
        }
        navigate(page, direction);
    }

    private void addProfile(LinearLayout group, String name, String image, boolean active, boolean highlight,
                            Runnable use, Runnable edit) {
        LinearLayout row = row(70);
        if (edit != null || !active) {
            row.setBackground(ripple(0, 14));
            click(row, edit != null ? edit : use);
        }
        ImageView avatar = XiaoAiAvatar.view(activity, image);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView label = text(name, 17, primary);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(12));
        labelParams.setMarginEnd(dp(8));
        row.addView(label, labelParams);
        if (active) {
            TextView selected = text("使用中", 13, accent);
            selected.setPaddingRelative(dp(10), dp(4), dp(10), dp(4));
            selected.setBackground(shape(accentSurface, 18));
            row.addView(selected, new LinearLayout.LayoutParams(-2, -2));
            if (highlight && ValueAnimator.areAnimatorsEnabled()) {
                selected.setAlpha(0.35f);
                selected.setScaleX(0.82f);
                selected.setScaleY(0.82f);
                selected.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(EASE).start();
            }
        } else {
            Button choose = action("使用", accent, accentSurface);
            choose.setContentDescription("使用" + name);
            click(choose, use);
            row.addView(choose, new LinearLayout.LayoutParams(-2, -2));
        }
        if (edit != null) addArrow(row);
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void select(String id) {
        XiaoAiConfig config = read();
        if (config == null) return;
        var next = config.selectPersona(id);
        if (save(next)) showList(next, 0);
    }

    private void showEditor(PersonaProfile profile) {
        XiaoAiConfig config = read();
        if (config == null) return;
        closePicker();
        XiaoAiPersona saved = profile == null ? XiaoAiPersona.EMPTY : profile.persona();
        boolean customModel = config.selected() != null;
        Button saveButton = action("保存", accent, accentSurface);
        LinearLayout page = screen(profile == null ? "新建人格" : "编辑人格", saveButton);
        editorPage = page;
        LinearLayout body = scrollBody(page);

        LinearLayout information = group(body);
        LinearLayout photo = row(76);
        photo.setBackground(ripple(0, 14));
        String[] image = { saved.avatar() };
        ImageView preview = XiaoAiAvatar.view(activity, image[0]);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        photo.addView(preview, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView photoLabel = text("头像", 17, primary);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(12));
        photo.addView(photoLabel, labelParams);
        Button remove = action("移除", secondary, surface);
        remove.setVisibility(image[0].isEmpty() ? View.GONE : View.VISIBLE);
        photo.addView(remove);
        addArrow(photo);
        information.addView(photo);
        var avatarPicker = new XiaoAiAvatar(activity, photoBitmap -> showCrop(photoBitmap, cropped -> {
            image[0] = cropped;
            XiaoAiAvatar.display(preview, cropped);
            remove.setVisibility(View.VISIBLE);
        }));
        picker = avatarPicker;
        click(photo, () -> {
            hideKeyboard();
            avatarPicker.choose();
        });
        click(remove, () -> {
            if (avatarPicker.pending()) return;
            image[0] = "";
            XiaoAiAvatar.display(preview, "");
            remove.setVisibility(View.GONE);
        });
        addDivider(information);
        EditText name = field(information, "名称", profile == null ? "" : profile.name(),
                "例如，学习搭档", PersonaProfile.NAME_LIMIT, false);

        section(body, "人格设定");
        LinearLayout fields = group(body);
        EditText identity = field(fields, "身份", saved.identity(), "例如，我的学习搭档", XiaoAiPersona.TEXT_LIMIT, true);
        addDivider(fields);
        EditText character = field(fields, "性格", saved.character(), "例如，耐心、好奇", XiaoAiPersona.TEXT_LIMIT, true);
        addDivider(fields);
        EditText style = field(fields, "表达风格", saved.style(), "例如，先说结论，再举例", XiaoAiPersona.TEXT_LIMIT, true);

        LinearLayout referenceCard = group(body);
        LinearLayout referenceRow = row(62);
        LinearLayout captions = column();
        captions.addView(text("参考默认人格", 16, primary));
        if (!customModel) captions.addView(text("使用自定义模型时可关闭", 13, secondary));
        referenceRow.addView(captions, new LinearLayout.LayoutParams(0, -2, 1));
        CompoundButton reference = ui.toggle(activity);
        reference.setContentDescription("参考默认人格");
        reference.setChecked(saved.referencesDefault(customModel));
        reference.setEnabled(customModel);
        if (customModel) {
            referenceRow.setBackground(ripple(0, 14));
            click(referenceRow, () -> reference.setChecked(!reference.isChecked()));
        }
        referenceRow.addView(reference, new LinearLayout.LayoutParams(-2, -2));
        referenceCard.addView(referenceRow);

        click(saveButton, () -> {
            if (avatarPicker.pending()) {
                Toast.makeText(activity, "正在读取头像", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                var persona = new XiaoAiPersona(value(identity), value(character), value(style), image[0], true,
                        customModel ? reference.isChecked() : saved.referenceDefault());
                String id = profile == null ? UUID.randomUUID().toString() : profile.id();
                var updated = new PersonaProfile(id, value(name), persona);
                XiaoAiConfig current = read();
                if (current == null) return;
                var next = current.withPersona(updated);
                if (save(next)) showList(next, -1);
            } catch (IllegalArgumentException invalid) {
                Toast.makeText(activity, invalid.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        if (profile != null) {
            Button delete = action("删除人格", danger, surface);
            click(delete, () -> ui.dialog(activity, "删除「" + profile.name() + "」")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> {
                        XiaoAiConfig current = read();
                        if (current == null) return;
                        var next = current.withoutPersona(profile.id());
                        if (save(next)) showList(next, -1);
                    }).show(false, null));
            group(body).addView(delete, new LinearLayout.LayoutParams(-1, -2));
        }
        navigate(page, 1);
    }

    private void showCrop(Bitmap photo, Consumer<String> apply) {
        if (closing || !isAttachedToWindow() || visiblePage != editorPage) return;
        hideKeyboard();
        Button confirm = action("完成", accent, accentSurface);
        LinearLayout page = screen("裁剪头像", confirm);
        var preview = new XiaoAiAvatarCropView(activity, photo);
        LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(-1, 0, 1);
        area.setMargins(dp(16), dp(6), dp(16), dp(10));
        preview.setBackground(shape(0xff141414, 20));
        preview.setClipToOutline(true);
        page.addView(preview, area);

        TextView hint = text("拖动照片调整位置，双指缩放", 14, secondary);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout zoom = new LinearLayout(activity);
        zoom.setGravity(Gravity.CENTER);
        zoom.setPadding(0, dp(8), 0, dp(10));
        Button minus = action("−", primary, surface);
        minus.setContentDescription("缩小头像");
        click(minus, () -> preview.zoomBy(1f / 1.25f));
        Button plus = action("+", primary, surface);
        plus.setContentDescription("放大头像");
        click(plus, () -> preview.zoomBy(1.25f));
        zoom.addView(minus, new LinearLayout.LayoutParams(dp(56), dp(48)));
        LinearLayout.LayoutParams plusParams = new LinearLayout.LayoutParams(dp(56), dp(48));
        plusParams.setMarginStart(dp(12));
        zoom.addView(plus, plusParams);
        page.addView(zoom);

        click(confirm, () -> {
            confirm.setEnabled(false);
            preview.setEnabled(false);
            minus.setEnabled(false);
            plus.setEnabled(false);
            Bitmap square = preview.croppedAvatar();
            new Thread(() -> {
                try {
                    String image = XiaoAiAvatar.encode(square);
                    activity.runOnUiThread(() -> {
                        if (closing || cropPage != page) return;
                        apply.accept(image);
                        finishCrop();
                    });
                } catch (IOException error) {
                    activity.runOnUiThread(() -> {
                        if (closing || cropPage != page) return;
                        confirm.setEnabled(true);
                        preview.setEnabled(true);
                        minus.setEnabled(true);
                        plus.setEnabled(true);
                        HookLog.error("XiaoAi avatar crop", error);
                        Toast.makeText(activity, "无法保存裁剪后的头像", Toast.LENGTH_SHORT).show();
                    });
                } finally {
                    square.recycle();
                }
            }, "HYPERNOVA-XiaoAi-crop").start();
        });
        cropPage = page;
        navigate(page, 1);
    }

    private void finishCrop() {
        cropPage = null;
        navigate(editorPage, -1);
    }

    private LinearLayout screen(String title, Button trailing) {
        LinearLayout page = column();
        page.setBackgroundColor(background);
        page.setFocusableInTouchMode(true);
        page.setAccessibilityPaneTitle(title);

        FrameLayout toolbar = new FrameLayout(activity);
        TextView heading = text(title, 20, primary);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        heading.setGravity(Gravity.CENTER);
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams header = new FrameLayout.LayoutParams(-1, -1);
        header.setMarginStart(dp(80));
        header.setMarginEnd(dp(80));
        toolbar.addView(heading, header);

        ImageButton back = new ImageButton(activity);
        back.setImageResource(night ? 0x7f08040b : 0x7f08040c); // Host back icon
        back.setImageTintList(ColorStateList.valueOf(primary));
        back.setBackground(ripple(0, 24));
        back.setContentDescription("返回");
        click(back, this::back);
        FrameLayout.LayoutParams start = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL);
        start.setMarginStart(dp(8));
        toolbar.addView(back, start);
        FrameLayout.LayoutParams end = new FrameLayout.LayoutParams(-2, dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        end.setMarginEnd(dp(12));
        toolbar.addView(trailing, end);
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        return page;
    }

    private LinearLayout scrollBody(LinearLayout page) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = column();
        body.setPaddingRelative(dp(16), dp(8), dp(16), dp(18));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return body;
    }

    private LinearLayout group(LinearLayout body) {
        LinearLayout group = column();
        group.setBackground(shape(surface, 20));
        group.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        body.addView(group, params);
        return group;
    }

    private void section(LinearLayout body, String title) {
        TextView label = text(title, 14, secondary);
        label.setAccessibilityHeading(true);
        label.setPaddingRelative(dp(14), dp(4), dp(14), dp(8));
        body.addView(label);
    }

    private void addDivider(LinearLayout parent) {
        View line = new View(activity);
        line.setBackgroundColor(divider);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 1);
        params.setMarginStart(dp(16));
        params.setMarginEnd(dp(16));
        parent.addView(line, params);
    }

    @SuppressLint("ResourceType") // The preference arrow is loaded from XiaoAi resources.
    private void addArrow(LinearLayout row) {
        ImageView arrow = new ImageView(activity);
        var drawable = activity.getDrawable(0x7f080ac7).mutate(); // pref_right_arrow
        drawable.setAutoMirrored(true);
        arrow.setImageDrawable(drawable);
        arrow.setImageTintList(ColorStateList.valueOf(secondary));
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(18), dp(22));
        params.setMarginStart(dp(6));
        row.addView(arrow, params);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout row(int height) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(height));
        row.setPaddingRelative(dp(16), dp(8), dp(12), dp(8));
        return row;
    }

    private EditText field(LinearLayout parent, String label, String current, String hint, int limit, boolean multiline) {
        LinearLayout labels = column();
        labels.setPaddingRelative(dp(16), dp(10), dp(16), dp(10));
        EditText field = ui.edit(activity);
        field.setId(View.generateViewId());
        field.setSingleLine(!multiline);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        field.setMaxLines(multiline ? 4 : 1);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        field.setText(current);
        field.setHint(hint);
        TextView caption = text(label, 15, primary);
        caption.setLabelFor(field.getId());
        labels.addView(caption);
        labels.addView(field, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(labels);
        return field;
    }

    private TextView text(String value, int size, int color) {
        TextView text = ui.text(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private Button action(String label, int color, int fill) {
        Button button = ui.button(activity);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(color);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setBackground(ripple(fill, 20));
        button.setPaddingRelative(dp(12), 0, dp(12), 0);
        return button;
    }

    private void click(View view, Runnable action) {
        view.setOnClickListener(clicked -> {
            if (!closing && transition == null && clicked.isAttachedToWindow()) action.run();
        });
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        var states = new StateListAnimator();
        states.addState(new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed}, scale(view, 0.97f, 85));
        states.addState(new int[]{}, scale(view, 1f, 170));
        view.setStateListAnimator(states);
    }

    private static AnimatorSet scale(View view, float factor, long duration) {
        var animator = new AnimatorSet();
        animator.playTogether(ObjectAnimator.ofFloat(view, View.SCALE_X, factor), ObjectAnimator.ofFloat(view, View.SCALE_Y, factor));
        animator.setDuration(duration);
        animator.setInterpolator(EASE);
        return animator;
    }

    private GradientDrawable shape(int color, int radius) {
        var drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private RippleDrawable ripple(int fill, int radius) {
        int color = night ? 0x25ffffff : 0x19000000;
        return new RippleDrawable(ColorStateList.valueOf(color), shape(fill, radius), shape(0xffffffff, radius));
    }

    private void hideKeyboard() {
        if (getWindowInsetsController() != null) getWindowInsetsController().hide(WindowInsets.Type.ime());
    }

    private void closePicker() {
        if (picker != null) {
            picker.close();
            picker = null;
        }
    }

    private XiaoAiConfig read() {
        try { return store.read(); }
        catch (Exception error) {
            HookLog.error("XiaoAi persona page read", error);
            Toast.makeText(activity, "无法读取人格", Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private boolean save(XiaoAiConfig config) {
        try { store.save(config); return true; }
        catch (Exception error) {
            HookLog.error("XiaoAi persona page save", error);
            Toast.makeText(activity, "无法保存人格", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String value(EditText field) { return field.getText().toString().strip(); }
}
