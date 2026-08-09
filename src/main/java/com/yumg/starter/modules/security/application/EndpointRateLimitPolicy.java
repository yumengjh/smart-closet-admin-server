package com.yumg.starter.modules.security.application;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.util.AntPathMatcher;

/** Matches the explicitly protected endpoints used by the runtime rate-limit policy. */
public final class EndpointRateLimitPolicy {
    private final String[] patterns;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public EndpointRateLimitPolicy(String configuredPatterns) {
        this.patterns = Arrays.stream((configuredPatterns == null ? "" : configuredPatterns).split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toArray(String[]::new);
    }

    public boolean matches(String path) {
        return matchingPattern(path).isPresent();
    }

    public Optional<String> matchingPattern(String path) {
        return Arrays.stream(patterns).filter(pattern -> matcher.match(pattern, path)).findFirst();
    }
}
