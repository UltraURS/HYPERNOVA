// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.ads;

import android.content.Context;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.hypernova.hook.HookLog;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Uses the calendar's promotion-off path without disabling shared calendar requests. */
public final class CalendarAdHook {
    private CalendarAdHook() { }

    public static void install(ClassLoader loader) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            Class<?> settings = XposedHelpers.findClass("com.android.calendar.settings.y", loader);
            Class<?> ads = XposedHelpers.findClass("com.miui.calendar.ad.AdsExtraManager", loader);
            Class<?> presenter = XposedHelpers.findClass("com.android.calendar.cards.AdPresenter", loader);
            var promotion = XposedHelpers.findMethodExact(settings, "h", Context.class);
            var cards = XposedHelpers.findMethodExact(ads, "f", Context.class);
            var oneAd = XposedHelpers.findMethodExact(ads, "g", Context.class, long.class, String.class);
            var cached = XposedHelpers.findMethodExact(ads, "d", int.class);
            var removed = XposedHelpers.findMethodExact(presenter, "isRemoveCard");
            Field instance = XposedHelpers.findField(ads, "e");
            Field cardCall = XposedHelpers.findField(ads, "a"), oneCall = XposedHelpers.findField(ads, "b");
            var cancel = XposedHelpers.findMethodExact(XposedHelpers.findClass("retrofit2.Call", loader), "cancel");
            hooks.add(XposedBridge.hookMethod(promotion, XC_MethodReplacement.returnConstant(false)));
            hooks.add(XposedBridge.hookMethod(cards, XC_MethodReplacement.returnConstant(null)));
            hooks.add(XposedBridge.hookMethod(oneAd, XC_MethodReplacement.returnConstant(null)));
            hooks.add(XposedBridge.hookMethod(cached, XC_MethodReplacement.returnConstant(null)));
            hooks.add(XposedBridge.hookMethod(removed, XC_MethodReplacement.returnConstant(true)));
            // Setup runs after APK verification. Cancel only this manager's two existing ad calls.
            try {
                Object manager = instance.get(null);
                if (manager != null) for (Field field : new Field[]{cardCall, oneCall}) {
                    Object call = field.get(manager);
                    if (call != null) cancel.invoke(call);
                }
            } catch (Throwable error) { HookLog.error("calendar-ads cancel pending", error); }
            HookLog.info("calendar-ads INSTALLED (promotion off, ad requests blocked, cached cards suppressed)");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            HookLog.error("calendar-ads install", error);
        }
    }
}
