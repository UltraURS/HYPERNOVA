package io.github.hypernova.config;

import java.util.Set;

public enum Feature {
    TRAFFIC("traffic_daily_usage", "今日移动流量", "在移动数据磁贴标题显示今日用量，保留开关状态。全部移动网络，不含 Wi-Fi。", "com.android.systemui"),
    OTA_ENTRY("ota_hide_device_entry", "隐藏系统更新条目", "仅隐藏设置中 miui_updater 对应条目；不包含搜索、通知或更新请求阻断。", "com.android.settings"),
    RESET_ENTRY("reset_hide_device_entry", "隐藏恢复出厂入口", "隐藏“我的设备”、底部推荐及设置搜索中的对应结果；不阻断实际重置。", "com.android.settings"),
    NOTIFICATION_CONTROLS("notification_item_controls", "通知锁定与置顶", "长按通知，在“更多设置”两侧选择锁定或置顶。各保留一条通知；置顶条目独立显示，点击取消立即恢复。", "com.android.systemui"),
    NOTIFICATION_CLEAR_ALL("notification_clear_all", "通知中心一键清理", "竖屏时在通知中心底部居中显示清理按钮，横屏时移到右侧中部。保留锁定及系统不可清除的通知，单独置顶不阻止清理。", "com.android.systemui"),
    SIDEBAR_GESTURE("sidebar_back_gesture", "屏幕边缘返回手势呼出侧边栏", "左右两侧返回手势的系统震动后开始计时，到期再次震动并切换图标，松手呼出、回划取消。需先开启系统侧边栏，并勾选系统桌面与手机管家作用域。", "com.miui.home", "com.miui.securitycenter"),
    SIDEBAR_HIDE_TRIGGER("sidebar_hide_native_trigger", "隐藏系统侧边栏触发条", "隐藏并禁用原生触发条的触摸区域，保留侧边栏服务和面板；可配合返回手势呼出，普通返回仍由系统处理。", "com.miui.securitycenter"),
    CALENDAR_ADS("calendar_block_ads", "屏蔽日历广告", "阻止日历的专用广告请求，并屏蔽推广及缓存广告卡片；保留日程、节日和普通日历请求。", "com.android.calendar"),
    WEATHER_ADS("weather_block_ads", "屏蔽天气广告", "阻止天气的专用广告请求，隐藏预报广告并过滤缓存及共用内容中的广告卡片；保留天气预报、空气质量和普通内容。", "com.miui.weather2"),
    HEALTH_FORCE_UPDATE("health_hide_force_update", "屏蔽强制更新弹窗", "跳过小米运动健康中只有“下载并更新”按钮的 App 更新提示。仍可在应用内手动检查更新。", "com.mi.health"),
    SIDEBAR_SWIPE_CLOSE("sidebar_swipe_close", "侧边栏向外滑动收回", "在侧边栏应用列表上向所在屏幕边缘滑动，松手复用系统动画收回；左侧向左、右侧向右，保留纵向滚动与长按拖动。", "com.miui.securitycenter"),
    FREEFORM_CLOSE("freeform_close_button", "小窗右上角关闭按钮", "在普通小窗右上角显示 ×，点击沿用系统原生关闭：退出当前小窗并将任务转到后台。", "com.android.systemui"),
    BLUETOOTH_RECONNECT("bluetooth_reconnect", "蓝牙自动重连", "在系统蓝牙的已配对设备详情页增加自动连接和 1～5 次尝试设置，每台默认关闭。需同时勾选设置与系统界面作用域；手动断开后暂停，重新开蓝牙或重启恢复。", "com.android.settings", "com.android.systemui"),
    BLUETOOTH_PROMPTS("bluetooth_reconnect_prompts", "蓝牙重连提示", "尝试重连、成功或次数耗尽时显示短暂文字提示。需先开启蓝牙自动重连；仅关闭提示不会停止重连。", "com.android.systemui"),
    AUTO_POWER_SAVE("auto_power_save_options", "自动省电设置", "在系统省电模式页增加 HYPERNOVA 分类，可设置未接电时低于 20%～80% 自动开启省电。页面内开关默认关闭、阈值默认 20%，保存后立即生效。", "com.miui.securitycenter"),
    XIAOAI_CUSTOM("xiaoai_custom", "自定义模型与人格", "在超级小爱设置中管理 API Key 和模型，在侧栏的“人格”中设置头像、身份、性格与表达风格。仅参考自定义人格需使用自定义模型。", "com.miui.voiceassist"),
    SCREENSHOT_MENU("screenshot_tile_menu", "控制中心截屏菜单", "长按截屏磁贴可选择截屏、区域截屏或长截屏。区域截屏在系统选区界面保存。需勾选系统界面和截屏作用域。", "com.android.systemui", "com.miui.screenshot");

    public static final String PACKAGE = "io.github.hypernova";
    public static final String PREFS = "features";
    public static final String SCHEMA = "config_version";
    public final String key, title, description;
    public final Set<String> packages;
    Feature(String key, String title, String description, String... packages) {
        this.key = key;
        this.title = title;
        this.description = description;
        this.packages = Set.of(packages);
    }
}
