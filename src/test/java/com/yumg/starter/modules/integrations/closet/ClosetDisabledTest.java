package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThat;
import com.yumg.starter.modules.integrations.closet.api.*;
import com.yumg.starter.modules.integrations.closet.application.*;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetIntegrationConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:target/closet-disabled-test.db",
        "app.integrations.closet.enabled=false",
        "app.runtime.component-startup-probe.enabled=false"
})
class ClosetDisabledTest {
    @Autowired WebApplicationContext context;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "PATCH, /users/alice/profile", "DELETE, /users/alice/sessions/0123456789abcdef",
            "GET, /users/alice/login-lock", "DELETE, /users/alice/login-lock", "GET, /users/alice/events",
            "GET, /registration", "PUT, /registration", "GET, /invites", "POST, /invites",
            "GET, /invites/invite-12", "PATCH, /invites/invite-12", "DELETE, /invites/invite-12", "GET, /account-events"})
    void disabledAccountRoutesDoNotExistEvenWithAllTheirPermissions(String method, String path) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(request(HttpMethod.valueOf(method), "/api/v1/closet" + path)
                        .with(jwt().authorities(() -> "closet:user:read", () -> "closet:user:write",
                                () -> "closet:registration:read", () -> "closet:registration:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test void disabledIntegrationRegistersNoControllersServicesOrSeeders() {
        Class<?>[] gated = { ClosetUserAdministrationController.class, ClosetInsightController.class,
                ClosetRegistrationController.class, ClosetRegistrationService.class, ClosetManagementController.class, ClosetUserAdministrationService.class, ClosetInsightService.class,
                ClosetManagementService.class, ClosetPermissionBootstrap.class, ClosetNavigationBootstrap.class,
                ClosetIntegrationConfiguration.class };
        new ApplicationContextRunner().withUserConfiguration(gated).run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : gated) assertThat(context).doesNotHaveBean(type);
            assertThat(context).doesNotHaveBean(ClosetRuntimeComponent.class);
        });
    }
}
