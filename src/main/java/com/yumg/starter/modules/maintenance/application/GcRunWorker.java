package com.yumg.starter.modules.maintenance.application;

import com.yumg.starter.common.web.TraceIdFilter;
import com.yumg.starter.entities.MaintenanceGcPolicy;
import com.yumg.starter.entities.MaintenanceGcRun;
import com.yumg.starter.modules.maintenance.infrastructure.MaintenanceGcLockRepository;
import com.yumg.starter.modules.maintenance.infrastructure.MaintenanceGcRunRepository;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import com.yumg.starter.modules.security.application.AuditService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GcRunWorker {
    private static final String LOCK_NAME = "global";
    private final GcPolicyService policies;
    private final MaintenanceGcRunRepository runs;
    private final MaintenanceGcLockRepository locks;
    private final RuntimeSettingService settings;
    private final AuditService audit;
    private final GcRunSummaryCodec summaries;

    public GcRunWorker(GcPolicyService policies, MaintenanceGcRunRepository runs,
                       MaintenanceGcLockRepository locks, RuntimeSettingService settings,
                       AuditService audit, GcRunSummaryCodec summaries) {
        this.policies = policies;
        this.runs = runs;
        this.locks = locks;
        this.settings = settings;
        this.audit = audit;
        this.summaries = summaries;
    }

    @Transactional
    public MaintenanceGcRun create(String triggerType, boolean dryRun, String requestedBy,
                                   Set<String> selected) {
        return runs.save(new MaintenanceGcRun(triggerType, dryRun, requestedBy,
                String.join(",", selected), MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY)));
    }

    @Transactional
    public GcRunContent execute(String runId, String triggerType, boolean dryRun,
                                Set<String> selected) {
        MaintenanceGcRun run = runs.findById(runId).orElseThrow();
        Instant now = Instant.now();
        if (locks.tryAcquire(LOCK_NAME, runId, now,
                now.plusSeconds(settings.integer("maintenance.gc.max-run-seconds"))) != 1) {
            run.skip("已有 GC 任务正在运行");
            return GcRunContent.from(run, summaries);
        }
        try {
            List<GcResourceResult> results = new ArrayList<>();
            for (String code : selected) {
                MaintenanceGcPolicy policy = policies.policy(code);
                if (!policy.isEnabled()
                        || ("SCHEDULED".equals(triggerType) && !policy.isAutomaticEnabled())) {
                    continue;
                }
                results.add(policies.resources().get(code).execute(policy, dryRun, now));
            }
            run.complete(summaries.write(GcRunSummary.from(results)));
            audit.event("GC_RUN_COMPLETED", "MaintenanceGcRun", runId, "SUCCESS",
                    "{\"dryRun\":" + dryRun + "}");
            return GcRunContent.from(run, summaries);
        } finally {
            locks.release(LOCK_NAME, runId, Instant.now());
        }
    }

    @Transactional
    public void fail(String runId, RuntimeException exception) {
        MaintenanceGcRun run = runs.findById(runId).orElseThrow();
        run.fail(exception.getMessage());
    }

    @Transactional
    public void auditFailure(String runId) {
        audit.event("GC_RUN_FAILED", "MaintenanceGcRun", runId, "FAILURE", null);
    }
}
