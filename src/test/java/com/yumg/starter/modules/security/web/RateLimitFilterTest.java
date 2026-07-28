package com.yumg.starter.modules.security.web;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.common.api.ApiErrorWriter;
import com.yumg.starter.common.web.ClientIpResolver;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import com.yumg.starter.modules.security.application.RateLimitService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {
    @Test
    void countsEveryPathInOneGlobalBucketAndMatchedPathsInTheirEndpointBucket()
            throws Exception {
        RateLimitService limits = Mockito.mock(RateLimitService.class);
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        ApiErrorWriter errors = Mockito.mock(ApiErrorWriter.class);
        FilterChain chain = Mockito.mock(FilterChain.class);
        when(settings.string("security.endpoint.rate-limit.patterns"))
                .thenReturn("/api/v1/auth/login");
        when(settings.integer("security.endpoint.rate-limit.capacity")).thenReturn(20);
        when(settings.integer("security.endpoint.rate-limit.window-seconds")).thenReturn(60);
        when(limits.allow(Mockito.anyString())).thenReturn(true);
        when(limits.allow(Mockito.anyString(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(true);
        RateLimitFilter filter = new RateLimitFilter(
                limits, settings, errors, new ClientIpResolver(""));

        MockHttpServletRequest login =
                new MockHttpServletRequest("POST", "/api/v1/auth/login");
        login.setRemoteAddr("203.0.113.8");
        filter.doFilter(login, new MockHttpServletResponse(), chain);
        MockHttpServletRequest profile =
                new MockHttpServletRequest("GET", "/api/v1/users/me");
        profile.setRemoteAddr("203.0.113.8");
        filter.doFilter(profile, new MockHttpServletResponse(), chain);

        verify(limits, times(2)).allow("global:203.0.113.8");
        verify(limits).allow("endpoint:203.0.113.8:/api/v1/auth/login", 20, 60);
        verify(chain, times(2)).doFilter(
                Mockito.any(MockHttpServletRequest.class),
                Mockito.any(MockHttpServletResponse.class));
    }
}
