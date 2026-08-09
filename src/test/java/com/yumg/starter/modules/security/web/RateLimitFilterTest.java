package com.yumg.starter.modules.security.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.common.api.ApiErrorWriter;
import com.yumg.starter.common.web.ClientIpResolver;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import com.yumg.starter.modules.security.application.IpAbuseTracker;
import com.yumg.starter.modules.security.application.RateLimitDecision;
import com.yumg.starter.modules.security.application.RateLimitService;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
    @Test
    void countsMatchedPathsInOnePatternBucketInsteadOfOneBucketPerConcreteUri()
            throws Exception {
        RateLimitService limits = Mockito.mock(RateLimitService.class);
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        ApiErrorWriter errors = new ApiErrorWriter();
        IpAbuseTracker abuse = Mockito.mock(IpAbuseTracker.class);
        FilterChain chain = Mockito.mock(FilterChain.class);
        when(settings.string("security.endpoint.rate-limit.patterns"))
                .thenReturn("/api/v1/users/**");
        when(settings.integer("security.endpoint.rate-limit.capacity")).thenReturn(20);
        when(settings.integer("security.endpoint.rate-limit.window-seconds")).thenReturn(60);
        when(limits.evaluate(Mockito.startsWith("global:"))).thenReturn(allowed());
        when(limits.evaluate(Mockito.startsWith("endpoint:"), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(allowed());
        RateLimitFilter filter = new RateLimitFilter(limits, settings, errors,
                new ClientIpResolver(""), abuse);

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/users/one");
        first.setRemoteAddr("203.0.113.8");
        filter.doFilter(first, new MockHttpServletResponse(), chain);
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/users/two");
        second.setRemoteAddr("203.0.113.8");
        filter.doFilter(second, new MockHttpServletResponse(), chain);

        verify(limits, times(2)).evaluate("endpoint:203.0.113.8:/api/v1/users/**", 20, 60);
        verify(chain, times(2)).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void returnsRetryHeadersAndRecordsARejectedEndpointRequest() throws Exception {
        RateLimitService limits = Mockito.mock(RateLimitService.class);
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        IpAbuseTracker abuse = Mockito.mock(IpAbuseTracker.class);
        FilterChain chain = Mockito.mock(FilterChain.class);
        when(settings.string("security.endpoint.rate-limit.patterns"))
                .thenReturn("/api/v1/auth/login");
        when(settings.integer("security.endpoint.rate-limit.capacity")).thenReturn(20);
        when(settings.integer("security.endpoint.rate-limit.window-seconds")).thenReturn(60);
        when(limits.evaluate("global:203.0.113.8")).thenReturn(allowed());
        when(limits.evaluate("endpoint:203.0.113.8:/api/v1/auth/login", 20, 60))
                .thenReturn(RateLimitDecision.denied(20, Instant.now().plusSeconds(17), 17));
        RateLimitFilter filter = new RateLimitFilter(limits, settings, new ApiErrorWriter(),
                new ClientIpResolver(""), abuse);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("17");
        verify(abuse).recordRateLimitRejection(
                "203.0.113.8", "RATE_LIMIT_ENDPOINT", "/api/v1/auth/login");
        verify(chain, never()).doFilter(Mockito.any(), Mockito.any());
    }

    @Test
    void usesTheDedicatedControlPlaneBucketWithoutAutomaticEscalation() throws Exception {
        RateLimitService limits = Mockito.mock(RateLimitService.class);
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        IpAbuseTracker abuse = Mockito.mock(IpAbuseTracker.class);
        FilterChain chain = Mockito.mock(FilterChain.class);
        when(settings.integer("security.control-plane.rate-limit.capacity")).thenReturn(600);
        when(settings.integer("security.control-plane.rate-limit.window-seconds")).thenReturn(60);
        when(limits.evaluate("control:203.0.113.8", 600, 60))
                .thenReturn(RateLimitDecision.denied(
                        600, Instant.now().plusSeconds(17), 17));
        RateLimitFilter filter = new RateLimitFilter(limits, settings, new ApiErrorWriter(),
                new ClientIpResolver(""), abuse);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/system/runtime-config");
        request.setRemoteAddr("203.0.113.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("600");
        verify(abuse, never()).recordRateLimitRejection(
                Mockito.anyString(), Mockito.anyString(), Mockito.anyString());
        verify(chain, never()).doFilter(Mockito.any(), Mockito.any());
    }

    private RateLimitDecision allowed() {
        return RateLimitDecision.allowed(120, 119, Instant.now().plusSeconds(60));
    }
}
