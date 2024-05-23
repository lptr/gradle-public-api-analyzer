package org.gradle.research;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.core.util.strings.Atom;

import javax.annotation.Nonnull;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class PublicApiDetector {
    private static final ImmutableList<Pattern> publicApiPackages = Stream.of(
            "org/gradle/",
            "org/gradle/api/.*",
            "org/gradle/authentication/.*",
            "org/gradle/build/.*",
            "org/gradle/buildconfiguration/.*",
            "org/gradle/buildinit/.*",
            "org/gradle/caching/.*",
            "org/gradle/concurrent/.*",
            "org/gradle/deployment/.*",
            "org/gradle/external/javadoc/.*",
            "org/gradle/ide/.*",
            "org/gradle/ivy/.*",
            "org/gradle/jvm/.*",
            "org/gradle/language/.*",
            "org/gradle/maven/.*",
            "org/gradle/nativeplatform/.*",
            "org/gradle/normalization/.*",
            "org/gradle/platform/.*",
            "org/gradle/plugin/devel/.*",
            "org/gradle/plugin/use/",
            "org/gradle/plugin/management/",
            "org/gradle/plugins/.*",
            "org/gradle/process/.*",
            "org/gradle/testfixtures/.*",
            "org/gradle/testing/jacoco/.*",
            "org/gradle/tooling/.*",
            "org/gradle/swiftpm/.*",
            "org/gradle/model/.*",
            "org/gradle/testkit/.*",
            "org/gradle/testing/.*",
            "org/gradle/vcs/.*",
            "org/gradle/work/.*",
            "org/gradle/workers/.*",
            "org/gradle/util/.*"
        )
        .map(Pattern::compile)
        .collect(ImmutableList.toImmutableList());

    private static final LoadingCache<IClass, Boolean> publicApiLookup = CacheBuilder.newBuilder()
        .build(new CacheLoader<>() {
            @Nonnull
            @Override
            public Boolean load(@Nonnull IClass type) {
                Atom packageName = type.getName().getPackage();
                if (packageName == null) {
                    return false;
                }
                var packageWithTrailingSlash = packageName + "/";
                return publicApiPackages.stream()
                           .anyMatch(pattern -> pattern.matcher(packageWithTrailingSlash).matches())
                       && !packageWithTrailingSlash.contains("/internal/");
            }
        });

    public static boolean isPublicApi(IClass type) {
        return publicApiLookup.getUnchecked(type);
    }
}
