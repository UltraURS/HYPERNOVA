// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.ui;

import io.github.hypernova.config.Feature;
import java.util.Locale;

enum FeatureSearchMode {
    NAME("开关名"),
    DESCRIPTION("功能描述"),
    ALL("全部");

    final String label;

    FeatureSearchMode(String label) {
        this.label = label;
    }

    boolean matches(Feature feature, String query) {
        String term = query.strip().toLowerCase(Locale.ROOT);
        return !term.isEmpty() && (
            (this != DESCRIPTION && feature.title.toLowerCase(Locale.ROOT).contains(term))
                || (this != NAME && feature.description.toLowerCase(Locale.ROOT).contains(term)));
    }
}
