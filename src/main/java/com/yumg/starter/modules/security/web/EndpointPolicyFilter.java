package com.yumg.starter.modules.security.web;

import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.common.api.ApiErrorWriter;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class EndpointPolicyFilter extends OncePerRequestFilter {
    private final RuntimeSettingService settings; private final ApiErrorWriter errors;
    private final AntPathMatcher matcher = new AntPathMatcher();
    public EndpointPolicyFilter(RuntimeSettingService settings, ApiErrorWriter errors) { this.settings = settings; this.errors = errors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/") || HttpMethod.OPTIONS.matches(request.getMethod()); }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/system/runtime-config") || path.startsWith("/api/v1/system/runtime-caches")) { chain.doFilter(request, response); return; }
        boolean disabled = Arrays.stream(settings.string("security.endpoint.disabled-patterns").split(",")).map(String::trim).filter(pattern -> !pattern.isEmpty()).anyMatch(pattern -> matcher.match(pattern, path));
        if (disabled) { errors.write(response, ApiErrorCode.ENDPOINT_DISABLED); return; }
        chain.doFilter(request, response);
    }
}
