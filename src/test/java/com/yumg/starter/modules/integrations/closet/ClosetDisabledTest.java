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
            "GET, /creations/trial-garments", "POST, /creations/trial-persons",
            "DELETE, /creations/trial-persons/11111111-2222-3333-4444-555555555555",
            "GET, /creations/trials/11111111-2222-3333-4444-555555555555/image",
            "GET, /creations/showcases", "POST, /creations/showcases", "POST, /creations/showcases/uploads",
            "PUT, /creations/showcases/asset-1", "POST, /creations/showcases/asset-1/publish",
            "POST, /creations/showcases/asset-1/retire", "GET, /creations/showcases/asset-1/image",
            "PATCH, /users/alice/profile", "DELETE, /users/alice/sessions/0123456789abcdef",
            "GET, /users/alice/login-lock", "DELETE, /users/alice/login-lock", "GET, /users/alice/events",
            "GET, /registration", "PUT, /registration", "GET, /invites", "POST, /invites",
            "GET, /invites/invite-12", "PATCH, /invites/invite-12", "DELETE, /invites/invite-12", "GET, /account-events"})
    void disabledAccountRoutesDoNotExistEvenWithAllTheirPermissions(String method, String path) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(request(HttpMethod.valueOf(method), "/api/v1/closet" + path)
                        .with(jwt().authorities(() -> "closet:showcase:read", () -> "closet:showcase:write", () -> "closet:showcase:publish",
                                () -> "closet:user:read", () -> "closet:user:write",
                                () -> "closet:registration:read", () -> "closet:registration:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test void disabledIntegrationRegistersNoControllersServicesOrSeeders() {
        Class<?>[] gated = { ClosetShowcaseController.class, ClosetShowcaseService.class,
                com.yumg.starter.modules.integrations.closet.infrastructure.ClosetShowcaseMultipartConfiguration.class, ClosetUserAdministrationController.class, ClosetInsightController.class,
                ClosetRegistrationController.class, ClosetRegistrationService.class, ClosetManagementController.class, ClosetUserAdministrationService.class, ClosetInsightService.class,
                ClosetManagementService.class, ClosetPermissionBootstrap.class, ClosetNavigationBootstrap.class,
                ClosetBillingController.class, ClosetBillingService.class, ClosetTryOnController.class, ClosetTryOnService.class, ClosetIntegrationConfiguration.class };
        new ApplicationContextRunner().withUserConfiguration(gated).run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> type : gated) assertThat(context).doesNotHaveBean(type);
            assertThat(context).doesNotHaveBean(ClosetRuntimeComponent.class);
        });
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("com.yumg.starter.modules.integrations.closet.ClosetIntegrationApiTest#tryOnOperations")
    void disabledTryOnRoutesDoNotExist(String method, String path, String permission, String operation, String body) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(request(HttpMethod.valueOf(method), "/api/v1/closet/tryon" + path)
                .with(jwt().authorities(() -> "closet:tryon:" + permission))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("com.yumg.starter.modules.integrations.closet.ClosetIntegrationApiTest#billingOperations")
    void disabledBillingRoutesDoNotExist(String method, String path, String query, String body, int expectedStatus) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(request(HttpMethod.valueOf(method), "/api/v1/closet" + path + query)
                .with(jwt().authorities(() -> "closet:billing:read", () -> "closet:billing:write"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

}
