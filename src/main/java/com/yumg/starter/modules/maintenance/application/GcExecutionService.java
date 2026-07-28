package com.yumg.starter.modules.maintenance.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.MaintenanceGcRun;
import com.yumg.starter.modules.maintenance.infrastructure.MaintenanceGcRunRepository;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.yumg.starter.common.api.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GcExecutionService {
    private final GcPolicyService policies; private final MaintenanceGcRunRepository runs;
    private final RuntimeSettingService settings;
    private final GcRunSummaryCodec summaries;
    private final GcRunWorker worker;
    public GcExecutionService(GcPolicyService policies, MaintenanceGcRunRepository runs,
                              RuntimeSettingService settings, GcRunSummaryCodec summaries,
                              GcRunWorker worker) {
        this.policies = policies;
        this.runs = runs;
        this.settings = settings;
        this.summaries = summaries;
        this.worker = worker;
    }
    public GcRunContent run(String triggerType, boolean dryRun, List<String> requestedResources, String requestedBy) {
        if (!settings.enabled("maintenance.gc.enabled")) throw ApiException.notFound();
        Map<String, GcResource> resources = policies.resources();
        Set<String> selected = requestedResources == null || requestedResources.isEmpty() ? resources.keySet() : Set.copyOf(requestedResources);
        if (!resources.keySet().containsAll(selected)) throw ApiException.invalidParameter();
        MaintenanceGcRun run = worker.create(triggerType, dryRun, requestedBy, selected);
        try {
            return worker.execute(run.getId(), triggerType, dryRun, selected);
        } catch (RuntimeException exception) {
            try {
                worker.fail(run.getId(), exception);
                try {
                    worker.auditFailure(run.getId());
                } catch (RuntimeException auditFailure) {
                    exception.addSuppressed(auditFailure);
                }
            } catch (RuntimeException persistenceFailure) {
                exception.addSuppressed(persistenceFailure);
            }
            throw exception;
        }
    }
    @Transactional(readOnly = true) public PageResponse<GcRunContent> history(int page, int size) {
        return PageResponse.from(runs.findAll(org.springframework.data.domain.PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by("startedAt").descending())).map(run -> GcRunContent.from(run, summaries)));
    }
}
