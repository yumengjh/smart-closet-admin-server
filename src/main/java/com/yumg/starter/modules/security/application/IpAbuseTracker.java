package com.yumg.starter.modules.security.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import java.net.InetAddress;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IpAbuseTracker {
    private static final Logger log = LoggerFactory.getLogger(IpAbuseTracker.class);
    private static final int MAX_STATES = 100_000;
    private final RuntimeSettingService settings;
    private final IpAccessRuleService rules;
    private final Cache<String, Rejections> rejections = Caffeine.newBuilder()
            .maximumSize(MAX_STATES)
            .expireAfterAccess(24, TimeUnit.HOURS)
            .build();

    public IpAbuseTracker(RuntimeSettingService settings, IpAccessRuleService rules) {
        this.settings = settings;
        this.rules = rules;
    }

    public void recordRateLimitRejection(String ip, String triggerType, String policy) {
        if (!settings.enabled("security.ip-auto-ban.enabled") || unsafeTarget(ip)) return;
        Instant now = Instant.now();
        int threshold = settings.integer("security.ip-auto-ban.rejection-threshold");
        long windowMillis = settings.integer("security.ip-auto-ban.window-seconds") * 1000L;
        Rejections state = rejections.asMap().compute(ip, (ignored, current) -> {
            if (current == null || now.toEpochMilli() >= current.expiresAtMillis()) {
                return new Rejections(now, now, 1, now.toEpochMilli() + windowMillis, false);
            }
            return new Rejections(current.firstAt(), now, current.count() + 1,
                    current.expiresAtMillis(), current.blockCreated());
        });
        if (state.count() < threshold || state.blockCreated()) return;
        Rejections blocked = new Rejections(state.firstAt(), state.lastAt(), state.count(),
                state.expiresAtMillis(), true);
        if (!rejections.asMap().replace(ip, state, blocked)) return;
        try {
            Instant expiresAt = now.plusSeconds(settings.integer("security.ip-auto-ban.duration-seconds"));
            String reason = "Repeated rate-limit violations: " + sanitizePolicy(policy);
            rules.blockAutomatically(ip, triggerType, reason, state.count(), state.firstAt(),
                    state.lastAt(), expiresAt);
            log.warn("ip_auto_blocked ip={} trigger={} violations={} expiresAt={}",
                    ip, triggerType, state.count(), expiresAt);
        } catch (RuntimeException exception) {
            rejections.asMap().replace(ip, blocked, state);
            log.error("Failed to persist automatic IP block for {}", ip, exception);
        }
    }

    int stateCount() { return rejections.asMap().size(); }

    private boolean unsafeTarget(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            return address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isMulticastAddress();
        } catch (Exception ignored) {
            return true;
        }
    }

    private String sanitizePolicy(String policy) {
        if (policy == null || policy.isBlank()) return "global";
        String value = policy.replaceAll("[\\p{Cntrl}]", " ");
        return value.length() <= 180 ? value : value.substring(0, 180);
    }

    private record Rejections(Instant firstAt, Instant lastAt, int count,
                              long expiresAtMillis, boolean blockCreated) {}
}
