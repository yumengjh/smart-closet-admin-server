package com.yumg.starter.modules.security.application;

import java.time.Instant;

public record RateLimitDecision(boolean allowed, int limit, int remaining,
                                Instant resetAt, long retryAfterSeconds) {
    public static RateLimitDecision allowed(int limit, int remaining, Instant resetAt) {
        return new RateLimitDecision(true, limit, remaining, resetAt, 0);
    }
    public static RateLimitDecision denied(int limit, Instant resetAt, long retryAfterSeconds) {
        return new RateLimitDecision(false, limit, 0, resetAt, Math.max(1, retryAfterSeconds));
    }
}
