package com.yumg.starter.modules.security.application;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class IpAbuseTrackerTest {
    @Test
    void createsOneTemporaryBlockOnlyAfterRepeatedRejections() {
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        IpAccessRuleService rules = Mockito.mock(IpAccessRuleService.class);
        when(settings.enabled("security.ip-auto-ban.enabled")).thenReturn(true);
        when(settings.integer("security.ip-auto-ban.rejection-threshold")).thenReturn(3);
        when(settings.integer("security.ip-auto-ban.window-seconds")).thenReturn(300);
        when(settings.integer("security.ip-auto-ban.duration-seconds")).thenReturn(900);
        IpAbuseTracker tracker = new IpAbuseTracker(settings, rules);

        tracker.recordRateLimitRejection("203.0.113.8", "RATE_LIMIT_ENDPOINT", "/login");
        tracker.recordRateLimitRejection("203.0.113.8", "RATE_LIMIT_ENDPOINT", "/login");
        verify(rules, never()).blockAutomatically(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());

        tracker.recordRateLimitRejection("203.0.113.8", "RATE_LIMIT_ENDPOINT", "/login");
        tracker.recordRateLimitRejection("203.0.113.8", "RATE_LIMIT_ENDPOINT", "/login");

        verify(rules).blockAutomatically(eq("203.0.113.8"), eq("RATE_LIMIT_ENDPOINT"),
                ArgumentMatchers.contains("/login"), eq(3L),
                ArgumentMatchers.any(Instant.class), ArgumentMatchers.any(Instant.class),
                ArgumentMatchers.any(Instant.class));
    }

    @Test
    void neverAutomaticallyBlocksLoopback() {
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        IpAccessRuleService rules = Mockito.mock(IpAccessRuleService.class);
        when(settings.enabled("security.ip-auto-ban.enabled")).thenReturn(true);
        IpAbuseTracker tracker = new IpAbuseTracker(settings, rules);

        tracker.recordRateLimitRejection("127.0.0.1", "RATE_LIMIT_GLOBAL", "global");

        verify(rules, never()).blockAutomatically(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }
}
