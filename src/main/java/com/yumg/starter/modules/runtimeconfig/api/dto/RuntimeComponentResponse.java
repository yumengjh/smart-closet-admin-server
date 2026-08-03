package com.yumg.starter.modules.runtimeconfig.api.dto;

import com.yumg.starter.entities.RuntimeComponentStatus;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponent;
import java.time.Instant;

public record RuntimeComponentResponse(String code, String displayName, String kind, String status,
                                       Instant lastCheckedAt, Instant lastSucceededAt,
                                       Long latencyMs, int consecutiveFailures, String message) {
    public static RuntimeComponentResponse from(RuntimeComponentStatus status, RuntimeComponent component) {
        return new RuntimeComponentResponse(status.getComponentCode(), component.displayName(),
                component.kind().name(),
                status.getStatus().name(), status.getLastCheckedAt(), status.getLastSucceededAt(),
                status.getLatencyMs(), status.getConsecutiveFailures(), status.getMessage());
    }
}
