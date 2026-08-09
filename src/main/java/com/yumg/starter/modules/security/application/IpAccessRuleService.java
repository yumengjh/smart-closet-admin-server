package com.yumg.starter.modules.security.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.IpAccessRule;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCacheProvider;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCacheRevisionService;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCacheStatus;
import com.yumg.starter.modules.security.api.dto.IpAccessRuleRequest;
import com.yumg.starter.modules.security.api.dto.IpAccessRuleResponse;
import com.yumg.starter.modules.security.infrastructure.IpAccessRuleRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class IpAccessRuleService implements RuntimeCacheProvider {
    public static final String CACHE_CODE = "IP_ACCESS_RULES";
    private static final String API_SCOPE = "API";

    private final IpAccessRuleRepository rules;
    private final AuditService audit;
    private final RuntimeCacheRevisionService revisions;
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile long snapshotRevision;
    private volatile long databaseRevision;
    private volatile Instant loadedAt;
    private volatile Instant lastCheckedAt;
    private volatile Instant lastReloadedAt;
    private volatile Instant lastFailedAt;
    private volatile String lastFailureMessage;
    private long localRevision;

    public enum Decision { ALLOW, DENY }

    public IpAccessRuleService(IpAccessRuleRepository rules, AuditService audit) {
        this(rules, audit, null);
    }

    @Autowired
    public IpAccessRuleService(IpAccessRuleRepository rules, AuditService audit,
                               RuntimeCacheRevisionService revisions) {
        this.rules = rules;
        this.audit = audit;
        this.revisions = revisions;
    }

    @PostConstruct
    void initialize() { refreshSnapshot(ensureRevision()); }

    void refreshSnapshot() { refreshSnapshot(currentRevision()); }

    @Transactional(readOnly = true)
    public List<IpAccessRuleResponse> list() {
        return rules.findAllByScope(API_SCOPE).stream()
                .sorted(Comparator.comparing(IpAccessRule::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(IpAccessRuleResponse::from)
                .toList();
    }

    @Transactional
    public IpAccessRuleResponse create(IpAccessRuleRequest request) {
        String network = IpNetworkMatcher.normalize(request.network())
                .orElseThrow(ApiException::invalidParameter);
        Instant expiresAt = request.expiresAt() == null
                ? null : Instant.ofEpochMilli(request.expiresAt());
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw ApiException.invalidParameter();
        }
        IpAccessRule rule = rules.save(new IpAccessRule(request.type(), network, API_SCOPE,
                expiresAt, request.reason().trim()));
        publishRuleChange("IP_ACCESS_RULE_CREATED", rule);
        return IpAccessRuleResponse.from(rule);
    }

    @Transactional
    public void revoke(String id) {
        IpAccessRule rule = rules.findById(id).orElseThrow(ApiException::notFound);
        rule.revoke(Instant.now());
        publishRuleChange("IP_ACCESS_RULE_REVOKED", rule);
    }

    @Transactional
    public IpAccessRuleResponse blockAutomatically(String ip, String triggerType, String reason,
                                                   long violations, Instant firstTriggeredAt,
                                                   Instant lastTriggeredAt, Instant expiresAt) {
        String normalized = IpNetworkMatcher.normalize(ip)
                .orElseThrow(ApiException::invalidParameter);
        IpAccessRule rule = rules.findAllByScope(API_SCOPE).stream()
                .filter(current -> current.getType() == IpAccessRule.Type.DENY)
                .filter(current -> current.getSource() == IpAccessRule.Source.AUTO_RATE_LIMIT)
                .filter(current -> current.getNetwork().equals(normalized))
                .filter(current -> current.activeAt(lastTriggeredAt))
                .findFirst()
                .orElseGet(() -> IpAccessRule.automaticDeny(normalized, API_SCOPE, expiresAt,
                        reason, triggerType, violations, firstTriggeredAt, lastTriggeredAt));
        rule.recordAutomaticTrigger(violations, firstTriggeredAt, lastTriggeredAt, expiresAt,
                reason, triggerType);
        rule = rules.save(rule);
        publishRuleChange("IP_AUTO_BLOCKED", rule);
        return IpAccessRuleResponse.from(rule);
    }

    public Decision decision(String ip, Instant now) { return snapshot.decision(ip, now); }

    @Override public String code() { return CACHE_CODE; }
    @Override public String displayName() { return "IP 访问规则"; }
    @Override public int order() { return 100; }
    @Override public RuntimeCacheStatus status() {
        return new RuntimeCacheStatus(code(), displayName(), snapshotRevision, databaseRevision,
                snapshotRevision == databaseRevision, loadedAt, lastCheckedAt, lastReloadedAt,
                lastFailedAt, lastFailureMessage, snapshot.ruleCount());
    }

    @Override
    @Transactional(readOnly = true)
    public synchronized RuntimeCacheStatus forceRefresh() {
        lastCheckedAt = Instant.now();
        try {
            refreshSnapshot(currentRevision());
            return status();
        } catch (RuntimeException exception) {
            lastFailedAt = Instant.now();
            lastFailureMessage = failureMessage(exception);
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean refreshIfChanged() {
        lastCheckedAt = Instant.now();
        long current = currentRevision();
        databaseRevision = current;
        if (current == snapshotRevision) return false;
        forceRefresh();
        return true;
    }

    private void publishRuleChange(String action, IpAccessRule rule) {
        long revision = incrementRevision();
        refreshSnapshotAfterCommit(revision);
        audit.event(action, "IpAccessRule", rule.getId(), "SUCCESS",
                "{\"source\":\"" + rule.getSource().name() + "\",\"network\":\""
                        + rule.getNetwork() + "\"}");
    }

    private record Rule(IpAccessRule.Type type, IpAccessRule.Source source, String network,
                        Instant expiresAt, Instant revokedAt) {
        boolean activeAt(Instant now) {
            return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
        }
        boolean matches(String ip, Instant now) {
            return activeAt(now) && IpNetworkMatcher.matches(network, ip);
        }
    }

    private record Snapshot(List<Rule> allow, List<Rule> manualDeny, List<Rule> automaticDeny) {
        static Snapshot empty() { return new Snapshot(List.of(), List.of(), List.of()); }
        static Snapshot from(List<IpAccessRule> values) {
            List<Rule> all = values.stream().map(rule -> new Rule(rule.getType(), rule.getSource(),
                    rule.getNetwork(), rule.getExpiresAt(), rule.getRevokedAt())).toList();
            return new Snapshot(
                    all.stream().filter(rule -> rule.type() == IpAccessRule.Type.ALLOW).toList(),
                    all.stream().filter(rule -> rule.type() == IpAccessRule.Type.DENY)
                            .filter(rule -> rule.source() == IpAccessRule.Source.MANUAL
                                    || rule.source() == IpAccessRule.Source.MIGRATED).toList(),
                    all.stream().filter(rule -> rule.type() == IpAccessRule.Type.DENY)
                            .filter(rule -> rule.source() != IpAccessRule.Source.MANUAL
                                    && rule.source() != IpAccessRule.Source.MIGRATED).toList());
        }
        Decision decision(String ip, Instant now) {
            if (manualDeny.stream().anyMatch(rule -> rule.matches(ip, now))) return Decision.DENY;
            boolean explicitlyAllowed = allow.stream().anyMatch(rule -> rule.matches(ip, now));
            if (explicitlyAllowed) return Decision.ALLOW;
            if (automaticDeny.stream().anyMatch(rule -> rule.matches(ip, now))) return Decision.DENY;
            boolean allowMode = allow.stream().anyMatch(rule -> rule.activeAt(now));
            return allowMode ? Decision.DENY : Decision.ALLOW;
        }
        int ruleCount() { return allow.size() + manualDeny.size() + automaticDeny.size(); }
    }

    private void refreshSnapshotAfterCommit(long revision) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refreshSnapshot(revision);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { refreshSnapshot(revision); }
        });
    }

    private synchronized void refreshSnapshot(long revision) {
        if (revision < snapshotRevision) return;
        snapshot = Snapshot.from(rules.findAllByScope(API_SCOPE));
        Instant now = Instant.now();
        snapshotRevision = revision;
        databaseRevision = revision;
        loadedAt = loadedAt == null ? now : loadedAt;
        lastReloadedAt = now;
        lastFailureMessage = null;
    }

    private long ensureRevision() { return revisions == null ? localRevision : revisions.ensure(CACHE_CODE); }
    private long currentRevision() { return revisions == null ? localRevision : revisions.current(CACHE_CODE); }
    private long incrementRevision() { return revisions == null ? ++localRevision : revisions.increment(CACHE_CODE); }
    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
