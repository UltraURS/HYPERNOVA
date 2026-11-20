// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.ads;

import android.content.Context;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Blocks dedicated weather ads and removes ad nodes before shared cards fetch media or render. */
public final class WeatherAdHook {
    private WeatherAdHook() { }

    public static void install(ClassLoader loader) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Class<?> presenter = XposedHelpers.findClass("q1.e", loader);
            Class<?> service = XposedHelpers.findClass("w1.d", loader);
            Class<?> callback = XposedHelpers.findClass("retrofit2.Callback", loader);
            Class<?> spider = XposedHelpers.findClass("I1.a", loader);
            Class<?> parser = XposedHelpers.findClass("H1.b", loader);
            Class<?> card = XposedHelpers.findClass("com.miui.weather2.structures.InfoCardBean", loader);
            Class<?> node = XposedHelpers.findClass("com.miui.weather2.structures.InfoListNodeBean", loader);
            Class<?> detail = XposedHelpers.findClass("com.miui.weather2.ad.dailyDetailPageAd.DailyDetailAdView", loader);
            Class<?> info = XposedHelpers.findClass("com.miui.weather2.structures.InfoDataBean", loader);
            Class<?> data = XposedHelpers.findClass("com.miui.weather2.structures.DailyForecastAdData", loader);
            Class<?> tracker = XposedHelpers.findClass("com.miui.weather2.tools.u", loader);
            var forecast = XposedHelpers.findMethodExact(presenter, "j", String.class, int.class);
            var forecastRequest = XposedHelpers.findMethodExact(service, "a", Map.class, callback);
            var speakerRequest = XposedHelpers.findMethodExact(spider, "f", Context.class, int.class, int.class, String.class, String.class);
            var speakerCache = XposedHelpers.findMethodExact(parser, "b", String.class, Context.class);
            var cards = XposedHelpers.findMethodExact(card, "getList");
            var type = XposedHelpers.findMethodExact(node, "getType");
            var inflated = XposedHelpers.findMethodExact(detail, "onFinishInflate");
            var bind = XposedHelpers.findMethodExact(detail, "m", info, data);
            var track = XposedHelpers.findMethodExact(tracker, "a", String.class, String.class, String[].class, String.class, Context.class);
            var click = XposedHelpers.findMethodExact(tracker, "b", String.class, String.class, String[].class, String.class, String.class, Context.class);
            for (var method : new java.lang.reflect.Method[]{forecast, forecastRequest, speakerRequest, speakerCache, track, click}) {
                hooks.add(XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(null)));
            }
            hooks.add(XposedBridge.hookMethod(cards, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    param.setResult(WeatherAdFilter.withoutAds((List<?>) param.getResult(), item -> {
                        try { return (String) type.invoke(item); }
                        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                    }));
                }
            }));
            hooks.add(XposedBridge.hookMethod(inflated, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) ((View) param.thisObject).setVisibility(View.GONE);
                }
            }));
            hooks.add(XposedBridge.hookMethod(bind, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    ((View) param.thisObject).setVisibility(View.GONE);
                    param.setResult(null);
                }
            }));
            HookLog.info("weather-ads INSTALLED (dedicated requests blocked, shared ad nodes filtered)");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("weather-ads install", error);
        }
    }
}
