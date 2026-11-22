// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public final class ScopeTargets {
    public static final String SYNC_KEY = "sync_feature_scope";

    private ScopeTargets() { }

    public static Set<String> enabledPackages(Predicate<Feature> enabled) {
        Set<String> packages = new LinkedHashSet<>();
        for (Feature feature : Feature.values()) if (enabled.test(feature)) packages.addAll(feature.packages);
        return packages;
    }

    public static List<String> missing(Set<String> requested, Collection<String> scope, Predicate<String> installed) {
        return requested.stream().filter(pkg -> !scope.contains(pkg) && installed.test(pkg)).sorted().toList();
    }
}
