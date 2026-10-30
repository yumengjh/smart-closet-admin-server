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
    /**
     * Account-level backoff that does not change the persisted account status. Used for
     * super administrators, who are never locked (locking the last one would need offline
     * recovery) but must still stop accepting password guesses once the threshold is hit (#162).
     */
    private final Cache<String, Long> throttledUntil = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(24, TimeUnit.HOURS)
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
        boolean reached = value.count >= threshold;
        if (reached) throttledUntil.put(username, lockUntil().toEpochMilli());
        return reached;
    }
    /** Whether password verification for this account is currently refused by the account backoff. */
    public boolean isThrottled(String username) {
        if (!settings.enabled("security.brute-force.enabled")) return false;
        Long until = throttledUntil.getIfPresent(username);
        return until != null && Instant.now().toEpochMilli() < until;
    }
    public void clear(String username) { failures.invalidate(username); throttledUntil.invalidate(username); }
    public Instant lockUntil() { return Instant.now().plusSeconds(settings.integer("security.brute-force.lock-seconds")); }
    public long cleanupExpired(Instant referenceTime) {
        long now = referenceTime.toEpochMilli(); int before = failures.asMap().size();
        failures.asMap().entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        throttledUntil.asMap().entrySet().removeIf(entry -> entry.getValue() <= now);
        return before - failures.asMap().size();
    }
    public long expiredStateCount(Instant referenceTime) {
        long now = referenceTime.toEpochMilli();
        return failures.asMap().values().stream().filter(value -> value.expiresAt <= now).count();
    }
    public int stateCount() { return failures.asMap().size(); }
    private record Failures(long expiresAt, int count) {}
}
