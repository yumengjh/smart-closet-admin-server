package com.yumg.starter.modules.security.web;

import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.common.api.ApiErrorWriter;
import com.yumg.starter.common.web.ClientIpResolver;
import com.yumg.starter.common.web.RequestPaths;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import com.yumg.starter.modules.security.application.EndpointRateLimitPolicy;
import com.yumg.starter.modules.security.application.IpAbuseTracker;
import com.yumg.starter.modules.security.application.RateLimitDecision;
import com.yumg.starter.modules.security.application.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService limits;
    private final RuntimeSettingService settings;
    private final ApiErrorWriter errors;
    private final ClientIpResolver clientIp;
    private final IpAbuseTracker abuse;

    public RateLimitFilter(RateLimitService limits, RuntimeSettingService settings,
                           ApiErrorWriter errors, ClientIpResolver clientIp,
                           IpAbuseTracker abuse) {
        this.limits = limits;
        this.settings = settings;
        this.errors = errors;
        this.clientIp = clientIp;
        this.abuse = abuse;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.isApi(request)
                || HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String ip = clientIp.resolve(request);
        String path = RequestPaths.normalized(request);
        if (isControlPlane(path)) {
            RateLimitDecision control = limits.evaluate("control:" + ip,
                    settings.integer("security.control-plane.rate-limit.capacity"),
                    settings.integer("security.control-plane.rate-limit.window-seconds"));
            if (!control.allowed()) {
                reject(response, control);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision = limits.evaluate("global:" + ip);
        String triggerType = "RATE_LIMIT_GLOBAL";
        String policy = "global";
        Optional<String> matchedPattern = new EndpointRateLimitPolicy(
                settings.string("security.endpoint.rate-limit.patterns")).matchingPattern(path);
        if (decision.allowed() && matchedPattern.isPresent()) {
            policy = matchedPattern.get();
            triggerType = "RATE_LIMIT_ENDPOINT";
            decision = limits.evaluate("endpoint:" + ip + ':' + policy,
                    settings.integer("security.endpoint.rate-limit.capacity"),
                    settings.integer("security.endpoint.rate-limit.window-seconds"));
        }
        if (!decision.allowed()) {
            abuse.recordRateLimitRejection(ip, triggerType, policy);
            reject(response, decision);
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, RateLimitDecision decision) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setHeader("X-RateLimit-Reset", Long.toString(decision.resetAt().getEpochSecond()));
        errors.write(response, ApiErrorCode.RATE_LIMITED);
    }

    private boolean isControlPlane(String path) {
        return RequestPaths.isUnder(path, "/api/v1/system/runtime-config")
                || RequestPaths.isUnder(path, "/api/v1/system/runtime-caches")
                || RequestPaths.isUnder(path, "/api/v1/system/runtime-checks")
                || RequestPaths.isUnder(path, "/api/v1/system/ip-access-rules");
    }
}
