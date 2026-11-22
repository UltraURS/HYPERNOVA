// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.stk;

import org.junit.Test;
import static org.junit.Assert.*;

public class StkWelcomeHookTest {
    @Test public void recognizesCarrierGreetingsWithLineBreaksAndPunctuation() {
        assertTrue(StkWelcomeHook.isWelcome("尊敬的用户，感谢您使用中国移动通信服务！"));
        assertTrue(StkWelcomeHook.isWelcome("尊敬的 中国移动 客户：\n 您好！\n感谢您使用　中国移动。"));
        assertTrue(StkWelcomeHook.isWelcome("欢迎您使用中国广电。"));
    }

    @Test public void leavesNormalStkTextAlone() {
        for (String message : new String[]{null, "", "开机菜单导航", "欢迎使用本服务",
            "尊敬的用户，您的账户余额为 10 元", "请输入 SIM 卡密码", "此前收到的消息：欢迎使用中国移动"}) {
            assertFalse(message, StkWelcomeHook.isWelcome(message));
        }
    }

    @Test public void leavesActionAndAccountMessagesWithAGreetingAlone() {
        for (String suffix : new String[]{"，验证码为 1234", "，本业务每月扣费 2 元",
            "，请确认是否办理", "，您的余额不足，请充值", "，成功订购了本业务"}) {
            assertFalse(suffix, StkWelcomeHook.isWelcome("尊敬的用户，感谢您使用中国移动" + suffix));
        }
    }
}
