// SPDX-License-Identifier: GPL-3.0-only
// Shared-configuration approach adapted from Connect Colours (UltraURS and contributors).
package io.github.hypernova.ui;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import io.github.hypernova.BuildConfig;
import io.github.hypernova.HypernovaApp;
import io.github.hypernova.ModuleStatus;
import io.github.hypernova.R;
import io.github.hypernova.compat.DeviceProfile;
import io.github.hypernova.config.Feature;
import io.github.hypernova.config.LegacyConfigMigration;
import io.github.hypernova.config.ScopeTargets;
import io.github.hypernova.feature.sidebar.SidebarGesture;
import io.github.hypernova.feature.update.OtaClientStatus;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.function.Consumer;

/** Native views with Miuix-style surfaces and package-based preference pages. */
public final class MainActivity extends Activity {
    private static final String PACKAGE_PAGE = "package_page";
    private static final String FEATURE_ROW = "feature_row";
    private static final String REPOSITORY_URL = "https://github.com/UltraURS/HYPERNOVA";
    private static final List<String> NAV_PAGES = List.of("home", "settings", "about");
    private static final String[][] PACKAGES = {
        {"设置", "com.android.settings"}, {"系统界面", "com.android.systemui"},
        {"系统更新", "com.android.updater"}, {"系统桌面", "com.miui.home"},
        {"手机管家", "com.miui.securitycenter"}, {"日历", "com.android.calendar"},
        {"天气", "com.miui.weather2"}, {"超级小爱", "com.miui.voiceassist"}, {"截屏", "com.miui.screenshot"},
        {"小米运动健康", "com.mi.health"}, {"USIM 卡应用", "com.android.stk"}, {"SystemHelper", "com.mobiletools.systemhelper"},
        {"RegService", "com.miui.dmregservice"}, {"Analytics", "com.miui.analytics"}
    };
    private SharedPreferences preferences;
    private HypernovaApp app;
    private final Runnable connectionChanged = this::refreshConfiguration;
    private LinearLayout body, navigation;
    private PageHost pageHost;
    private View swipeControl;
    private ValueAnimator pageAnimation;
    private String page = "home";
    private String searchQuery = "";
    private FeatureSearchMode searchMode = FeatureSearchMode.NAME;
    private int ink, muted, accent, surface;
    private int topInset, renderedApi;
    private boolean saving, detail;
    private TextView notice, otaStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (HypernovaApp) getApplication();
        preferences = app.preferences();
        if (preferences == null && ModuleStatus.frameworkApi() > 0) preferences = openLegacyConfiguration();
        if (BuildConfig.LEGACY_CONFIG_MIGRATION) backUpLegacyConfiguration();
        if (state != null) {
            page = state.getString("page", "home");
            searchQuery = state.getString("search_query", "");
            searchMode = FeatureSearchMode.values()[state.getInt("search_mode", 0)];
        }
        String requestedPackage = getIntent().getStringExtra(PACKAGE_PAGE);
        detail = category(requestedPackage) != null;
        if (detail) page = requestedPackage;
        else if (category(page) != null) page = "settings";
        ink = getColor(R.color.text_primary); muted = getColor(R.color.text_secondary);
        accent = getColor(R.color.accent); surface = getColor(R.color.card_background);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = column(); root.setBackgroundColor(getColor(R.color.page_background));
        pageHost = new PageHost();
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        navigation = new LinearLayout(this); navigation.setBackgroundColor(surface); navigation.setBaselineAligned(false);
        if (detail) navigation.setVisibility(View.GONE);
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            view.setPadding(bars.left, 0, bars.right, Math.max(keyboard, detail ? bars.bottom : 0));
            topInset = bars.top;
            if (body != null) body.setPadding(0, topInset, 0, 0);
            if (pageHost.preview != null) pageHost.preview.view.setPadding(0, topInset, 0, 0);
            navigation.setVisibility(detail || insets.isVisible(WindowInsets.Type.ime()) ? View.GONE : View.VISIBLE);
            navigation.setPadding(dp(16), dp(8), dp(16), dp(8) + bars.bottom);
            return insets;
        });
        setContentView(root);
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        getWindow().getInsetsController().setSystemBarsAppearance(getResources().getBoolean(R.bool.light_system_bars) ? light : 0, light);
        root.requestApplyInsets(); showPage(page);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page);
        state.putString("search_query", searchQuery);
        state.putInt("search_mode", searchMode.ordinal());
        super.onSaveInstanceState(state);
    }
    @Override protected void onStart() {
        super.onStart();
        app.addConnectionListener(connectionChanged);
        refreshConfiguration();
    }
    @Override protected void onStop() {
        app.removeConnectionListener(connectionChanged);
        if (pageAnimation != null) pageAnimation.cancel();
        pageHost.resetDrag();
        super.onStop();
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
        return preferences != null && frameworkApi() > 0 && DeviceProfile.matchesBuild();
    }
    private int frameworkApi() {
        return app.frameworkApi() > 0 ? app.frameworkApi() : ModuleStatus.frameworkApi();
    }
    private void refreshConfiguration() {
        SharedPreferences remote = app.preferences();
        if (remote == null && ModuleStatus.frameworkApi() > 0) return;
        if (preferences != remote || renderedApi != frameworkApi()) {
            preferences = remote;
            showPage(page);
        }
    }

    @android.annotation.SuppressLint("WorldReadableFiles")
    @SuppressWarnings("deprecation")
    private SharedPreferences openLegacyConfiguration() {
        try {
            SharedPreferences legacy = getSharedPreferences(Feature.PREFS, Context.MODE_WORLD_READABLE);
            if (!BuildConfig.LEGACY_CONFIG_MIGRATION && legacy.getInt(Feature.SCHEMA, 0) != 1
                && !legacy.edit().putInt(Feature.SCHEMA, 1).commit()) return null;
            return legacy;
        } catch (SecurityException error) {
            return null;
        }
    }

    private void backUpLegacyConfiguration() {
        String result;
        if (preferences == null || preferences.getInt(Feature.SCHEMA, 0) != 1) {
            result = "读取旧配置失败。请确认 LSPosed 已激活 HYPERNOVA 后重新打开";
        } else {
            try {
                LegacyConfigMigration.save(this, preferences);
                result = "已备份原有开关和阈值，可以覆盖安装新版";
            } catch (java.io.IOException | org.json.JSONException error) {
                result = "备份旧配置失败";
            }
        }
        Toast.makeText(this, result, Toast.LENGTH_LONG).show();
    }
    private String[] category(String key) {
        for (String[] item : PACKAGES) if (item[1].equals(key)) return item;
        return null;
    }
    private void showPage(String selected) {
        if (pageAnimation != null) pageAnimation.cancel();
        pageHost.resetDrag();
        String previousPage = page;
        LinearLayout previousBody = body;
        String next = NAV_PAGES.contains(selected) || category(selected) != null ? selected : "home";
        if ("settings".equals(previousPage) && !previousPage.equals(next) && previousBody != null) dismissSearchKeyboard();
        notice = otaStatus = null;
        pageHost.removeAllViews();
        PageContent content = createPage(next);
        pageHost.addView(content.view, new FrameLayout.LayoutParams(-1, -1));
        selectPage(next, content);
        if (!detail && previousBody != null && !previousPage.equals(page)) pushPage(previousBody, previousPage);
    }

    private PageContent createPage(String name) {
        boolean themed = "home".equals(name) || "about".equals(name);
        LinearLayout view = themed ? new ThemePage(this) : column();
        if (!themed) view.setBackgroundColor(getColor(R.color.page_background));
        view.setPadding(0, topInset, 0, 0);
        String[] target = category(name);
        if (target != null || "settings".equals(name)) toolbar(view, target != null ? target[0] : "设置", target != null);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content = column(); content.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(content); view.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        View control = null;
        if (target != null) packagePage(content, scroll, target);
        else if ("settings".equals(name)) control = settings(content);
        else if ("about".equals(name)) about(content);
        else control = home(content);
        return new PageContent(view, control);
    }

    private void selectPage(String name, PageContent content) {
        renderedApi = frameworkApi();
        page = name;
        body = content.view;
        swipeControl = content.swipeControl;
        body.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if ("settings".equals(page)) {
            body.setFocusableInTouchMode(true);
            body.requestFocus();
        }
        navigation.removeAllViews();
        nav("home", "首页", R.drawable.ic_home, "home".equals(page));
        nav("settings", "设置", R.drawable.ic_settings, detail || "settings".equals(page));
        nav("about", "关于", R.drawable.ic_info, "about".equals(page));
    }

    private void pushPage(View previous, String previousPage) {
        if (!ValueAnimator.areAnimatorsEnabled() || pageHost.getWidth() == 0) return;
        int direction = Integer.signum(NAV_PAGES.indexOf(page) - NAV_PAGES.indexOf(previousPage));
        float gap = pageHost.getWidth() * (pageHost.isRtl() ? -direction : direction);
        previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        pageHost.addView(previous, 0, new FrameLayout.LayoutParams(-1, -1));
        positionPages(previous, body, 0f, gap);
        settlePages(previous, body, gap, -gap, previous);
    }

    private void settlePages(View first, View second, float gap, float end, View departing) {
        float start = first.getTranslationX();
        if (!ValueAnimator.areAnimatorsEnabled() || start == end) {
            positionPages(first, second, end, gap);
            pageHost.removeView(departing);
            return;
        }
        ValueAnimator animation = ValueAnimator.ofFloat(start, end);
        animation.addUpdateListener(frame -> positionPages(first, second, (float) frame.getAnimatedValue(), gap));
        animation.setDuration(Math.round(90 + 160 * Math.abs(end - start) / Math.abs(gap)));
        animation.setInterpolator(new DecelerateInterpolator(1.4f));
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                positionPages(first, second, end, gap);
                pageHost.removeView(departing);
                if (pageAnimation == animation) pageAnimation = null;
            }
        });
        pageAnimation = animation; animation.start();
    }

    private static void positionPages(View first, View second, float offset, float gap) {
        first.setTranslationX(offset);
        second.setTranslationX(offset + gap);
    }

    private record PageContent(LinearLayout view, View swipeControl) { }

    private void toolbar(LinearLayout parent, String title, boolean showBack) {
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
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(56)));
    }
    private View home(LinearLayout content) {
        int api = frameworkApi();
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
        deviceRow(info, "构建匹配", supported ? "与目标一致" : "与目标不同，功能不会加载");
        content.addView(info);

        LinearLayout scope = card();
        switchRow(scope, "勾选开关同步到作用域", null, ScopeTargets.SYNC_KEY,
            editable() && app.preferences() != null, enabledSync -> {
                if (enabledSync) app.requestScopes(ScopeTargets.enabledPackages(
                    feature -> preferences.getBoolean(feature.key, false)));
                else app.forgetScopeRequest();
            });
        LinearLayout.LayoutParams scopeParams = new LinearLayout.LayoutParams(-1, -2);
        scopeParams.topMargin = dp(12);
        content.addView(scope, scopeParams);
        return scope;
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
    private View settings(LinearLayout content) {
        LinearLayout categories = column();
        LinearLayout results = card();
        EditText query = settingsSearch(content, categories, results);
        TextView status = text(frameworkApi() > 0
            ? "模块自身已激活 · 更改后重启生效" : "模块尚未激活 · 请在 LSPosed 中启用", 13, accent, false);
        status.setPadding(dp(16), dp(13), dp(16), dp(13)); status.setBackground(round(getColor(R.color.accent_background), 18));
        categories.addView(status);
        section(categories, "应用");
        LinearLayout apps = card();
        for (int i = 0; i < PACKAGES.length; i++) {
            if (i > 0) divider(apps);
            String[] target = PACKAGES[i];
            LinearLayout row = packageIdentity(target, true);
            row.setBackground(ripple(surface));
            row.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class).putExtra(PACKAGE_PAGE, target[1])));
            apps.addView(row);
        }
        categories.addView(apps);
        TextView tip = text("选择应用管理功能。涉及多个应用的选项会在各分类中同步显示。", 13, muted, false);
        tip.setPadding(dp(12), dp(16), dp(12), 0); categories.addView(tip);
        content.addView(categories);
        content.addView(results);
        return query;
    }

    private EditText settingsSearch(LinearLayout content, LinearLayout categories, LinearLayout results) {
        LinearLayout searchCard = card();
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(12);
        content.addView(searchCard, cardParams);

        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPaddingRelative(dp(16), 0, dp(4), 0);
        ImageView magnifier = icon(R.drawable.ic_search, muted);
        magnifier.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        field.addView(magnifier, new LinearLayout.LayoutParams(dp(20), dp(20)));
        EditText query = new EditText(this);
        query.setHint("搜索开关");
        query.setTextColor(ink);
        query.setHintTextColor(muted);
        query.setTextSize(16);
        query.setSingleLine();
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setBackground(null);
        query.setMinHeight(dp(52));
        query.setPaddingRelative(dp(12), 0, dp(8), 0);
        query.setText(searchQuery);
        query.setSelection(query.length());
        field.addView(query, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView clear = icon(R.drawable.ic_search_clear, muted);
        clear.setPadding(dp(14), dp(14), dp(14), dp(14));
        clear.setContentDescription("清除搜索");
        clear.setBackground(ripple(surface));
        clear.setOnClickListener(view -> query.setText(""));
        field.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        searchCard.addView(field);

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        modes.setPaddingRelative(dp(12), 0, dp(12), dp(5));
        for (FeatureSearchMode mode : FeatureSearchMode.values()) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setTag(mode);
            option.setText(mode.label);
            option.setTextSize(13);
            option.setTextColor(ink);
            option.setMinHeight(dp(48));
            option.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{accent, muted}));
            modes.addView(option, new RadioGroup.LayoutParams(0, -2, 1));
            if (mode == searchMode) modes.check(option.getId());
        }
        searchCard.addView(modes);

        Runnable refresh = () -> {
            boolean searching = !searchQuery.isBlank();
            clear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
            categories.setVisibility(searching ? View.GONE : View.VISIBLE);
            results.setVisibility(searching ? View.VISIBLE : View.GONE);
            if (searching) showSearchResults(results);
        };
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                searchQuery = text.toString();
                refresh.run();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        modes.setOnCheckedChangeListener((group, id) -> {
            searchMode = (FeatureSearchMode) group.findViewById(id).getTag();
            refresh.run();
        });
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dismissSearchKeyboard();
            return true;
        });
        refresh.run();
        return query;
    }

    private void showSearchResults(LinearLayout results) {
        results.removeAllViews();
        for (Feature feature : Feature.values()) {
            if (!searchMode.matches(feature, searchQuery)) continue;
            StringJoiner apps = new StringJoiner(" · ");
            String firstPackage = null;
            for (String[] target : PACKAGES) if (feature.packages.contains(target[1])) {
                apps.add(target[0]);
                if (firstPackage == null) firstPackage = target[1];
            }
            if (firstPackage == null) continue;
            if (results.getChildCount() > 0) divider(results);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPaddingRelative(dp(18), dp(14), dp(16), dp(14));
            row.setBackground(ripple(surface));
            LinearLayout labels = column();
            labels.addView(text(feature.title, 17, ink, true));
            TextView categories = text(apps.toString(), 13, accent, false);
            categories.setPadding(0, dp(3), 0, dp(4));
            labels.addView(categories);
            labels.addView(text(feature.description, 13, muted, false));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
            labelParams.setMarginEnd(dp(10));
            row.addView(labels, labelParams);
            row.addView(icon(R.drawable.ic_chevron, muted), new LinearLayout.LayoutParams(dp(16), dp(24)));
            String packageName = firstPackage;
            row.setOnClickListener(view -> {
                dismissSearchKeyboard();
                startActivity(new Intent(this, MainActivity.class).putExtra(PACKAGE_PAGE, packageName)
                    .putExtra(FEATURE_ROW, feature.key));
            });
            results.addView(row);
        }
        if (results.getChildCount() == 0) {
            TextView empty = text("没有匹配的开关", 14, muted, false);
            empty.setPadding(dp(18), dp(16), dp(18), dp(16));
            results.addView(empty);
        }
    }

    private void dismissSearchKeyboard() {
        body.requestFocus();
        getWindow().getInsetsController().hide(WindowInsets.Type.ime());
    }

    private void packagePage(LinearLayout content, ScrollView scroll, String[] target) {
        LinearLayout identity = card(); identity.addView(packageIdentity(target, false)); content.addView(identity);
        notice = text(editable() ? "更改后重启手机生效。" : "请确认 LSPosed 已激活模块、共享配置可用且设备构建匹配。", 13, muted, false);
        notice.setPadding(dp(12), dp(16), dp(12), dp(12)); content.addView(notice);
        LinearLayout options = card(); boolean added = false;
        String requestedFeature = getIntent().getStringExtra(FEATURE_ROW);
        for (Feature feature : Feature.values()) if (feature.packages.contains(target[1])) {
            if (added) divider(options);
            LinearLayout row = featureRow(options, feature); added = true;
            if (feature.key.equals(requestedFeature)) {
                row.setBackground(ripple(getColor(R.color.accent_background)));
                scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                                                         int oldL, int oldT, int oldR, int oldB) {
                        scroll.removeOnLayoutChangeListener(this);
                        Rect bounds = new Rect();
                        row.getDrawingRect(bounds);
                        scroll.offsetDescendantRectToMyCoords(row, bounds);
                        scroll.scrollTo(0, bounds.top - dp(8));
                    }
                });
            }
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
        LinearLayout identity = column();
        identity.setGravity(Gravity.CENTER);
        identity.setMinimumHeight(dp(338));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_hypernova);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        identity.addView(logo, new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView name = text(getString(R.string.app_name), 34, ink, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(16), 0, dp(8));
        identity.addView(name);
        TextView version = text(getString(R.string.module_version), 14, getColor(R.color.nova_text_secondary), false);
        version.setGravity(Gravity.CENTER);
        identity.addView(version, new LinearLayout.LayoutParams(-1, -2));
        content.addView(identity);
        section(content, "来源").setTextColor(getColor(R.color.nova_text_secondary));
        LinearLayout source = paddedCard();
        infoRow(source, "许可证", "GPL-3.0-only");
        LinearLayout repository = column();
        infoRow(repository, "GitHub 仓库", REPOSITORY_URL).setTextColor(accent);
        repository.setBackground(ripple(surface));
        repository.setFocusable(true);
        repository.setOnClickListener(view -> openRepository());
        source.addView(repository);
        content.addView(source);
    }
    private void openRepository() {
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL)).addCategory(Intent.CATEGORY_BROWSABLE);
        // Resolve a generic web address so a GitHub App Link cannot take over.
        link.setSelector(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/")).addCategory(Intent.CATEGORY_BROWSABLE));
        try {
            startActivity(link);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "未找到可打开链接的浏览器", Toast.LENGTH_SHORT).show();
        }
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
    private LinearLayout featureRow(LinearLayout parent, Feature feature) {
        return switchRow(parent, feature.title, feature.description, feature.key, editable(), enabled -> {
            if (enabled) app.requestScopes(feature.packages);
        });
    }
    @SuppressWarnings("UseSwitchCompatOrMaterialCode")
    private LinearLayout switchRow(LinearLayout parent, String label, String description, String key,
                                   boolean editEnabled, Consumer<Boolean> onSaved) {
        LinearLayout item = column(); item.setPadding(dp(20), dp(14), dp(20), dp(14)); item.setBackground(ripple(surface));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(label, 17, ink, true); title.setPadding(0, 0, dp(16), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this); toggle.setContentDescription(label); toggle.setShowText(false);
        toggle.setMinHeight(dp(48)); toggle.setSwitchMinWidth(dp(52));
        GradientDrawable thumb = round(Color.WHITE, 20); thumb.setSize(dp(24), dp(24));
        toggle.setThumbTintList(null); toggle.setTrackTintList(null); toggle.setThumbDrawable(new InsetDrawable(thumb, dp(3)));
        StateListDrawable track = new StateListDrawable();
        GradientDrawable checked = round(accent, 20); checked.setSize(dp(52), dp(30));
        GradientDrawable unchecked = round(getColor(R.color.switch_off), 20); unchecked.setSize(dp(52), dp(30));
        track.addState(new int[]{android.R.attr.state_checked}, checked); track.addState(new int[]{}, unchecked); toggle.setTrackDrawable(track);
        toggle.setChecked(preferences != null && preferences.getBoolean(key, false)); toggle.setEnabled(editEnabled);
        if (!toggle.isEnabled()) toggle.setAlpha(0.45f);
        row.addView(toggle); item.addView(row);
        if (description != null) item.addView(text(description, 13, muted, false));
        parent.addView(item);
        item.setOnClickListener(view -> { if (toggle.isEnabled()) toggle.toggle(); });
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            if (saving || !editable()) return;
            boolean previous = preferences.getBoolean(key, false);
            if (commit(preferences.edit().putBoolean(key, enabled))) {
                saved("已保存，请重启手机生效。");
                onSaved.accept(enabled);
            }
            else {
                saving = true; toggle.setChecked(previous); saving = false; saveFailed();
            }
        });
        return item;
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
                if (commit(preferences.edit().putInt(SidebarGesture.HOLD_KEY, SidebarGesture.MIN_HOLD_MILLIS + slider.getProgress() * 10))) saved("触发阈值已保存，请重启手机生效。");
                else saveFailed();
            }
        });
    }
    private boolean commit(SharedPreferences.Editor update) {
        try { return update.commit(); }
        catch (io.github.libxposed.service.XposedService.ServiceException error) { return false; }
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
    // The navigation buttons expose the equivalent actions to accessibility services.
    @SuppressLint("ClickableViewAccessibility")
    private final class PageHost extends FrameLayout {
        private final NavigationSwipe swipe = new NavigationSwipe(
            ViewConfiguration.get(MainActivity.this).getScaledTouchSlop(), dp(64));
        private final Rect controlBounds = new Rect();
        private PageContent preview;
        private String previewName;
        private float gap;

        PageHost() { super(MainActivity.this); }

        boolean isRtl() { return getLayoutDirection() == View.LAYOUT_DIRECTION_RTL; }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (detail) return false;
            if (pageAnimation != null) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    WindowInsets insets = getRootWindowInsets();
                    Insets edges = insets == null ? Insets.NONE : insets.getInsets(WindowInsets.Type.systemGestures());
                    float x = event.getX(), y = event.getY();
                    boolean eligible = x >= Math.max(dp(24), edges.left) && x <= getWidth() - Math.max(dp(24), edges.right);
                    if (eligible && swipeControl != null && swipeControl.getLocalVisibleRect(controlBounds)) {
                        offsetDescendantRectToMyCoords(swipeControl, controlBounds);
                        eligible = !controlBounds.contains((int) x, (int) y);
                    }
                    swipe.start(x, y, eligible);
                }
                case MotionEvent.ACTION_MOVE -> { return drag(event); }
                case MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> resetDrag();
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (detail) return super.onTouchEvent(event);
            if (pageAnimation != null) return true;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE -> drag(event);
                case MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    swipe.cancel();
                    finishDrag(false);
                }
                case MotionEvent.ACTION_UP -> {
                    drag(event);
                    finishDrag(swipe.finish(event.getX()));
                }
            }
            return true;
        }

        private boolean drag(MotionEvent event) {
            if (!swipe.intercept(event.getX(), event.getY()) || getWidth() == 0) return false;
            float offset = swipe.offset(event.getX());
            if (offset == 0f) {
                if (preview != null) positionPages(body, preview.view, 0f, gap);
                return true;
            }
            int step = offset < 0f ? 1 : -1;
            int next = NAV_PAGES.indexOf(page) + (isRtl() ? -step : step);
            if (next < 0 || next >= NAV_PAGES.size()) {
                removePreview();
                return true;
            }

            String target = NAV_PAGES.get(next);
            gap = step * getWidth();
            offset = Math.max(-getWidth(), Math.min(getWidth(), offset));
            if (!target.equals(previewName)) {
                removePreview();
                preview = createPage(target);
                previewName = target;
                preview.view.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                preview.view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                addView(preview.view, new FrameLayout.LayoutParams(-1, -1));
            }
            positionPages(body, preview.view, offset, gap);
            return true;
        }

        private void finishDrag(boolean accepted) {
            if (preview == null) return;
            LinearLayout previous = body;
            PageContent adjacent = preview;
            String target = previewName;
            preview = null;
            previewName = null;
            if (accepted) {
                if ("settings".equals(page)) dismissSearchKeyboard();
                previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                selectPage(target, adjacent);
            }
            settlePages(previous, adjacent.view, gap, accepted ? -gap : 0f, accepted ? previous : adjacent.view);
        }

        private void removePreview() {
            if (preview != null) removeView(preview.view);
            preview = null;
            previewName = null;
            if (body != null) body.setTranslationX(0f);
        }

        void resetDrag() {
            swipe.cancel();
            removePreview();
        }
    }
    private TextView infoRow(LinearLayout parent, String label, String value) {
        TextView heading = text(label, 13, muted, false); heading.setPadding(0, dp(8), 0, dp(5)); parent.addView(heading);
        TextView detail = text(value, 15, ink, false); detail.setPadding(0, 0, 0, dp(12)); parent.addView(detail);
        return detail;
    }
    private TextView section(LinearLayout parent, String label) {
        TextView heading = text(label, 13, muted, false); heading.setPadding(dp(12), dp(22), 0, dp(10)); parent.addView(heading);
        return heading;
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
