package com.yumg.starter.modules.runtimeconfig.api.dto;

import com.yumg.starter.entities.RuntimeComponentStatus;
import java.time.Instant;

public record RuntimeComponentResponse(String code, String displayName, String status,
                                       Instant lastCheckedAt, Instant lastSucceededAt,
                                       Long latencyMs, int consecutiveFailures, String message) {
    public static RuntimeComponentResponse from(RuntimeComponentStatus status, String displayName) {
        return new RuntimeComponentResponse(status.getComponentCode(), displayName,
                status.getStatus().name(), status.getLastCheckedAt(), status.getLastSucceededAt(),
                status.getLatencyMs(), status.getConsecutiveFailures(), status.getMessage());
    }
}
