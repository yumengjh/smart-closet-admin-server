package com.yumg.starter.modules.security.web;

import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.common.api.ApiErrorWriter;
import com.yumg.starter.common.web.ClientIpResolver;
import com.yumg.starter.common.web.RequestPaths;
import com.yumg.starter.modules.security.application.IpAccessRuleService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class IpAccessFilter extends OncePerRequestFilter {
    private final ApiErrorWriter errors;
    private final ClientIpResolver clientIp;
    private final IpAccessRuleService rules;

    public IpAccessFilter(ApiErrorWriter errors, ClientIpResolver clientIp,
                          IpAccessRuleService rules) {
        this.errors = errors;
        this.clientIp = clientIp;
        this.rules = rules;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.isApi(request)
                || HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isRuntimeRecoveryEndpoint(RequestPaths.normalized(request))) {
            chain.doFilter(request, response);
            return;
        }
        String ip = clientIp.resolve(request);
        if (rules.decision(ip, Instant.now()) == IpAccessRuleService.Decision.DENY) {
            errors.write(response, ApiErrorCode.IP_ACCESS_DENIED);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isRuntimeRecoveryEndpoint(String path) {
        return RequestPaths.isUnder(path, "/api/v1/system/runtime-config")
                || RequestPaths.isUnder(path, "/api/v1/system/runtime-caches")
                || RequestPaths.isUnder(path, "/api/v1/system/ip-access-rules");
    }
}
