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

class RuntimeSettingServiceTest {
    @Test
    void returnsReadableMetadataForBuiltInRuntimeSettings() {
        RuntimeSettingRepository repository = Mockito.mock(RuntimeSettingRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                new RuntimeSetting("identity.registration.enabled", "BOOLEAN", "true")));

        var response = new RuntimeSettingService(repository).list().getFirst();

        assertThat(response.displayName()).isEqualTo("开放自助注册");
        assertThat(response.description()).isEqualTo("控制访客是否可以自行注册新账号。");
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
                new RuntimeSetting("runtime.dependency-health.enabled", "BOOLEAN", "true"),
                new RuntimeSetting("runtime.dependency-health.interval-seconds", "INTEGER", "90"),
                new RuntimeSetting("avatar.generator.enabled", "BOOLEAN", "false")));
        RuntimeSettingService service = new RuntimeSettingService(repository);

        service.reload();

        assertThat(service.enabled("runtime.dependency-health.enabled")).isTrue();
        assertThat(service.integer("runtime.dependency-health.interval-seconds")).isEqualTo(90);
        assertThat(service.enabled("avatar.generator.enabled")).isFalse();
    }
}
