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
@Table(name = "ip_access_rules")
public class IpAccessRule extends AuditedEntity {
    public enum Type { ALLOW, DENY }
    public enum Source { MANUAL, AUTO_RATE_LIMIT, AUTO_LOGIN_ABUSE, MIGRATED }
    @Enumerated(EnumType.STRING) @Column(name = "rule_type", nullable = false, length = 32) private Type type;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private Source source = Source.MANUAL;
    @Column(nullable = false, length = 64) private String network;
    @Column(nullable = false, length = 64) private String scope;
    @Column(name = "expires_at") @Convert(converter = InstantStringConverter.class) private Instant expiresAt;
    @Column(length = 500) private String reason;
    @Column(name = "trigger_type", length = 64) private String triggerType;
    @Column(name = "violation_count", nullable = false) private long violationCount;
    @Column(name = "first_triggered_at") @Convert(converter = InstantStringConverter.class) private Instant firstTriggeredAt;
    @Column(name = "last_triggered_at") @Convert(converter = InstantStringConverter.class) private Instant lastTriggeredAt;
    @Column(name = "blocked_at") @Convert(converter = InstantStringConverter.class) private Instant blockedAt;
    @Column(name = "revoked_at") @Convert(converter = InstantStringConverter.class) private Instant revokedAt;
    protected IpAccessRule() { }
    public IpAccessRule(Type type, String network, String scope, Instant expiresAt, String reason) {
        this(type, Source.MANUAL, network, scope, expiresAt, reason);
    }
    public IpAccessRule(Type type, Source source, String network, String scope, Instant expiresAt, String reason) {
        this.type=type; this.source=source; this.network=network; this.scope=scope;
        this.expiresAt=expiresAt; this.reason=reason;
        if (type == Type.DENY) this.blockedAt = Instant.now();
    }
    public static IpAccessRule automaticDeny(String network, String scope, Instant expiresAt,
                                             String reason, String triggerType, long violations,
                                             Instant firstTriggeredAt, Instant lastTriggeredAt) {
        IpAccessRule rule = new IpAccessRule(Type.DENY, Source.AUTO_RATE_LIMIT,
                network, scope, expiresAt, reason);
        rule.triggerType = triggerType;
        rule.violationCount = violations;
        rule.firstTriggeredAt = firstTriggeredAt;
        rule.lastTriggeredAt = lastTriggeredAt;
        rule.blockedAt = lastTriggeredAt;
        return rule;
    }
    public void recordAutomaticTrigger(long violations, Instant first, Instant last,
                                       Instant newExpiry, String reason, String triggerType) {
        violationCount = Math.max(violationCount, violations);
        if (firstTriggeredAt == null || first.isBefore(firstTriggeredAt)) firstTriggeredAt = first;
        lastTriggeredAt = last;
        if (expiresAt == null || newExpiry.isAfter(expiresAt)) expiresAt = newExpiry;
        this.reason = reason;
        this.triggerType = triggerType;
        revokedAt = null;
    }
    public void revoke(Instant now) { revokedAt = now; }
    public Type getType(){return type;} public Source getSource(){return source;}
    public String getNetwork(){return network;} public String getScope(){return scope;}
    public Instant getExpiresAt(){return expiresAt;} public String getReason(){return reason;}
    public String getTriggerType(){return triggerType;} public long getViolationCount(){return violationCount;}
    public Instant getFirstTriggeredAt(){return firstTriggeredAt;} public Instant getLastTriggeredAt(){return lastTriggeredAt;}
    public Instant getBlockedAt(){return blockedAt;} public Instant getRevokedAt(){return revokedAt;}
    public boolean activeAt(Instant now){return revokedAt==null&&(expiresAt==null||expiresAt.isAfter(now));}
    public String statusAt(Instant now){return revokedAt!=null?"REVOKED":expiresAt!=null&&!expiresAt.isAfter(now)?"EXPIRED":"ACTIVE";}
}
