package com.yumg.starter.modules.security.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RateLimitServiceTest {
    @Test
    void reportsRemainingQuotaAndRetryDelay() {
        RateLimitService service = new RateLimitService(Mockito.mock(RuntimeSettingService.class));

        assertThat(service.evaluate("ip", 2, 60).remaining()).isEqualTo(1);
        assertThat(service.evaluate("ip", 2, 60).remaining()).isZero();
        RateLimitDecision denied = service.evaluate("ip", 2, 60);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 60L);
        assertThat(service.stateCount()).isEqualTo(1);
    }
}
