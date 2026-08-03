package com.yumg.starter.entities;

import com.yumg.starter.common.entity.AuditedEntity;
import com.yumg.starter.common.entity.InstantStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "runtime_component_statuses")
public class RuntimeComponentStatus extends AuditedEntity {
    @Column(name = "component_code", nullable = false, unique = true, length = 100)
    private String componentCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RuntimeComponentState status;
    @Column(name = "last_checked_at") @Convert(converter = InstantStringConverter.class)
    private Instant lastCheckedAt;
    @Column(name = "last_succeeded_at") @Convert(converter = InstantStringConverter.class)
    private Instant lastSucceededAt;
    @Column(name = "latency_ms")
    private Long latencyMs;
    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;
    @Column(length = 500)
    private String message;

    protected RuntimeComponentStatus() {}

    public RuntimeComponentStatus(String componentCode) {
        ensureId();
        this.componentCode = componentCode;
        this.status = RuntimeComponentState.UNKNOWN;
    }

    public void checking(Instant now) {
        status = RuntimeComponentState.CHECKING;
        lastCheckedAt = now;
        message = "checking";
    }

    public void up(Instant now, long latencyMs, String message) {
        status = RuntimeComponentState.UP;
        lastCheckedAt = now;
        lastSucceededAt = now;
        this.latencyMs = latencyMs;
        consecutiveFailures = 0;
        this.message = message;
    }

    public void error(Instant now, long latencyMs, String message) {
        status = RuntimeComponentState.ERROR;
        lastCheckedAt = now;
        this.latencyMs = latencyMs;
        consecutiveFailures++;
        this.message = message;
    }

    public void disabled(Instant now) {
        status = RuntimeComponentState.DISABLED;
        lastCheckedAt = now;
        latencyMs = 0L;
        message = "disabled by runtime setting";
    }

    public String getComponentCode() { return componentCode; }
    public RuntimeComponentState getStatus() { return status; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public Instant getLastSucceededAt() { return lastSucceededAt; }
    public Long getLatencyMs() { return latencyMs; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public String getMessage() { return message; }
}
