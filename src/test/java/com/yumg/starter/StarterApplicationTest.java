package com.yumg.starter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:target/starter-application-test.db",
        "app.sql-console.enabled=false"
})
class StarterApplicationTest {
    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void contextLoads() {}

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
}
