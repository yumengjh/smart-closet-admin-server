package com.yumg.starter.modules.runtimeconfig.application;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RuntimeCacheSchedulerTest {
    @Test
    void stillChecksTheSettingsRevisionWhileAutomaticReconciliationIsDisabled() {
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        RuntimeCacheRegistry caches = Mockito.mock(RuntimeCacheRegistry.class);
        when(settings.enabled("runtime.config-refresh.enabled")).thenReturn(false);
        RuntimeCacheScheduler scheduler = new RuntimeCacheScheduler(settings, caches);

        scheduler.refreshWhenDue();

        verify(settings).refreshIfChanged();
        verify(caches, never()).refreshChanged();
    }
}
