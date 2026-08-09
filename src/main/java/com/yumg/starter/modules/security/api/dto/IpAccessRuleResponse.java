package com.yumg.starter.modules.security.api.dto;

import com.yumg.starter.entities.IpAccessRule;
import java.time.Instant;

public record IpAccessRuleResponse(String id, String type, String source, String status,
                                   String network, String scope, Long expiresAt, String reason,
                                   String triggerType, long violationCount,
                                   Long firstTriggeredAt, Long lastTriggeredAt, Long blockedAt,
                                   Long revokedAt, Long createdAt, Long updatedAt) {
    public static IpAccessRuleResponse from(IpAccessRule rule) {
        Instant now = Instant.now();
        return new IpAccessRuleResponse(rule.getId(), rule.getType().name(), rule.getSource().name(),
                rule.statusAt(now), rule.getNetwork(), rule.getScope(), epoch(rule.getExpiresAt()),
                rule.getReason(), rule.getTriggerType(), rule.getViolationCount(),
                epoch(rule.getFirstTriggeredAt()), epoch(rule.getLastTriggeredAt()),
                epoch(rule.getBlockedAt()), epoch(rule.getRevokedAt()), epoch(rule.getCreatedAt()),
                epoch(rule.getUpdatedAt()));
    }
    private static Long epoch(Instant value) { return value == null ? null : value.toEpochMilli(); }
}
