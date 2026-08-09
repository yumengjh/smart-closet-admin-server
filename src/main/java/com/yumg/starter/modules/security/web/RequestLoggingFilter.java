package com.yumg.starter.modules.security.web;

import com.yumg.starter.common.web.TraceIdFilter;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.yumg.starter.common.web.ClientIpResolver;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final RuntimeSettingService settings;
    private final ClientIpResolver clientIp;
    public RequestLoggingFilter(RuntimeSettingService settings, ClientIpResolver clientIp) {
        this.settings = settings; this.clientIp = clientIp;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            if (settings.enabled("security.request-log.enabled")) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                String user = authentication == null || !authentication.isAuthenticated() ? null : authentication.getName();
                log.info("request method={} path={} status={} durationMs={} traceId={} userId={} clientIp={}", request.getMethod(), request.getRequestURI(), response.getStatus(), (System.nanoTime()-started)/1_000_000, MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY), user, clientIp.resolve(request));
            }
        }
    }
}
