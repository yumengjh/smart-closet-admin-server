package com.yumg.starter.modules.security.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

@Service
public class BruteForceService {
    private final RuntimeSettingService settings;
    private final Cache<String, Failures> failures = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(24, TimeUnit.HOURS)
            .build();
    public BruteForceService(RuntimeSettingService settings) { this.settings = settings; }
    public boolean recordFailure(String username) {
        if (!settings.enabled("security.brute-force.enabled")) return false;
        long now = Instant.now().toEpochMilli();
        int threshold = settings.integer("security.brute-force.failure-threshold");
        Failures value = failures.asMap().compute(username, (ignored, current) -> {
            long duration = settings.integer("security.brute-force.window-seconds") * 1000L;
            if (current == null || now >= current.expiresAt) return new Failures(now + duration, 1);
            return new Failures(current.expiresAt, current.count + 1);
        });
        return value.count >= threshold;
    }
    public void clear(String username) { failures.invalidate(username); }
    public Instant lockUntil() { return Instant.now().plusSeconds(settings.integer("security.brute-force.lock-seconds")); }
    public long cleanupExpired(Instant referenceTime) {
        long now = referenceTime.toEpochMilli(); int before = failures.asMap().size();
        failures.asMap().entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        return before - failures.asMap().size();
    }
    public long expiredStateCount(Instant referenceTime) {
        long now = referenceTime.toEpochMilli();
        return failures.asMap().values().stream().filter(value -> value.expiresAt <= now).count();
    }
    public int stateCount() { return failures.asMap().size(); }
    private record Failures(long expiresAt, int count) {}
}
