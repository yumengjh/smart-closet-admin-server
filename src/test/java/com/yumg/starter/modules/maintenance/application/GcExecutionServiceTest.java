package com.yumg.starter.modules.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.MaintenanceGcRun;
import com.yumg.starter.modules.maintenance.infrastructure.MaintenanceGcRunRepository;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GcExecutionServiceTest {
    @Test
    void dryRunPreviewsSelectedResourceWithoutMutatingIt() {
        GcPolicyService policies = Mockito.mock(GcPolicyService.class);
        MaintenanceGcRunRepository runs = Mockito.mock(MaintenanceGcRunRepository.class);
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        GcRunWorker worker = Mockito.mock(GcRunWorker.class);
        GcResource resource = Mockito.mock(GcResource.class);
        when(policies.resources()).thenReturn(Map.of("example", resource));
        when(settings.enabled("maintenance.gc.enabled")).thenReturn(true);
        MaintenanceGcRun run = Mockito.mock(MaintenanceGcRun.class);
        when(run.getId()).thenReturn("run-id");
        when(worker.create("MANUAL", true, "admin", Set.of("example"))).thenReturn(run);
        GcRunContent expected = new GcRunContent("run-id", "SUCCEEDED", "MANUAL", true,
                "admin", "example", GcRunSummary.from(List.of(
                new GcResourceResult("example", 3, 0, "预览"))), null, null, null, null);
        when(worker.execute("run-id", "MANUAL", true, Set.of("example"))).thenReturn(expected);

        GcExecutionService service = new GcExecutionService(policies, runs, settings,
                new GcRunSummaryCodec(new tools.jackson.databind.ObjectMapper()), worker);

        GcRunContent result = service.run("MANUAL", true, List.of("example"), "admin");

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.summary()).isNotNull();
        assertThat(result.summary().totalCandidates()).isEqualTo(3);
        assertThat(result.summary().totalDeleted()).isZero();
        assertThat(result.summary().resources()).singleElement().satisfies(item -> {
            assertThat(item.resourceCode()).isEqualTo("example");
            assertThat(item.candidates()).isEqualTo(3);
            assertThat(item.deleted()).isZero();
            assertThat(item.message()).isEqualTo("预览");
        });
        verify(worker).execute("run-id", "MANUAL", true, Set.of("example"));
    }

    @Test
    void marksTheCommittedRunAsFailedAfterTheExecutionTransactionRollsBack() {
        GcPolicyService policies = Mockito.mock(GcPolicyService.class);
        MaintenanceGcRunRepository runs = Mockito.mock(MaintenanceGcRunRepository.class);
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        GcRunWorker worker = Mockito.mock(GcRunWorker.class);
        GcResource resource = Mockito.mock(GcResource.class);
        when(policies.resources()).thenReturn(Map.of("example", resource));
        when(settings.enabled("maintenance.gc.enabled")).thenReturn(true);
        MaintenanceGcRun run = Mockito.mock(MaintenanceGcRun.class);
        when(run.getId()).thenReturn("run-id");
        when(worker.create("MANUAL", false, "admin", Set.of("example"))).thenReturn(run);
        IllegalStateException failure = new IllegalStateException("database unavailable");
        when(worker.execute("run-id", "MANUAL", false, Set.of("example"))).thenThrow(failure);
        GcExecutionService service = new GcExecutionService(policies, runs, settings,
                new GcRunSummaryCodec(new tools.jackson.databind.ObjectMapper()), worker);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.run("MANUAL", false, List.of("example"), "admin"))
                .isSameAs(failure);

        verify(worker).fail("run-id", failure);
        verify(worker).auditFailure("run-id");
    }
}
