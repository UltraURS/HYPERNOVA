// SPDX-License-Identifier: GPL-3.0-only
// Shared-configuration approach adapted from Connect Colours (UltraURS and contributors).
package io.github.hypernova.ui;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import io.github.hypernova.ModuleStatus;
import io.github.hypernova.R;
import io.github.hypernova.compat.DeviceProfile;
import io.github.hypernova.config.Feature;
import io.github.hypernova.feature.sidebar.SidebarGesture;
import io.github.hypernova.feature.update.OtaClientStatus;
import java.util.Locale;

/** Native views with Miuix-style surfaces and package-based preference pages. */
public final class MainActivity extends Activity {
    private static final String PACKAGE_PAGE = "package_page";
    private static final String[][] PACKAGES = {
        {"设置", "com.android.settings"}, {"系统界面", "com.android.systemui"},
        {"系统更新", "com.android.updater"}, {"系统桌面", "com.miui.home"},
        {"手机管家", "com.miui.securitycenter"}, {"日历", "com.android.calendar"},
        {"天气", "com.miui.weather2"}, {"超级小爱", "com.miui.voiceassist"}, {"截屏", "com.miui.screenshot"}
    };
    private SharedPreferences preferences;
    private LinearLayout body, navigation;
    private FrameLayout pageHost;
    private AnimatorSet pageAnimation;
    private String page = "home";
    private int ink, muted, accent, surface;
    private boolean saving, detail;
    private TextView notice, otaStatus;

    @android.annotation.SuppressLint("WorldReadableFiles")
    @Override @SuppressWarnings("deprecation") protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            preferences = getSharedPreferences(Feature.PREFS, Context.MODE_WORLD_READABLE);
            SharedPreferences.Editor editor = preferences.edit().putInt(Feature.SCHEMA, 1);
            for (Feature feature : Feature.values()) if (!preferences.contains(feature.key)) editor.putBoolean(feature.key, false);
            editor.putInt(SidebarGesture.HOLD_KEY, SidebarGesture.clampHoldMillis(
                preferences.getInt(SidebarGesture.HOLD_KEY, SidebarGesture.DEFAULT_HOLD_MILLIS)));
            if (!editor.commit()) preferences = null;
        } catch (SecurityException error) { preferences = null; }
        if (state != null) page = state.getString("page", "home");
        String requestedPackage = getIntent().getStringExtra(PACKAGE_PAGE);
        detail = category(requestedPackage) != null;
        if (detail) page = requestedPackage;
        else if (category(page) != null) page = "settings";
        ink = getColor(R.color.text_primary); muted = getColor(R.color.text_secondary);
        accent = getColor(R.color.accent); surface = getColor(R.color.card_background);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);
        LinearLayout root = column(); root.setBackgroundColor(getColor(R.color.page_background));
        pageHost = new FrameLayout(this) {
            @Override public boolean onInterceptTouchEvent(MotionEvent event) {
                return pageAnimation != null || super.onInterceptTouchEvent(event);
            }
        };
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new LinearLayout(this); navigation.setBackgroundColor(surface); navigation.setBaselineAligned(false);
        if (detail) navigation.setVisibility(View.GONE);
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, detail ? bars.bottom : 0);
            navigation.setPadding(dp(16), dp(8), dp(16), dp(8) + bars.bottom);
            return insets;
        });
        setContentView(root);
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(getResources().getBoolean(R.bool.light_system_bars) ? light : 0, light);
        root.requestApplyInsets(); showPage(page);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page); super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume();
        if (otaStatus != null) otaStatus.setText(OtaClientStatus.describe(this));
    }
    @Override protected void onDestroy() {
        if (pageAnimation != null) pageAnimation.cancel();
        super.onDestroy();
    }
    private boolean editable() {
        return preferences != null && ModuleStatus.frameworkApi() > 0 && DeviceProfile.matchesBuild();
    }
    private String[] category(String key) {
        for (String[] item : PACKAGES) if (item[1].equals(key)) return item;
        return null;
    }
    private void showPage(String selected) {
        String previousPage = page;
        if (pageAnimation != null) pageAnimation.cancel();
        LinearLayout previousBody = body;
        String[] target = category(selected);
        page = target != null || "settings".equals(selected) || "about".equals(selected) ? selected : "home";
        notice = otaStatus = null; pageHost.removeAllViews(); navigation.removeAllViews();
        body = column(); pageHost.addView(body, new FrameLayout.LayoutParams(-1, -1));
        if (!"home".equals(page)) toolbar(target != null ? target[0] : "settings".equals(page) ? "设置" : "关于", target != null);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content = column(); content.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(content); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (target != null) packagePage(content, target);
        else if ("settings".equals(page)) settings(content);
        else if ("about".equals(page)) about(content);
        else home(content);
        nav("home", "首页", R.drawable.ic_home, "home".equals(page));
        nav("settings", "设置", R.drawable.ic_settings, target != null || "settings".equals(page));
        nav("about", "关于", R.drawable.ic_info, "about".equals(page));
        if (!detail && previousBody != null && !previousPage.equals(page)) pushPage(previousBody, previousPage);
    }

    private void pushPage(View previous, String previousPage) {
        if (!ValueAnimator.areAnimatorsEnabled() || pageHost.getWidth() == 0) return;
        int oldIndex = "home".equals(previousPage) ? 0 : "settings".equals(previousPage) ? 1 : 2;
        int newIndex = "home".equals(page) ? 0 : "settings".equals(page) ? 1 : 2;
        int direction = newIndex > oldIndex ? 1 : -1;
        if (pageHost.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) direction = -direction;
        float distance = pageHost.getWidth() * direction;
        previous.setTranslationX(0); previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        pageHost.addView(previous, 0, new FrameLayout.LayoutParams(-1, -1));
        AnimatorSet animation = new AnimatorSet();
        animation.playTogether(ObjectAnimator.ofFloat(previous, View.TRANSLATION_X, 0, -distance),
            ObjectAnimator.ofFloat(body, View.TRANSLATION_X, distance, 0));
        animation.setDuration(250); animation.setInterpolator(new DecelerateInterpolator());
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                pageHost.removeView(previous);
                if (pageAnimation == animation) pageAnimation = null;
            }
        });
        pageAnimation = animation; animation.start();
    }
    private void toolbar(String title, boolean showBack) {
        FrameLayout bar = new FrameLayout(this);
        TextView heading = text(title, 21, ink, true); heading.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams headingParams = new FrameLayout.LayoutParams(-1, -1);
        headingParams.leftMargin = headingParams.rightMargin = dp(56); bar.addView(heading, headingParams);
        if (showBack) {
            ImageView button = icon(R.drawable.ic_back, ink); button.setPadding(dp(12), dp(12), dp(12), dp(12));
            button.setBackground(ripple(0)); button.setContentDescription("返回设置列表");
            button.setOnClickListener(view -> finish());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL);
            params.setMarginStart(dp(8)); bar.addView(button, params);
        }
        body.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));
    }
    private void home(LinearLayout content) {
        int api = ModuleStatus.frameworkApi();
        boolean active = api > 0, supported = DeviceProfile.matchesBuild();
        boolean ready = active && preferences != null && supported;
        TextView heading = text("HYPERNOVA", 34, ink, false);
        heading.setPadding(dp(12), dp(16), dp(12), dp(20)); content.addView(heading);
        int background = getColor(ready ? R.color.status_active_background
            : active ? R.color.status_warning_background : R.color.status_inactive_background);
        int symbol = getColor(ready ? R.color.status_active_foreground
            : active ? R.color.status_warning_foreground : R.color.status_inactive_foreground);
        FrameLayout status = new FrameLayout(this);
        status.setBackground(round(background, 24)); status.setClipToOutline(true); status.setMinimumHeight(dp(190));
        ImageView mark = icon(ready ? R.drawable.ic_status_check : R.drawable.ic_info, symbol);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(dp(142), dp(142), Gravity.END | Gravity.BOTTOM);
        markParams.setMarginEnd(-dp(16)); markParams.bottomMargin = -dp(18); status.addView(mark, markParams);
        LinearLayout summary = column(); summary.setPadding(dp(22), dp(20), dp(128), dp(22));
        TextView badge = text("LSPosed", 11, symbol, true);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3)); badge.setBackground(round(surface, 6));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2); badgeParams.bottomMargin = dp(40);
        summary.addView(badge, badgeParams);
        String state = !active ? "未激活" : preferences == null ? "配置不可用" : !supported ? "待适配" : "已激活";
        summary.addView(text(state, 34, ink, true));
        TextView version = text("版本：" + getString(R.string.module_version), 14, ink, false);
        version.setPadding(0, dp(7), 0, 0); summary.addView(version);
        status.addView(summary, new FrameLayout.LayoutParams(-1, -2)); content.addView(status);

        int enabled = 0;
        if (preferences != null) for (Feature feature : Feature.values()) if (preferences.getBoolean(feature.key, false)) enabled++;
        LinearLayout metrics = new LinearLayout(this);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1); first.setMarginEnd(dp(6));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1); second.setMarginStart(dp(6));
        metrics.addView(metric(preferences != null ? Integer.toString(enabled) : "—", "已开启功能 / " + Feature.values().length), first);
        metrics.addView(metric(active ? Integer.toString(api) : "—", "框架 API"), second);
        LinearLayout.LayoutParams metricParams = new LinearLayout.LayoutParams(-1, -2); metricParams.topMargin = dp(12); metricParams.bottomMargin = dp(12);
        content.addView(metrics, metricParams);

        LinearLayout info = card();
        deviceRow(info, "模块版本", getString(R.string.module_version));
        deviceRow(info, "配置状态", preferences != null ? "可写入" : "不可用");
        deviceRow(info, "设备型号", Build.MODEL);
        deviceRow(info, "Android 版本", Build.VERSION.RELEASE + " · API " + Build.VERSION.SDK_INT);
        deviceRow(info, "适配目标", "Redmi Note 12T Pro\nHyperOS 3.0.3.0.VLHCNXM");
        deviceRow(info, "构建匹配", supported ? "与目标一致" : "与目标不同，功能不会加载");
        content.addView(info);
    }
    private LinearLayout metric(String value, String label) {
        LinearLayout card = paddedCard();
        card.addView(text(value, 28, ink, true));
        TextView caption = text(label, 13, muted, false); caption.setPadding(0, dp(8), 0, dp(2)); card.addView(caption);
        return card;
    }
    private void deviceRow(LinearLayout parent, String label, String value) {
        if (parent.getChildCount() > 0) divider(parent);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(18), dp(15), dp(18), dp(15));
        row.addView(text(label, 14, ink, false), new LinearLayout.LayoutParams(0, -2, 0.35f));
        TextView detail = text(value, 14, muted, false); detail.setGravity(Gravity.END); detail.setPadding(dp(10), 0, 0, 0);
        row.addView(detail, new LinearLayout.LayoutParams(0, -2, 0.65f)); parent.addView(row);
    }
    private void settings(LinearLayout content) {
        TextView status = text(ModuleStatus.frameworkApi() > 0
            ? "模块自身已激活 · 更改后重启生效" : "模块尚未激活 · 请在 LSPosed 中启用", 13, accent, false);
        status.setPadding(dp(16), dp(13), dp(16), dp(13)); status.setBackground(round(getColor(R.color.accent_background), 18));
        content.addView(status);
        section(content, "应用");
        LinearLayout apps = card();
        for (int i = 0; i < PACKAGES.length; i++) {
            if (i > 0) divider(apps);
            String[] target = PACKAGES[i];
            LinearLayout row = packageIdentity(target, true);
            row.setBackground(ripple(surface));
            row.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class).putExtra(PACKAGE_PAGE, target[1])));
            apps.addView(row);
        }
        content.addView(apps);
        TextView tip = text("选择应用管理功能。涉及多个应用的选项会在各分类中同步显示。", 13, muted, false);
        tip.setPadding(dp(12), dp(16), dp(12), 0); content.addView(tip);
    }
    private void packagePage(LinearLayout content, String[] target) {
        LinearLayout identity = card(); identity.addView(packageIdentity(target, false)); content.addView(identity);
        notice = text(editable() ? "更改后重启手机生效。" : "请确认 LSPosed 已激活模块、共享配置可用且设备构建匹配。", 13, muted, false);
        notice.setPadding(dp(12), dp(16), dp(12), dp(12)); content.addView(notice);
        LinearLayout options = card(); boolean added = false;
        for (Feature feature : Feature.values()) if (feature.packages.contains(target[1])) {
            if (added) divider(options);
            featureRow(options, feature); added = true;
            if (feature == Feature.SIDEBAR_GESTURE) { divider(options); sidebarTimingRow(options); }
        }
        if (added) content.addView(options);
        if ("com.android.updater".equals(target[1])) {
            LinearLayout ota = paddedCard();
            ota.addView(text("OTA 客户端服务器阻断", 17, ink, true));
            otaStatus = text(OtaClientStatus.describe(this), 14, muted, false);
            otaStatus.setPadding(0, dp(8), 0, 0); ota.addView(otaStatus); content.addView(ota);
        }
        if (Feature.SIDEBAR_GESTURE.packages.contains(target[1])) {
            TextView scope = text("本功能同时需要系统桌面和手机管家作用域。请先在系统设置开启侧边栏，并允许当前应用显示；两处的开关与阈值共用同一份配置。", 13, muted, false);
            scope.setPadding(dp(12), dp(16), dp(12), 0); content.addView(scope);
        }
    }
    private void about(LinearLayout content) {
        section(content, "使用方式");
        LinearLayout usage = paddedCard();
        infoRow(usage, "管理功能", "在设置页选择对应应用；跨应用功能在相关包名下共用配置。");
        infoRow(usage, "生效方式", "开关和阈值保存后需重启手机。");
        infoRow(usage, "基础作用域", "系统界面 · com.android.systemui\n设置 · com.android.settings");
        infoRow(usage, "蓝牙自动重连", "同样需要设置和系统界面。全局开关重启生效；每台设备的开关与次数在系统蓝牙详情页保存后立即生效。");
        infoRow(usage, "自动省电", "勾选手机管家作用域，在手机管家分类开启自动省电设置并重启；随后进入系统省电模式页面底部的 HYPERNOVA 分类，开启自动打开并调整阈值。");
        infoRow(usage, "侧边栏作用域", "系统桌面 · com.miui.home\n手机管家 · com.miui.securitycenter");
        infoRow(usage, "广告屏蔽作用域", "日历 · com.android.calendar\n天气 · com.miui.weather2");
        content.addView(usage);
        section(content, "功能说明");
        LinearLayout scope = paddedCard();
        infoRow(scope, "返回手势呼出侧边栏", "从系统返回手势的就绪震动开始计时，默认 0.30 秒后再次震动并切换图标；松手打开，回划取消。无需拉到动画最大位置。");
        infoRow(scope, "隐藏系统侧边栏触发条", "在手机管家分类开启。仅隐藏并禁用收起状态的原生触发区域，保留侧边栏；不接管普通返回手势。");
        infoRow(scope, "侧边栏向外滑动收回", "在手机管家分类开启。在应用列表上左侧向左、右侧向右滑动后松手，使用原生收回动画。");
        infoRow(scope, "小窗右上角关闭按钮", "在系统界面分类开启。点击 × 沿用系统原生小窗关闭，任务转到后台。");
        infoRow(scope, "蓝牙自动重连", "在设置或系统界面分类开启，重启后到系统蓝牙的已配对设备详情底部设置 HYPERNOVA 选项。每台默认关闭，1～5 次含首次；请求后等待连接 15 秒，连接出现后再稳定确认 5 秒才提示成功。短暂连接不重置次数，失败或稳定连接后断开均等待 10 秒；系统正在连接时延后尝试。手动断开后暂停，重新开蓝牙或重启恢复。蓝牙重连提示可在系统界面分类单独开启。");
        infoRow(scope, "自动省电", "系统省电模式页可设置未接电且电量严格小于阈值时自动开启普通省电模式。默认关闭，阈值 20%～80%、默认 20%；开关和阈值保存后立即生效。本轮手动关闭后保持关闭，重新插拔电源或电量恢复到阈值再下降时重新触发。充电或关闭自动选项不会替你关闭已开启的省电模式。");
        infoRow(scope, "通知中心一键清理", "开启后在通知中心底部显示系统清理按钮，保留锁定和系统不可清除的通知；仅置顶的通知仍可清理。");
        infoRow(scope, "日历与天气广告", "在各自分类分别开启。阻断专用广告加载，屏蔽缓存广告；共用内容接口保留正常内容并过滤广告条目。");
        infoRow(scope, "恢复出厂设置", "隐藏普通条目、底部推荐及设置搜索结果，不拦截实际重置。");
        infoRow(scope, "OTA 服务器阻断", "仅限已适配的 OTA 客户端。当前 ROM 没有更新器时，在系统更新分类显示不适用。");
        content.addView(scope);
        section(content, "来源");
        LinearLayout source = paddedCard();
        infoRow(source, "许可证", "GPL-3.0-only");
        content.addView(source);
    }
    private LinearLayout packageIdentity(String[] target, boolean arrow) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(18), dp(18), dp(18)); row.setMinimumHeight(dp(84));
        ImageView image = new ImageView(this);
        try { image.setImageDrawable(getPackageManager().getApplicationIcon(target[1])); }
        catch (PackageManager.NameNotFoundException missing) { image.setImageDrawable(getPackageManager().getDefaultActivityIcon()); }
        row.addView(image, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout labels = column(); TextView title = text(target[0], 18, ink, true);
        labels.addView(title); TextView name = text(target[1], 13, muted, false);
        name.setPadding(0, dp(3), 0, 0); labels.addView(name);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMarginStart(dp(16)); params.setMarginEnd(dp(10)); row.addView(labels, params);
        if (arrow) row.addView(icon(R.drawable.ic_chevron, muted), new LinearLayout.LayoutParams(dp(16), dp(24)));
        return row;
    }
    @SuppressWarnings("UseSwitchCompatOrMaterialCode")
    private void featureRow(LinearLayout parent, Feature feature) {
        LinearLayout item = column(); item.setPadding(dp(20), dp(14), dp(20), dp(14)); item.setBackground(ripple(surface));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(feature.title, 17, ink, true); title.setPadding(0, 0, dp(16), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this); toggle.setContentDescription(feature.title); toggle.setShowText(false);
        toggle.setMinHeight(dp(48)); toggle.setSwitchMinWidth(dp(52));
        GradientDrawable thumb = round(Color.WHITE, 20); thumb.setSize(dp(24), dp(24));
        toggle.setThumbTintList(null); toggle.setTrackTintList(null); toggle.setThumbDrawable(new InsetDrawable(thumb, dp(3)));
        StateListDrawable track = new StateListDrawable();
        GradientDrawable checked = round(accent, 20); checked.setSize(dp(52), dp(30));
        GradientDrawable unchecked = round(getColor(R.color.switch_off), 20); unchecked.setSize(dp(52), dp(30));
        track.addState(new int[]{android.R.attr.state_checked}, checked); track.addState(new int[]{}, unchecked); toggle.setTrackDrawable(track);
        toggle.setChecked(preferences != null && preferences.getBoolean(feature.key, false)); toggle.setEnabled(editable());
        if (!toggle.isEnabled()) toggle.setAlpha(0.45f);
        row.addView(toggle); item.addView(row);
        item.addView(text(feature.description, 13, muted, false)); parent.addView(item);
        item.setOnClickListener(view -> { if (toggle.isEnabled()) toggle.toggle(); });
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (saving || !editable()) return;
            boolean previous = preferences.getBoolean(feature.key, false);
            if (preferences.edit().putBoolean(feature.key, enabled).commit()) saved("已保存，请重启手机生效。");
            else {
                saving = true; toggle.setChecked(previous); saving = false; saveFailed();
            }
        });
    }
    private void sidebarTimingRow(LinearLayout parent) {
        LinearLayout item = column(); item.setPadding(dp(20), dp(18), dp(20), dp(18)); parent.addView(item);
        int value = SidebarGesture.clampHoldMillis(preferences == null ? SidebarGesture.DEFAULT_HOLD_MILLIS
            : preferences.getInt(SidebarGesture.HOLD_KEY, SidebarGesture.DEFAULT_HOLD_MILLIS));
        TextView label = text(String.format(Locale.ROOT, "触发阈值 · %.2f 秒", value / 1000f), 17, ink, true); item.addView(label);
        SeekBar slider = new SeekBar(this); slider.setMax((SidebarGesture.MAX_HOLD_MILLIS - SidebarGesture.MIN_HOLD_MILLIS) / 10);
        slider.setProgress((value - SidebarGesture.MIN_HOLD_MILLIS) / 10); slider.setMinHeight(dp(48)); slider.setEnabled(editable());
        slider.setProgressTintList(ColorStateList.valueOf(accent)); slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setContentDescription("系统返回震动后的侧边栏触发阈值，0.10 至 0.50 秒"); item.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        item.addView(text("从系统返回就绪震动开始计时。0.10～0.50 秒，默认 0.30 秒；到期再次震动并切换图标，松手打开，回划取消。", 13, muted, false));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean tracking;
            @Override public void onStartTrackingTouch(SeekBar view) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar view) { tracking = false; save(); }
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) {
                label.setText(String.format(Locale.ROOT, "触发阈值 · %.2f 秒", (SidebarGesture.MIN_HOLD_MILLIS + progress * 10) / 1000f));
                if (fromUser && !tracking) save();
            }
            private void save() {
                if (!editable()) return;
                if (preferences.edit().putInt(SidebarGesture.HOLD_KEY, SidebarGesture.MIN_HOLD_MILLIS + slider.getProgress() * 10).commit()) saved("触发阈值已保存，请重启手机生效。");
                else saveFailed();
            }
        });
    }
    private void saved(String message) { if (notice != null) notice.setText(message); }
    private void saveFailed() {
        preferences = null; showPage(page); Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_LONG).show();
    }
    private void nav(String target, String label, int resource, boolean selected) {
        LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setMinimumHeight(dp(64));
        item.setPadding(dp(8), dp(7), dp(8), dp(6)); item.setBackground(ripple(surface));
        item.addView(icon(resource, selected ? ink : muted), new LinearLayout.LayoutParams(dp(25), dp(25)));
        TextView caption = text(label, 12, selected ? ink : muted, false); caption.setGravity(Gravity.CENTER); caption.setIncludeFontPadding(false);
        caption.setPadding(0, dp(4), 0, 0); item.addView(caption, new LinearLayout.LayoutParams(-2, -2));
        item.setSelected(selected); item.setContentDescription(label);
        item.setOnClickListener(view -> { if (!target.equals(page)) showPage(target); });
        navigation.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private ImageView icon(int resource, int color) {
        ImageView image = new ImageView(this); Drawable drawable = getDrawable(resource).mutate(); drawable.setTint(color); image.setImageDrawable(drawable); return image;
    }
    private void infoRow(LinearLayout parent, String label, String value) {
        TextView heading = text(label, 13, muted, false); heading.setPadding(0, dp(8), 0, dp(5)); parent.addView(heading);
        TextView detail = text(value, 15, ink, false); detail.setPadding(0, 0, 0, dp(12)); parent.addView(detail);
    }
    private void section(LinearLayout parent, String label) {
        TextView heading = text(label, 13, muted, false); heading.setPadding(dp(12), dp(22), 0, dp(10)); parent.addView(heading);
    }
    private void divider(LinearLayout parent) {
        View line = new View(this); line.setBackgroundColor(getColor(R.color.divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1)); params.setMarginStart(dp(20)); params.setMarginEnd(dp(20)); parent.addView(line, params);
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout card() { LinearLayout layout = column(); layout.setBackground(round(surface, 22)); layout.setClipToOutline(true); return layout; }
    private LinearLayout paddedCard() { LinearLayout layout = card(); layout.setPadding(dp(20), dp(14), dp(20), dp(14)); return layout; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(2), 1); if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return view;
    }
    private GradientDrawable round(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private RippleDrawable ripple(int color) { return new RippleDrawable(ColorStateList.valueOf(getColor(R.color.touch_feedback)), round(color, 0), round(Color.WHITE, 0)); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
