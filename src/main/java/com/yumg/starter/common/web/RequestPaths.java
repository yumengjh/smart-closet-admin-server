package com.yumg.starter.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * Single source of the request path used by security filters, CORS routing and request logs.
 *
 * <p>Spring MVC matches handlers against the percent-decoded path within the application, so a
 * request such as {@code /%61pi/v1/auth/login} reaches the login controller. Filters must decide on
 * the same decoded path; comparing {@link HttpServletRequest#getRequestURI()} (raw, still encoded)
 * lets equivalent encodings skip IP rules, rate limits and endpoint policies. Spring Security's
 * {@code StrictHttpFirewall} has already rejected encoded slashes, dot segments and semicolons
 * before these filters run, so decoding here cannot introduce new path segments.
 */
public final class RequestPaths {
    private static final UrlPathHelper DECODED = UrlPathHelper.defaultInstance;

    private RequestPaths() {
    }

    /** Percent-decoded path within the application, with semicolon content and duplicate slashes removed. */
    public static String normalized(HttpServletRequest request) {
        return DECODED.getPathWithinApplication(request);
    }

    /** Whether the normalized request path is {@code prefix} itself or lies below it. */
    public static boolean isUnder(String normalizedPath, String prefix) {
        return normalizedPath.equals(prefix) || normalizedPath.startsWith(prefix + "/");
    }

    public static boolean isApi(HttpServletRequest request) {
        return isUnder(normalized(request), "/api");
    }
}
