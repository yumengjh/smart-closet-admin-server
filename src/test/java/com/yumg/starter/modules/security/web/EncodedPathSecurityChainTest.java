package com.yumg.starter.modules.security.web;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yumg.starter.entities.IpAccessRule;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import com.yumg.starter.modules.security.api.dto.IpAccessRuleRequest;
import com.yumg.starter.modules.security.application.IpAccessRuleService;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Full security filter chain: percent-encoded spellings of an API path must hit the same IP rule,
 * rate-limit bucket and endpoint policy as the plain spelling (#156).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:target/encoded-path-security-test.db",
        "app.sql-console.enabled=false",
        "app.integrations.closet.enabled=false"
})
class EncodedPathSecurityChainTest {
    static {
        new java.io.File("target/encoded-path-security-test.db").delete();
    }

    private static final List<String> LOGIN_SPELLINGS = List.of(
            "/api/v1/auth/login", "/%61pi/v1/auth/login", "/api/v1/auth/%6cogin", "/%61%70%69/v1/%61uth/login");
    private static final String BODY = "{\"username\":\"nobody-here\",\"password\":\"wrong-password-1\",\"rememberMe\":false}";

    @Autowired private WebApplicationContext context;
    @Autowired private RuntimeSettingService settings;
    @Autowired private IpAccessRuleService ipRules;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void resetPolicies() {
        settings.update("security.endpoint.disabled-patterns", "");
        settings.update("security.endpoint.rate-limit.patterns", "");
        settings.update("security.endpoint.rate-limit.capacity", "20");
        ipRules.list().stream().filter(rule -> rule.revokedAt() == null)
                .forEach(rule -> ipRules.revoke(rule.id()));
    }

    @Test
    void deniedIpIsRejectedForEveryEncodedSpellingOfTheLoginPath() throws Exception {
        ipRules.create(new IpAccessRuleRequest(IpAccessRule.Type.DENY, "198.51.100.7", null, "test"));
        for (String path : LOGIN_SPELLINGS) {
            login(path, "198.51.100.7").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("IP_ACCESS_DENIED"));
        }
        // Control-plane recovery stays reachable for a denied IP, but authentication still applies.
        for (String path : List.of("/api/v1/system/runtime-config", "/api/v1/system/%72untime-config")) {
            mvc.perform(get(URI.create(path)).with(remote("198.51.100.7")))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void disabledEndpointPolicyAppliesToEncodedSpellings() throws Exception {
        settings.update("security.endpoint.disabled-patterns", "/api/v1/auth/login");
        for (String path : LOGIN_SPELLINGS) {
            login(path, "198.51.100.20").andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ENDPOINT_DISABLED"));
        }
    }

    @Test
    void encodedSpellingsShareTheEndpointRateLimitBucket() throws Exception {
        settings.update("security.endpoint.rate-limit.patterns", "/api/v1/auth/login");
        settings.update("security.endpoint.rate-limit.capacity", "1");

        login("/api/v1/auth/login", "198.51.100.30").andExpect(status().isUnauthorized());
        for (String path : LOGIN_SPELLINGS) {
            login(path, "198.51.100.30").andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        }

        login("/%61pi/v1/auth/login", "198.51.100.31").andExpect(status().isUnauthorized());
        login("/api/v1/auth/login", "198.51.100.31").andExpect(status().isTooManyRequests());
    }

    private ResultActions login(String path, String ip) throws Exception {
        return mvc.perform(post(URI.create(path)).with(remote(ip))
                .contentType("application/json").content(BODY));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remote(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }
}
