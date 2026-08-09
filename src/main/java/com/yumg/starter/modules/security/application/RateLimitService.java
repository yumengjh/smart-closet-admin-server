package com.yumg.starter.modules.security.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {
    private static final int MAX_STATES = 100_000;
    private final RuntimeSettingService settings;
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .maximumSize(MAX_STATES)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();

    public RateLimitService(RuntimeSettingService settings) { this.settings = settings; }

    public RateLimitDecision evaluate(String key) {
        if (!settings.enabled("security.rate-limit.enabled")) {
            return RateLimitDecision.allowed(Integer.MAX_VALUE, Integer.MAX_VALUE, Instant.now());
        }
        return evaluate(key, settings.integer("security.rate-limit.capacity"),
                settings.integer("security.rate-limit.window-seconds"));
    }

    public RateLimitDecision evaluate(String key, int capacity, int windowSeconds) {
        long now = Instant.now().toEpochMilli();
        long duration = windowSeconds * 1000L;
        Window window = windows.asMap().compute(key, (ignored, current) -> {
            if (current == null || now >= current.expiresAt) {
                return new Window(now + duration, 1, true);
            }
            if (current.count >= capacity) return new Window(current.expiresAt, current.count, false);
            return new Window(current.expiresAt, current.count + 1, true);
        });
        Instant resetAt = Instant.ofEpochMilli(window.expiresAt);
        if (!window.allowed) {
            long retry = Math.max(1, (window.expiresAt - now + 999) / 1000);
            return RateLimitDecision.denied(capacity, resetAt, retry);
        }
        return RateLimitDecision.allowed(capacity, Math.max(0, capacity - window.count), resetAt);
    }

    public boolean allow(String key) { return evaluate(key).allowed(); }
    public boolean allow(String key, int capacity, int windowSeconds) {
        return evaluate(key, capacity, windowSeconds).allowed();
    }
    public long cleanupExpired(Instant referenceTime) {
        long now = referenceTime.toEpochMilli();
        int before = windows.asMap().size();
        windows.asMap().entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        return before - windows.asMap().size();
    }
    public long expiredStateCount(Instant referenceTime) {
        long now = referenceTime.toEpochMilli();
        return windows.asMap().values().stream().filter(window -> window.expiresAt <= now).count();
    }
    public int stateCount() { return windows.asMap().size(); }
    private record Window(long expiresAt, int count, boolean allowed) {}
}
