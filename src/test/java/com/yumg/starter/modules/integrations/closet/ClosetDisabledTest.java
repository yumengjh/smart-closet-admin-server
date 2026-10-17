package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThat;
import com.yumg.starter.modules.integrations.closet.api.*;
import com.yumg.starter.modules.integrations.closet.application.*;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetIntegrationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ClosetDisabledTest {
    @Test void disabledIntegrationRegistersNoControllersServicesOrSeeders() {
        Class<?>[] gated = { ClosetUserAdministrationController.class, ClosetInsightController.class,
                ClosetManagementController.class, ClosetUserAdministrationService.class, ClosetInsightService.class,
                ClosetManagementService.class, ClosetPermissionBootstrap.class, ClosetNavigationBootstrap.class,
                ClosetIntegrationConfiguration.class };
        new ApplicationContextRunner().withUserConfiguration(gated).run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : gated) assertThat(context).doesNotHaveBean(type);
            assertThat(context).doesNotHaveBean(ClosetRuntimeComponent.class);
        });
    }
}
