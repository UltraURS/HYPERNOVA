// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.ads;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** The host's TYPE_AD_NEW / TYPE_ALERT_AD_NEW nodes share a response with ordinary weather cards. */
public final class WeatherAdFilter {
    private WeatherAdFilter() { }

    public static <T> List<T> withoutAds(List<T> nodes, Function<T, String> type) {
        if (nodes == null || nodes.isEmpty()) return nodes;
        List<T> kept = null;
        for (int i = 0; i < nodes.size(); i++) {
            T node = nodes.get(i);
            String value = node == null ? null : type.apply(node);
            if ("7".equals(value) || "10".equals(value)) {
                if (kept == null) kept = new ArrayList<>(nodes.subList(0, i));
            } else if (kept != null) kept.add(node);
        }
        return kept == null ? nodes : kept;
    }
}
