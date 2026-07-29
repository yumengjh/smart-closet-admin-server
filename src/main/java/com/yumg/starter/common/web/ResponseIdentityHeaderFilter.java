package com.yumg.starter.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

public final class ResponseIdentityHeaderFilter extends OncePerRequestFilter
        implements Ordered {
    public static final String POWERED_BY_HEADER_NAME = "Powered-By";
    public static final String SERVER_HEADER_NAME = "Server";

    private final String poweredBy;
    private final String server;

    public ResponseIdentityHeaderFilter(String poweredBy, String server) {
        this.poweredBy = poweredBy;
        this.server = server;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        applyIdentityHeaders(response);
        filterChain.doFilter(request, new IdentityResponseWrapper(response));
    }

    private void applyIdentityHeaders(HttpServletResponse response) {
        response.setHeader(POWERED_BY_HEADER_NAME, poweredBy);
        response.setHeader(SERVER_HEADER_NAME, server);
    }

    private final class IdentityResponseWrapper extends HttpServletResponseWrapper {
        private IdentityResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            super.setHeader(name, identityValue(name, value));
        }

        @Override
        public void addHeader(String name, String value) {
            if (isIdentityHeader(name)) {
                super.setHeader(name, identityValue(name, value));
                return;
            }
            super.addHeader(name, value);
        }

        @Override
        public void reset() {
            super.reset();
            applyIdentityHeaders(this);
        }

        private boolean isIdentityHeader(String name) {
            return POWERED_BY_HEADER_NAME.equalsIgnoreCase(name)
                    || SERVER_HEADER_NAME.equalsIgnoreCase(name);
        }

        private String identityValue(String name, String fallback) {
            if (POWERED_BY_HEADER_NAME.equalsIgnoreCase(name)) {
                return poweredBy;
            }
            if (SERVER_HEADER_NAME.equalsIgnoreCase(name)) {
                return server;
            }
            return fallback;
        }
    }
}
