package com.yumg.starter;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yumg.starter.common.web.ResponseIdentityHeaderFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.navigation.infrastructure.NavigationMenuRepository;
import com.yumg.starter.modules.rbac.infrastructure.PermissionRepository;
import org.springframework.beans.factory.ObjectProvider;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:target/starter-application-test.db",
        "app.sql-console.enabled=false",
        // Pin the default explicitly so a developer's local .env cannot enable the integration here.
        "app.integrations.closet.enabled=false"
})
class StarterApplicationTest {
    static {
        // The SQLite file outlives runs; drop it so seeds from an earlier enabled run cannot leak in.
        new java.io.File("target/starter-application-test.db").delete();
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ResponseIdentityHeaderFilter responseIdentityHeaderFilter;
    @Autowired private PermissionRepository permissions;
    @Autowired private NavigationMenuRepository menus;
    @Autowired private ObjectProvider<ClosetManagementClient> closetClient;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(responseIdentityHeaderFilter)
                .apply(springSecurity())
                .build();
    }

    @Test
    void contextLoads() {}

    @Test
    void responsesExposeTheDefaultIdentityHeaders() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        ResponseIdentityHeaderFilter.POWERED_BY_HEADER_NAME, "ASP.NET"))
                .andExpect(header().string(
                        ResponseIdentityHeaderFilter.SERVER_HEADER_NAME,
                        "Microsoft-IIS/10.0"));
    }

    @Test
    @WithMockUser(authorities = "identity:profile:read")
    void regularUsersCannotReadOperationalActuatorData() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(authorities = "system:config:read")
    void configurationOperatorsCanReadOperationalActuatorData() throws Exception {
        mvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(authorities = "system:sql:execute")
    void sqlConsoleStaysHiddenUntilItIsExplicitlyEnabled() throws Exception {
        mvc.perform(post("/api/v1/system/sql-console/execute")
                        .contentType("application/json")
                        .content("{\"sql\":\"select 1\",\"confirmDangerous\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = "closet:user:read")
    void closetIntegrationIsDisabledWithoutRoutesPermissionsOrMenusByDefault() throws Exception {
        mvc.perform(get("/api/v1/closet/users")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/closet/insights/overview")).andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(permissions.findByCode("closet:user:read")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(permissions.findByCode("closet:insights:read")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(menus.findByCode("closet")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(closetClient.getIfAvailable()).isNull();
    }
}
