package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yumg.starter.entities.AuditEvent;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.navigation.infrastructure.NavigationMenuRepository;
import com.yumg.starter.modules.rbac.infrastructure.PermissionRepository;
import com.yumg.starter.modules.rbac.infrastructure.RoleRepository;
import com.yumg.starter.modules.security.infrastructure.AuditEventRepository;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:target/closet-integration-api-test.db",
        "app.integrations.closet.enabled=true",
        "app.integrations.closet.key-id=test-key",
        "app.integrations.closet.secret=0123456789abcdef0123456789abcdef",
        "app.integrations.closet.connect-timeout=1s",
        "app.integrations.closet.read-timeout=150ms",
        "app.runtime.component-startup-probe.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClosetIntegrationApiTest {
    private static final AtomicReference<RecordedRequest> recorded = new AtomicReference<>();
    @DynamicPropertySource static void integrationUrl(DynamicPropertyRegistry registry) {
        registry.add("app.integrations.closet.base-url", () -> "http://closet.test");
    }

    @Autowired WebApplicationContext context;
    @Autowired AuditEventRepository auditEvents;
    @Autowired PermissionRepository permissions;
    @Autowired RoleRepository roles;
    @Autowired NavigationMenuRepository menus;
    @Autowired @Qualifier("closetManagementRestClientBuilder") RestClient.Builder restClientBuilder;
    MockMvc mvc;
    MockRestServiceServer server;
    @BeforeAll void bindMockServer() { server = MockRestServiceServer.bindTo(restClientBuilder).ignoreExpectOrder(true).build(); }
    @BeforeEach void setUp() {
        recorded.set(null);
        server.reset();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void gatedSeedersCreatePermissionAndNavigationForSuperAdmin() {
        assertThat(permissions.findByCode("closet:user:read")).isPresent();
        assertThat(permissions.findByCode("closet:insights:read")).isPresent();
        assertThat(menus.findByCode("closet")).get().extracting(menu -> menu.getRoutePath()).isEqualTo("/closet");
        assertThat(menus.findByCode("closet-users")).isPresent();
        assertThat(menus.findByCode("closet-insights")).isPresent();
        assertThat(roles.findAllWithPermissions().stream().filter(role -> role.getCode().equals("SUPER_ADMIN"))
                .findFirst().orElseThrow().getPermissions()).extracting(permission -> permission.getCode())
                .contains("closet:user:read", "closet:user:write", "closet:user:reset-password",
                        "closet:quota:write", "closet:insights:read");
    }

    @Test void requiresAuthenticationAndTheRoutePermission() throws Exception {
        mvc.perform(get("/api/v1/closet/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/closet/users").with(jwtAs("system:user:read")))
                .andExpect(status().isForbidden());
    }

    @Test void translatesPagingAndWrapsTheSuccessfulResponse() throws Exception {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/users")))
                .andExpect(request -> capture(request))
                .andRespond(withSuccess("{\"items\":[{\"username\":\"alice\"}],\"total\":11,\"page\":2,\"size\":5}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/users?page=1&size=5&q=ali").with(jwtAs("closet:user:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5)).andExpect(jsonPath("$.data.totalElements").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(3)).andExpect(jsonPath("$.data.items[0].username").value("alice"));
        server.verify();
        assertThat(recorded.get().rawQuery()).contains("page=2", "size=5", "q=ali");
        assertThat(recorded.get().keyId()).isEqualTo("test-key");
        assertThat(recorded.get().signature()).isNotBlank();
    }

    @Test void auditsUserCreationWithoutPersistingThePassword() throws Exception {
        server.expect(requestTo("http://closet.test/api/manage/v1/users"))
                .andExpect(request -> capture(request))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(withSuccess("{\"username\":\"alice\",\"temporaryPassword\":null}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/v1/closet/users").with(jwtAs("closet:user:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"role\":\"USER\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.username").value("alice"));
        server.verify();
        AuditEvent event = auditEvents.findAll().stream().filter(item -> item.getAction().equals("CLOSET_USER_CREATED")).findFirst().orElseThrow();
        assertThat(event.getResult()).isEqualTo("SUCCESS");
        assertThat(event.getMetadata()).contains("alice").doesNotContain("correct-horse-battery", "password");
    }

    @Test void upstreamUnauthorizedMapsTo502AndTimeoutFailureIsAuditedAs503() throws Exception {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/users")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"MANAGEMENT_SIGNATURE_INVALID\"}"));
        mvc.perform(get("/api/v1/closet/users").with(jwtAs("closet:user:read")))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("CLOSET_INTEGRATION_MISCONFIGURED"));
        server.verify();
        server.reset();
        server.expect(requestTo("http://closet.test/api/manage/v1/users"))
                .andRespond(request -> { throw new org.springframework.web.client.ResourceAccessException("Read timed out"); });
        mvc.perform(post("/api/v1/closet/users").with(jwtAs("closet:user:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"timeout-user\",\"role\":\"USER\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CLOSET_SERVICE_UNAVAILABLE"));
        server.verify();
        AuditEvent failed = auditEvents.findAll().stream().filter(item -> item.getAction().equals("CLOSET_USER_CREATED")
                && "timeout-user".equals(item.getTargetId())).findFirst().orElseThrow();
        assertThat(failed.getResult()).isEqualTo("FAILURE");
        assertThat(failed.getMetadata()).contains("CLOSET_SERVICE_UNAVAILABLE").doesNotContain("password");
    }

    @Test void forwardsOnlyWhitelistedInsightsRoutesAndParameters() throws Exception {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/insights/trends")))
                .andExpect(request -> capture(request))
                .andRespond(withSuccess("{\"scope\":{\"username\":\"alice\"}}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/insights/trends?metric=garments_created&metric=users_created&username=alice")
                        .with(jwtAs("closet:insights:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.scope.username").value("alice"));
        server.verify();
        assertThat(recorded.get().path()).isEqualTo("/api/manage/v1/insights/trends");
        assertThat(recorded.get().rawQuery()).contains("metric=garments_created", "metric=users_created", "username=alice");
        mvc.perform(get("/api/v1/closet/insights/secret").with(jwtAs("closet:insights:read")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/closet/insights/wardrobe?unexpected=1").with(jwtAs("closet:insights:read")))
                .andExpect(status().isBadRequest());
    }

    @Test void forwardsTheRemainingUserOperationsWithTheConfiguredAuthorities() throws Exception {
        String root = "http://closet.test/api/manage/v1/users/alice";
        server.expect(requestTo(root)).andRespond(withSuccess("{\"username\":\"alice\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/status")).andRespond(withSuccess("{\"enabled\":false}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/role")).andRespond(withSuccess("{\"role\":\"ADMIN\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/password-reset")).andRespond(withSuccess("{\"username\":\"alice\",\"temporaryPassword\":\"one-time\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/sessions")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/sessions")).andRespond(withSuccess("{\"revokedSessions\":2}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/portable-links")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/portable-links/link-1")).andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(root + "/quota")).andRespond(withSuccess("{\"image\":{\"limit\":20}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/quota")).andRespond(withSuccess("{\"image\":{\"limit\":30}}", MediaType.APPLICATION_JSON));

        mvc.perform(get("/api/v1/closet/users/alice").with(jwtAs("closet:user:read"))).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/closet/users/alice/status")
                        .with(jwtAs("closet:user:write")).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/closet/users/alice/role")
                        .with(jwtAs("closet:user:write")).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/closet/users/alice/password-reset").with(jwtAs("closet:user:reset-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":null}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/closet/users/alice/sessions").with(jwtAs("closet:user:read"))).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/closet/users/alice/sessions")
                        .with(jwtAs("closet:user:write"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/closet/users/alice/portable-links").with(jwtAs("closet:user:read"))).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/closet/users/alice/portable-links/link-1")
                        .with(jwtAs("closet:user:write"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/closet/users/alice/quota").with(jwtAs("closet:user:read"))).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/closet/users/alice/quota")
                        .with(jwtAs("closet:quota:write")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageDailyLimit\":30,\"agentDailyLimit\":null}"))
                .andExpect(status().isOk());
        server.verify();
        assertThat(auditEvents.findAll().stream().filter(event -> event.getAction().startsWith("CLOSET_")
                && "FAILURE".equals(event.getResult())).map(AuditEvent::getMetadata))
                .allSatisfy(metadata -> assertThat(metadata).doesNotContain("one-time", "password"));
    }

    @Test void translatesUpstreamValidationNotFoundConflictAndServerErrors() throws Exception {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/users")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"VALIDATION_FAILED\",\"detail\":\"invalid username\",\"violations\":[{\"field\":\"username\",\"message\":\"invalid\"}]}"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/users/missing")))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"USER_NOT_FOUND\",\"detail\":\"user missing\"}"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/users/alice/status")))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"LAST_ADMIN_PROTECTED\"}"));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/users")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"INTERNAL_ERROR\"}"));

        mvc.perform(get("/api/v1/closet/users").with(jwtAs("closet:user:read")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("username"));
        mvc.perform(get("/api/v1/closet/users/missing").with(jwtAs("closet:user:read")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("user missing"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/closet/users/alice/status")
                        .with(jwtAs("closet:user:write")).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLOSET_LAST_ADMIN_PROTECTED"));
        mvc.perform(get("/api/v1/closet/users").with(jwtAs("closet:user:read")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CLOSET_SERVICE_UNAVAILABLE"));
        server.verify();
    }

    private static void capture(ClientHttpRequest request) {
        try {
            recorded.set(new RecordedRequest(request.getURI().getRawPath(), request.getURI().getRawQuery(),
                    request.getMethod().name(), "", request.getHeaders().getFirst("X-Closet-Key-Id"),
                    request.getHeaders().getFirst("X-Closet-Signature")));
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtAs(String authority) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt.claim("username", "admin"))
                .authorities(() -> authority);
    }
    private record RecordedRequest(String path, String rawQuery, String method, String body, String keyId, String signature) {}
}
