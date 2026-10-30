package com.yumg.starter.modules.runtimeconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.RuntimeSetting;
import com.yumg.starter.modules.runtimeconfig.infrastructure.RuntimeSettingRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeSettingServiceTest {
    @Test
    void returnsReadableMetadataForBuiltInRuntimeSettings() {
        RuntimeSettingRepository repository = Mockito.mock(RuntimeSettingRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new RuntimeSetting("identity.registration.enabled", "BOOLEAN", "true")));

        var response = new RuntimeSettingService(repository).list().getFirst();

        assertThat(response.displayName()).isEqualTo("开放自助注册");
        assertThat(response.description()).isEqualTo("控制访客是否可以自行注册新账号。");
        assertThat(response.group()).isEqualTo("identity");
        assertThat(response.dangerous()).isFalse();
    }

    @Test
    void rejectsWildcardCorsAtRuntimeInThePostgresProfile() {
        RuntimeSettingRepository repository = Mockito.mock(RuntimeSettingRepository.class);
        RuntimeSetting setting = new RuntimeSetting(
                "security.cors.allowed-origins", "STRING", "https://admin.example.com");
        when(repository.findByKey("security.cors.allowed-origins"))
                .thenReturn(Optional.of(setting));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("postgres");
        RuntimeSettingService service = new RuntimeSettingService(
                repository, null, "", null, environment);

        assertThatThrownBy(() -> service.update("security.cors.allowed-origins", "*"))
                .hasMessage("Request parameter is invalid");
    }

    @Test
    void publishesASettingToTheSnapshotOnlyAfterCommit() {
        RuntimeSettingRepository repository = Mockito.mock(RuntimeSettingRepository.class);
        RuntimeSetting setting = new RuntimeSetting(
                "maintenance.gc.interval-minutes", "INTEGER", "60");
        when(repository.findByKey("maintenance.gc.interval-minutes"))
                .thenReturn(Optional.of(setting));
        RuntimeSettingService service = new RuntimeSettingService(repository);
        service.update("maintenance.gc.interval-minutes", "60");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update("maintenance.gc.interval-minutes", "120");

            assertThat(service.integer("maintenance.gc.interval-minutes")).isEqualTo(60);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(service.integer("maintenance.gc.interval-minutes")).isEqualTo(120);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void keepsThePreviousSnapshotWhenABatchUpdateRollsBack() {
        RuntimeSettingRepository repository = Mockito.mock(RuntimeSettingRepository.class);
        RuntimeSetting enabled = new RuntimeSetting(
                "maintenance.gc.schedule.enabled", "BOOLEAN", "true");
        RuntimeSetting interval = new RuntimeSetting(
                "maintenance.gc.interval-minutes", "INTEGER", "60");
        when(repository.findByKey("maintenance.gc.schedule.enabled"))
                .thenReturn(Optional.of(enabled));
        when(repository.findByKey("maintenance.gc.interval-minutes"))
                .thenReturn(Optional.of(interval));
        RuntimeSettingService service = new RuntimeSettingService(repository);
        service.update("maintenance.gc.schedule.enabled", "true");
        service.update("maintenance.gc.interval-minutes", "60");

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.updateAll(Map.of(
                    "maintenance.gc.schedule.enabled", "false",
                    "maintenance.gc.interval-minutes", "120"));

            assertThat(service.enabled("maintenance.gc.schedule.enabled")).isTrue();
            assertThat(service.integer("maintenance.gc.interval-minutes")).isEqualTo(60);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(
                            TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(service.enabled("maintenance.gc.schedule.enabled")).isTrue();
            assertThat(service.integer("maintenance.gc.interval-minutes")).isEqualTo(60);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void atomicallyReloadsSupportedSettingsFromTheDatabase() {
        RuntimeSettingRepository repository = Mockito.mock(RuntimeSettingRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new RuntimeSetting("runtime.health-check.enabled", "BOOLEAN", "true"),
                new RuntimeSetting("runtime.health-check.interval-seconds", "INTEGER", "90"),
                new RuntimeSetting("avatar.generator.enabled", "BOOLEAN", "false")));
        RuntimeSettingService service = new RuntimeSettingService(repository);

        service.reload();

        assertThat(service.enabled("runtime.health-check.enabled")).isTrue();
        assertThat(service.integer("runtime.health-check.interval-seconds")).isEqualTo(90);
        assertThat(service.enabled("avatar.generator.enabled")).isFalse();
    }

    @Test
    void outOfOrderCommitCallbacksStillPublishEveryCommittedSetting() {
        RuntimeSettingRepository repository = Mockito.mock(RuntimeSettingRepository.class);
        RuntimeSetting registration = new RuntimeSetting("identity.registration.enabled", "BOOLEAN", "true");
        RuntimeSetting disabled = new RuntimeSetting("security.endpoint.disabled-patterns", "STRING", "");
        when(repository.findByKey(registration.getKey())).thenReturn(Optional.of(registration));
        when(repository.findByKey(disabled.getKey())).thenReturn(Optional.of(disabled));
        // The mocked "database" reflects every committed change, like a fresh query would.
        when(repository.findAll()).thenReturn(List.of(registration, disabled));
        RuntimeSettingService service = new RuntimeSettingService(repository);
        service.reload();

        List<TransactionSynchronization> first = capture(() ->
                service.update("identity.registration.enabled", "false"));
        List<TransactionSynchronization> second = capture(() ->
                service.update("security.endpoint.disabled-patterns", "/api/v1/auth/register"));
        // T1 committed first, but T2's callback runs first.
        second.forEach(TransactionSynchronization::afterCommit);
        first.forEach(TransactionSynchronization::afterCommit);

        assertThat(service.enabled("identity.registration.enabled")).isFalse();
        assertThat(service.string("security.endpoint.disabled-patterns")).isEqualTo("/api/v1/auth/register");
        assertThat(service.status().snapshotRevision()).isEqualTo(service.status().databaseRevision());
    }

    private static List<TransactionSynchronization> capture(Runnable transaction) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            transaction.run();
            return List.copyOf(TransactionSynchronizationManager.getSynchronizations());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
