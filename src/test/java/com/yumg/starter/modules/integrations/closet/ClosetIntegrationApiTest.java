package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
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
    private static final java.util.concurrent.atomic.AtomicInteger clientSequence = new java.util.concurrent.atomic.AtomicInteger();
    MockMvc mvc;
    MockRestServiceServer server;
    @BeforeAll void bindMockServer() { server = MockRestServiceServer.bindTo(restClientBuilder).ignoreExpectOrder(true).build(); }
    @BeforeEach void setUp() {
        recorded.set(null);
        server.reset();
        // Each case represents a fresh client, so accumulated requests cannot mask RBAC or validation results.
        String clientIp = "192.0.2." + clientSequence.incrementAndGet();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").with(request -> { request.setRemoteAddr(clientIp); return request; })).build();
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

    @Test void newUsernamesFollowTheClosetDotRuleWhileSingleDotsStayManageable() throws Exception {
        for (String username : java.util.List.of("..", "...", "a..b")) {
            mvc.perform(post("/api/v1/closet/users").with(jwtAs("closet:user:write"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"role\":\"USER\"}"))
                    .andExpect(status().isBadRequest());
        }
        server.expect(requestTo("http://closet.test/api/manage/v1/users"))
                .andRespond(withSuccess("{\"username\":\"a.b\",\"temporaryPassword\":null}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/users/a.b/status"))
                .andRespond(withSuccess("{\"enabled\":false}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/v1/closet/users").with(jwtAs("closet:user:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"a.b\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/closet/users/a.b/status").with(jwtAs("closet:user:write"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        server.verify();
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

    /** Issue #181: passwords follow the closet rule, 12 characters to 72 UTF-8 bytes, with the closet's own messages. */
    @Test void passwordsAreLimitedTo72Utf8BytesLikeTheCloset() throws Exception {
        String tooLong = "密码不能超过 72 字节（英文、数字和符号各占 1 字节，汉字通常占 3 字节，表情符号占 4 字节）。";
        for (String password : java.util.List.of("a".repeat(73), "汉".repeat(25), "😀".repeat(19))) {
            mvc.perform(post("/api/v1/closet/users").with(jwtAs("closet:user:write")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"long-pass\",\"role\":\"USER\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations[0].field").value("password"))
                    .andExpect(jsonPath("$.violations[0].message").value(tooLong));
            mvc.perform(post("/api/v1/closet/users/alice/password-reset").with(jwtAs("closet:user:reset-password"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + password + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.violations[0].message").value(tooLong));
        }
        mvc.perform(post("/api/v1/closet/users/alice/password-reset").with(jwtAs("closet:user:reset-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + "汉".repeat(11) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0].message").value("密码至少需要 12 个字符。"));

        server.expect(requestTo("http://closet.test/api/manage/v1/users"))
                .andRespond(withSuccess("{\"username\":\"long-pass\",\"temporaryPassword\":null}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/users/alice/password-reset"))
                .andRespond(withSuccess("{\"username\":\"alice\",\"temporaryPassword\":null}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/v1/closet/users").with(jwtAs("closet:user:write")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"long-pass\",\"role\":\"USER\",\"password\":\"" + "汉".repeat(24) + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/closet/users/alice/password-reset").with(jwtAs("closet:user:reset-password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + "😀".repeat(18) + "\"}"))
                .andExpect(status().isOk());
        server.verify();
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


    @Test void phaseTwoPermissionsAndMenusAreSeeded() {
        for (String permission : java.util.List.of("closet:ops:read", "closet:dashboard:read", "closet:exports:read", "closet:templates:read", "closet:templates:write"))
            assertThat(permissions.findByCode(permission)).isPresent();
        for (String code : java.util.List.of("closet-ops", "closet-exports", "closet-templates"))
            assertThat(menus.findByCode(code)).isPresent();
        // The business screen is the home page; it no longer has a menu entry of its own.
        assertThat(menus.findByCode("closet-dashboard")).isEmpty();
        assertThat(menus.findByCode("closet-ops").orElseThrow().getIcon()).startsWith("ri/");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "insights/recommendations,closet:insights:read", "insights/ai,closet:insights:read",
            "ops/status,closet:ops:read", "dashboard/snapshot,closet:dashboard:read",
            "exports/daily,closet:exports:read", "exports/events,closet:exports:read", "templates,closet:templates:read"})
    void phaseTwoReadsAreSignedAndPermissionProtected(String path, String permission) throws Exception {
        mvc.perform(get("/api/v1/closet/" + path)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/closet/" + path).with(jwtAs("system:user:read"))).andExpect(status().isForbidden());
        server.expect(requestTo("http://closet.test/api/manage/v1/" + path))
                .andExpect(request -> capture(request)).andRespond(withSuccess("{\"marker\":1}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/" + path).with(jwtAs(permission)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.marker").value(1));
        assertThat(recorded.get().signature()).isNotBlank(); server.verify();
    }

    @Test void taskPagingPreservesRepeatedFiltersAndOperationLogPagingIsZeroBased() throws Exception {
        for (String path : java.util.List.of("tasks", "operation-log")) {
            server.reset();
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/ops/" + path)))
                    .andExpect(request -> capture(request))
                    .andRespond(withSuccess("{\"items\":[{\"id\":\"task-1\"}],\"total\":11,\"page\":2,\"size\":5}", MediaType.APPLICATION_JSON));
            var request = get("/api/v1/closet/ops/" + path).param("page", "1").param("size", "5").with(jwtAs("closet:ops:read"));
            if (path.equals("tasks")) request.param("status", "FAILED", "RUNNING");
            mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.totalElements").value(11)).andExpect(jsonPath("$.data.totalPages").value(3));
            assertThat(recorded.get().rawQuery()).contains("page=2", "size=5");
            if (path.equals("tasks")) assertThat(recorded.get().rawQuery()).contains("status=FAILED", "status=RUNNING");
            server.verify();
        }
        server.verify();
    }

    @Test void csvDownloadIsRawUtf8AndRetainsAuthentication() throws Exception {
        mvc.perform(get("/api/v1/closet/exports/daily?format=csv")).andExpect(status().isUnauthorized());
        server.expect(requestTo("http://closet.test/api/manage/v1/exports/daily?format=csv"))
                .andExpect(request -> capture(request)).andRespond(withSuccess("date,active_users\n2026-10-10,2\n", MediaType.parseMediaType("text/csv;charset=UTF-8")));
        var result = mvc.perform(get("/api/v1/closet/exports/daily?format=csv").with(jwtAs("closet:exports:read")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("date,active_users\n2026-10-10,2\n"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Disposition", "attachment; filename=\"closet-daily.csv\""));
        assertThat(recorded.get().signature()).isNotBlank(); server.verify();
    }

    @Test void reviewRequiresWritePermissionValidatesAndAudits() throws Exception {
        var patch = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/closet/templates/file_template.v1");
        mvc.perform(patch.with(jwtAs("closet:templates:read")).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
        for (String invalid : java.util.List.of("{}", "{\"bonus\":6}", "{\"reviewStatus\":\"WRONG\"}", "{\"unknown\":true}"))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/closet/templates/file_template.v1")
                    .with(jwtAs("closet:templates:write")).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        server.expect(requestTo("http://closet.test/api/manage/v1/templates/file_template.v1"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(HttpMethod.PATCH))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json("{\"enabled\":true}"))
                .andRespond(withSuccess("{\"id\":\"file_template.v1\",\"enabled\":true}", MediaType.APPLICATION_JSON));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/closet/templates/file_template.v1")
                .with(jwtAs("closet:templates:write")).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true));
        assertThat(auditEvents.findAll().stream().filter(event -> event.getAction().equals("CLOSET_TEMPLATE_REVIEW")))
                .anySatisfy(event -> { assertThat(event.getTargetId()).isEqualTo("file_template.v1"); assertThat(event.getResult()).isEqualTo("SUCCESS"); });
        server.verify();
    }

    @Test void phaseTwoRejectsUnknownQueryAndBadPaginationWithoutUpstreamCalls() throws Exception {
        mvc.perform(get("/api/v1/closet/ops/tasks?page=-1").with(jwtAs("closet:ops:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/ops/tasks?size=101").with(jwtAs("closet:ops:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/ops/tasks?page=1&page=2").with(jwtAs("closet:ops:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/dashboard/snapshot?username=alice").with(jwtAs("closet:dashboard:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/exports/events?username=alice").with(jwtAs("closet:exports:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/exports/daily?format=xml").with(jwtAs("closet:exports:read"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/closet/ops/tasks/task-1/retry").with(jwtAs("closet:ops:read"))).andExpect(status().isNotFound());
        server.verify();
    }

    @Test void accountPermissionsAndMenusMatchTheContract() {
        assertThat(roles.findAllWithPermissions().stream().filter(role -> role.getCode().equals("SUPER_ADMIN"))
                .findFirst().orElseThrow().getPermissions()).extracting(permission -> permission.getCode())
                .contains("closet:registration:read", "closet:registration:write");
        var directory = menus.findByCode("closet").orElseThrow();
        var registration = menus.findByCode("closet-registration").orElseThrow();
        assertThat(registration.getParentId()).isEqualTo(directory.getId());
        assertThat(registration.getTitle()).isEqualTo("注册与邀请");
        assertThat(registration.getRoutePath()).isEqualTo("/closet/registration");
        assertThat(registration.getComponentKey()).isEqualTo("closet-registration");
        assertThat(registration.getIcon()).isEqualTo("ri/coupon-3-line");
        assertThat(registration.getSortOrder()).isEqualTo(25);
        assertThat(registration.getRequiredPermission()).isEqualTo("closet:registration:read");
        var events = menus.findByCode("closet-account-events").orElseThrow();
        assertThat(events.getParentId()).isEqualTo(directory.getId());
        assertThat(events.getTitle()).isEqualTo("账号审计");
        assertThat(events.getRoutePath()).isEqualTo("/closet/account-events");
        assertThat(events.getComponentKey()).isEqualTo("closet-account-events");
        assertThat(events.getIcon()).isEqualTo("ri/file-list-3-line");
        assertThat(events.getSortOrder()).isEqualTo(27);
        assertThat(events.getRequiredPermission()).isEqualTo("closet:user:read");
    }

    private record AccountRoute(HttpMethod method, String path, String permission, String body,
                                String response, int status, String action) {
        String upstream() {
            return "http://closet.test/api/manage/v1" + path
                    + (method == HttpMethod.GET && (path.equals("/invites") || path.equals("/account-events")) ? "?page=1&size=20" : "");
        }
        @Override public String toString() { return method + " " + path; }
    }
    static java.util.stream.Stream<AccountRoute> accountRoutes() {
        return java.util.stream.Stream.of(
                new AccountRoute(HttpMethod.PATCH, "/users/alice/profile", "closet:user:write", "{\"displayName\":\"\"}", "{\"username\":\"alice\",\"displayName\":null}", 200, "CLOSET_USER_PROFILE_UPDATED"),
                new AccountRoute(HttpMethod.DELETE, "/users/alice/sessions/0123456789abcdef", "closet:user:write", null, null, 204, "CLOSET_USER_SESSION_REVOKED"),
                new AccountRoute(HttpMethod.GET, "/users/alice/login-lock", "closet:user:read", null, "{\"locked\":true,\"recentFailures\":5,\"retryAfterSeconds\":812}", 200, null),
                new AccountRoute(HttpMethod.DELETE, "/users/alice/login-lock", "closet:user:write", null, "{\"locked\":false,\"recentFailures\":0,\"retryAfterSeconds\":0}", 200, "CLOSET_USER_LOGIN_UNLOCKED"),
                new AccountRoute(HttpMethod.GET, "/users/alice/events", "closet:user:read", null, "[{\"action\":\"MANAGE_PROFILE_UPDATED\"}]", 200, null),
                new AccountRoute(HttpMethod.GET, "/registration", "closet:registration:read", null, "{\"mode\":\"INVITE\",\"configuredMode\":\"INVITE\",\"overridden\":false,\"activeInviteCount\":1}", 200, null),
                new AccountRoute(HttpMethod.PUT, "/registration", "closet:registration:write", "{\"mode\":\"OPEN\"}", "{\"mode\":\"OPEN\"}", 200, "CLOSET_REGISTRATION_MODE_CHANGED"),
                new AccountRoute(HttpMethod.GET, "/invites", "closet:registration:read", null, "{\"items\":[],\"total\":0,\"page\":1,\"size\":20}", 200, null),
                new AccountRoute(HttpMethod.POST, "/invites", "closet:registration:write", "{\"note\":\"渠道 A\",\"maxUses\":3,\"expiresInDays\":7}", "{\"id\":\"invite-12\",\"code\":\"SECRET-ONCE-CODE\"}", 201, "CLOSET_INVITE_CREATED"),
                new AccountRoute(HttpMethod.GET, "/invites/invite-12", "closet:registration:read", null, "{\"invite\":{\"id\":\"invite-12\"},\"registeredUsers\":[{\"username\":\"alice\",\"createdAtMs\":1,\"enabled\":true}]}", 200, null),
                new AccountRoute(HttpMethod.PATCH, "/invites/invite-12", "closet:registration:write", "{\"note\":\"\",\"maxUses\":4}", "{\"id\":\"invite-12\",\"note\":\"\",\"maxUses\":4}", 200, "CLOSET_INVITE_UPDATED"),
                new AccountRoute(HttpMethod.DELETE, "/invites/invite-12", "closet:registration:write", null, null, 204, "CLOSET_INVITE_REVOKED"),
                new AccountRoute(HttpMethod.GET, "/account-events", "closet:user:read", null, "{\"items\":[],\"total\":0,\"page\":1,\"size\":20}", 200, null));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("accountRoutes")
    void accountRoutesForwardMethodBodyAndEnforceTheirOwnPermission(AccountRoute route) throws Exception {
        var denied = request(route.method(), "/api/v1/closet" + route.path()).contentType(MediaType.APPLICATION_JSON);
        var anonymous = request(route.method(), "/api/v1/closet" + route.path()).contentType(MediaType.APPLICATION_JSON);
        if (route.body() != null) { denied.content(route.body()); anonymous.content(route.body()); }
        mvc.perform(anonymous).andExpect(status().isUnauthorized());
        mvc.perform(denied.with(jwtAs("system:user:read"))).andExpect(status().isForbidden());
        if (route.action() != null) {
            var readOnly = request(route.method(), "/api/v1/closet" + route.path()).contentType(MediaType.APPLICATION_JSON);
            if (route.body() != null) readOnly.content(route.body());
            mvc.perform(readOnly.with(jwtAs(route.permission().replace(":write", ":read")))).andExpect(status().isForbidden());
        }
        var expected = server.expect(requestTo(route.upstream())).andExpect(method(route.method())).andExpect(ClosetIntegrationApiTest::capture);
        if (route.body() != null) expected.andExpect(content().json(route.body()));
        expected.andRespond(route.status() == 204 ? withStatus(HttpStatus.NO_CONTENT) : withSuccess(route.response(), MediaType.APPLICATION_JSON));
        long before = auditEvents.count();
        var forwarded = request(route.method(), "/api/v1/closet" + route.path()).with(jwtAs(route.permission())).contentType(MediaType.APPLICATION_JSON);
        if (route.body() != null) forwarded.content(route.body());
        var result = mvc.perform(forwarded).andExpect(status().is(route.status()));
        if (route.status() == 204) result.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));
        else if (route.path().equals("/invites") && route.method() == HttpMethod.POST)
            result.andExpect(jsonPath("$.data.code").value("SECRET-ONCE-CODE"));
        else if (route.path().equals("/invites") || route.path().equals("/account-events"))
            result.andExpect(jsonPath("$.data.page").value(0));
        else result.andExpect(jsonPath("$.data").exists());
        assertThat(recorded.get().signature()).isNotBlank();
        if (route.action() == null) assertThat(auditEvents.count()).isEqualTo(before);
        else {
            assertThat(auditEvents.count()).isEqualTo(before + 1);
            assertThat(auditEvents.findAll().stream().filter(event -> route.action().equals(event.getAction()) && "SUCCESS".equals(event.getResult())))
                    .anySatisfy(event -> {
                        assertThat(event.getMetadata()).doesNotContain("SECRET-ONCE-CODE", "code", "渠道 A");
                        if (route.action().equals("CLOSET_INVITE_CREATED")) {
                            assertThat(event.getTargetId()).isEqualTo("invite-12");
                            assertThat(event.getMetadata()).isEqualTo("{\"id\":\"invite-12\"}");
                        }
                    });
        }
        server.verify();
    }

    static java.util.stream.Stream<AccountRoute> accountWrites() { return accountRoutes().filter(route -> route.action() != null); }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.MethodSource("accountWrites")
    void everyAccountWriteAuditsUpstreamFailureWithoutSecrets(AccountRoute route) throws Exception {
        server.expect(requestTo(route.upstream())).andExpect(method(route.method()))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"INTERNAL_ERROR\",\"detail\":\"SECRET-ONCE-CODE\"}"));
        long before = auditEvents.count();
        var forwarded = request(route.method(), "/api/v1/closet" + route.path()).with(jwtAs(route.permission())).contentType(MediaType.APPLICATION_JSON);
        if (route.body() != null) forwarded.content(route.body());
        mvc.perform(forwarded).andExpect(status().isServiceUnavailable());
        assertThat(auditEvents.count()).isEqualTo(before + 1);
        assertThat(auditEvents.findAll().stream().filter(event -> route.action().equals(event.getAction()) && "FAILURE".equals(event.getResult())))
                .anySatisfy(event -> assertThat(event.getMetadata()).contains("CLOSET_SERVICE_UNAVAILABLE")
                        .doesNotContain("SECRET-ONCE-CODE", "渠道 A", "password"));
        server.verify();
    }

    @Test void accountListsWhitelistFiltersConvertPagingAndPreserveNewFields() throws Exception {
        String user = "{\"username\":\"alice\",\"displayName\":\"小鱼\",\"registeredVia\":\"INVITE\",\"lastLoginAtMs\":123,\"deactivatedAtMs\":456}";
        server.expect(requestTo("http://closet.test/api/manage/v1/users?sort=lastLoginAt&registeredVia=INVITE&deactivated=true&page=2&size=5"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("{\"items\":[" + user + "],\"total\":11,\"page\":2,\"size\":5}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/users?sort=lastLoginAt&registeredVia=INVITE&deactivated=true&page=1&size=5").with(jwtAs("closet:user:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.items[0].displayName").value("小鱼")).andExpect(jsonPath("$.data.items[0].registeredVia").value("INVITE"))
                .andExpect(jsonPath("$.data.items[0].lastLoginAtMs").value(123)).andExpect(jsonPath("$.data.items[0].deactivatedAtMs").value(456));
        server.verify(); server.reset();
        server.expect(requestTo("http://closet.test/api/manage/v1/users/alice"))
                .andRespond(withSuccess(user.substring(0, user.length() - 1) + ",\"inviteId\":\"invite-12\",\"loginLock\":{\"locked\":true}}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/users/alice").with(jwtAs("closet:user:read"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviteId").value("invite-12")).andExpect(jsonPath("$.data.loginLock.locked").value(true));
        server.verify(); server.reset();
        for (String path : java.util.List.of("invites", "account-events")) {
            String filters = path.equals("invites") ? "status=ACTIVE" : "from=2026-10-01&to=2026-10-20&username=alice&action=LOGIN_SUCCEEDED&actor=manage%3Aprimary%3Aadmin";
            server.expect(requestTo("http://closet.test/api/manage/v1/" + path + "?" + filters + "&page=2&size=5"))
                    .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("{\"items\":[{\"id\":\"one\"}],\"total\":11,\"page\":2,\"size\":5}", MediaType.APPLICATION_JSON));
            mvc.perform(get("/api/v1/closet/" + path)
                            .queryParams(org.springframework.web.util.UriComponentsBuilder.fromUriString("?" + filters.replace("%3A", ":")).build().getQueryParams())
                            .queryParam("page", "1").queryParam("size", "5")
                            .with(jwtAs(path.equals("invites") ? "closet:registration:read" : "closet:user:read")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.totalElements").value(11))
                    .andExpect(jsonPath("$.data.totalPages").value(3)).andExpect(jsonPath("$.data.items[0].id").value("one"));
            server.verify(); server.reset();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "users,registeredVia=INVALID", "users,deactivated=yes", "users,sort=invalid", "users,unexpected=x", "users,page=1&page=2", "users,page=2147483647",
            "invites,status=INVALID", "invites,status=ACTIVE&status=EXPIRED", "invites,unexpected=x", "invites,page=-1", "invites,size=101", "invites,page=1&page=2",
            "account-events,unexpected=x", "account-events,from=2026-02-30", "account-events,to=20-10-2026", "account-events,action=bad-action",
            "account-events,username=x", "account-events,action=LOGIN_SUCCEEDED&action=REGISTERED", "account-events,size=0", "account-events,page=NaN"})
    void accountQueriesRejectUnknownInvalidAndRepeatedParameters(String path, String query) throws Exception {
        mvc.perform(get("/api/v1/closet/" + path + "?" + query).with(jwtAs(path.equals("invites") ? "closet:registration:read" : "closet:user:read")))
                .andExpect(status().isBadRequest());
        server.verify();
    }

    @Test void accountFiltersRejectOverlongValues() throws Exception {
        mvc.perform(get("/api/v1/closet/account-events").param("actor", "a".repeat(201)).with(jwtAs("closet:user:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/account-events").param("action", "A".repeat(49)).with(jwtAs("closet:user:read"))).andExpect(status().isBadRequest());
        server.verify();
    }

    @Test void validatesAccountRequestBodiesAndPathVariablesBeforeCallingUpstream() throws Exception {
        for (String body : java.util.List.of("{}", "{\"displayName\":null}", "{\"displayName\":\"" + "x".repeat(41) + "\"}", "{\"displayName\":\"ok\",\"extra\":1}"))
            mvc.perform(request(HttpMethod.PATCH, "/api/v1/closet/users/alice/profile").with(jwtAs("closet:user:write")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, "/api/v1/closet/users/alice/sessions/ABCDEF0123456789").with(jwtAs("closet:user:write"))).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, "/api/v1/closet/users/alice/sessions/short").with(jwtAs("closet:user:write"))).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.PUT, "/api/v1/closet/registration").with(jwtAs("closet:registration:write")).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ANY\"}")).andExpect(status().isBadRequest());
        for (String body : java.util.List.of("{\"note\":\"" + "x".repeat(101) + "\"}", "{\"maxUses\":0}", "{\"maxUses\":101}", "{\"expiresInDays\":0}", "{\"expiresInDays\":91}", "{\"code\":\"secret\"}"))
            mvc.perform(post("/api/v1/closet/invites").with(jwtAs("closet:registration:write")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        for (String body : java.util.List.of("{}", "{\"note\":null,\"maxUses\":null,\"expiresAtMs\":null}", "{\"note\":\"" + "x".repeat(101) + "\"}", "{\"maxUses\":0}", "{\"maxUses\":101}", "{\"expiresAtMs\":0}", "{\"expiresAtMs\":" + (System.currentTimeMillis() + java.time.Duration.ofDays(91).toMillis()) + "}"))
            mvc.perform(request(HttpMethod.PATCH, "/api/v1/closet/invites/invite-12").with(jwtAs("closet:registration:write")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/invites/" + "i".repeat(37)).with(jwtAs("closet:registration:read"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/closet/users").with(jwtAs("closet:user:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"role\":\"USER\",\"displayName\":\"" + "x".repeat(41) + "\"}")).andExpect(status().isBadRequest());
        server.verify();
    }

    @Test void forwardsDisplayNameOnCreationWithoutAuditingTemporaryPassword() throws Exception {
        server.expect(requestTo("http://closet.test/api/manage/v1/users")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"username\":\"named-user\",\"role\":\"USER\",\"displayName\":\"小鱼\"}"))
                .andRespond(withSuccess("{\"username\":\"named-user\",\"temporaryPassword\":\"SECRET-TEMP-PASSWORD\"}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/v1/closet/users").with(jwtAs("closet:user:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"named-user\",\"role\":\"USER\",\"displayName\":\"小鱼\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.temporaryPassword").value("SECRET-TEMP-PASSWORD"));
        assertThat(auditEvents.findAll().stream().filter(event -> "named-user".equals(event.getTargetId())))
                .allSatisfy(event -> assertThat(event.getMetadata()).doesNotContain("SECRET-TEMP-PASSWORD", "temporaryPassword", "小鱼"));
        server.verify();
    }

    @Test void registrationNullRestoresDefaultAndInviteDefaultsRemainUpstreamOwned() throws Exception {
        for (String body : java.util.List.of("{\"mode\":null}", "{}")) {
            server.expect(requestTo("http://closet.test/api/manage/v1/registration")).andExpect(method(HttpMethod.PUT))
                    .andExpect(content().json("{\"mode\":null}", org.springframework.test.json.JsonCompareMode.STRICT))
                    .andRespond(withSuccess("{\"mode\":\"INVITE\",\"overridden\":false}", MediaType.APPLICATION_JSON));
            mvc.perform(request(HttpMethod.PUT, "/api/v1/closet/registration").with(jwtAs("closet:registration:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.overridden").value(false));
            server.verify(); server.reset();
        }
        server.expect(requestTo("http://closet.test/api/manage/v1/invites")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"id\":\"default-invite\",\"maxUses\":1,\"code\":\"DEFAULT-SECRET-CODE\"}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/v1/closet/invites").with(jwtAs("closet:registration:write")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.maxUses").value(1));
        server.verify();
    }

    @Test void inviteUpdatesPreservePartialFieldsAndEncodeTheIdAsOnePathSegment() throws Exception {
        long expiry = System.currentTimeMillis() + java.time.Duration.ofDays(1).toMillis();
        String body = "{\"expiresAtMs\":" + expiry + "}";
        server.expect(requestTo("http://closet.test/api/manage/v1/invites/%E6%B8%A0%E9%81%93%20A"))
                .andExpect(method(HttpMethod.PATCH)).andExpect(content().json(body, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"id\":\"渠道 A\",\"expiresAtMs\":" + expiry + "}", MediaType.APPLICATION_JSON));
        mvc.perform(request(HttpMethod.PATCH, "/api/v1/closet/invites/{id}", "渠道 A").with(jwtAs("closet:registration:write"))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresAtMs").value(expiry));
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "DELETE, /users/alice/sessions/0123456789abcdef, SESSION_NOT_FOUND, 404, NOT_FOUND, CLOSET_USER_SESSION_REVOKED",
            "GET, /invites/missing, INVITE_NOT_FOUND, 404, NOT_FOUND, ''",
            "PATCH, /invites/invite-12, INVITE_REVOKED, 409, CONFLICT, CLOSET_INVITE_UPDATED"})
    void accountProblemDetailsKeepNotFoundAndConflictSemantics(String verb, String path, String code, int httpStatus, String platformCode, String action) throws Exception {
        HttpMethod method = HttpMethod.valueOf(verb);
        server.expect(requestTo("http://closet.test/api/manage/v1" + path)).andExpect(method(method))
                .andRespond(withStatus(HttpStatus.valueOf(httpStatus)).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"" + code + "\",\"detail\":\"上游业务错误\"}"));
        var req = request(method, "/api/v1/closet" + path).with(jwtAs(path.startsWith("/users") ? "closet:user:write" : method == HttpMethod.GET ? "closet:registration:read" : "closet:registration:write"));
        if (method == HttpMethod.PATCH) req.contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"new\"}");
        mvc.perform(req).andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(platformCode)).andExpect(jsonPath("$.message").value("上游业务错误"));
        if (!action.isEmpty()) assertThat(auditEvents.findAll().stream().filter(event -> action.equals(event.getAction()) && "FAILURE".equals(event.getResult())))
                .anySatisfy(event -> assertThat(event.getMetadata()).contains(platformCode).doesNotContain("上游业务错误"));
        server.verify();
    }

    @Test void styleTermPermissionsAndMenuAreSeeded() {
        for (String permission : java.util.List.of("closet:styleterms:read", "closet:styleterms:write")) assertThat(permissions.findByCode(permission)).isPresent();
        assertThat(menus.findByCode("closet-style-terms").orElseThrow().getRequiredPermission()).isEqualTo("closet:styleterms:read");
        assertThat(roles.findAllWithPermissions().stream().filter(role -> role.getCode().equals("SUPER_ADMIN"))
                .findFirst().orElseThrow().getPermissions()).extracting(permission -> permission.getCode())
                .contains("closet:styleterms:read", "closet:styleterms:write");
    }

    @Test void styleTermQueueIsSignedZeroBasedAndPermissionProtected() throws Exception {
        mvc.perform(get("/api/v1/closet/style-terms/pending")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/closet/style-terms/pending").with(jwtAs("closet:templates:read"))).andExpect(status().isForbidden());
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/style-terms/pending?")))
                .andExpect(request -> capture(request))
                .andRespond(withSuccess("{\"items\":[{\"term\":\"多巴胺\",\"styles\":[\"休闲\"],\"hits\":3,\"proposers\":2}],\"total\":11,\"page\":2,\"size\":5,\"vocabulary\":[\"休闲\",\"甜美\"]}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/style-terms/pending?page=1&size=5").with(jwtAs("closet:styleterms:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.totalElements").value(11))
                .andExpect(jsonPath("$.data.totalPages").value(3)).andExpect(jsonPath("$.data.items[0].term").value("多巴胺"))
                .andExpect(jsonPath("$.data.vocabulary[1]").value("甜美"));
        assertThat(recorded.get().rawQuery()).contains("page=2", "size=5");
        assertThat(recorded.get().signature()).isNotBlank(); server.verify();
        mvc.perform(get("/api/v1/closet/style-terms/pending?username=alice").with(jwtAs("closet:styleterms:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/style-terms/pending?size=101").with(jwtAs("closet:styleterms:read"))).andExpect(status().isBadRequest());
    }

    @Test void styleTermApproveAndRejectNeedWritePermissionValidateAndAudit() throws Exception {
        String body = "{\"term\":\"多巴胺\",\"proposedStyles\":[\"休闲\"],\"styles\":[\"休闲\",\"运动\"]}";
        for (String action : java.util.List.of("approve", "reject")) {
            String path = "/api/v1/closet/style-terms/pending/" + action;
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
            mvc.perform(post(path).with(jwtAs("closet:styleterms:read")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            for (String invalid : java.util.List.of("{}", "{\"term\":\"x\",\"proposedStyles\":[]}", "{\"term\":\"x\",\"proposedStyles\":[\"a\",\"b\",\"c\"]}"))
                mvc.perform(post(path).with(jwtAs("closet:styleterms:write")).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        server.expect(requestTo("http://closet.test/api/manage/v1/style-terms/pending/approve")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"term\":\"多巴胺\",\"proposedStyles\":[\"休闲\"],\"styles\":[\"休闲\",\"运动\"]}"))
                .andRespond(withSuccess("{\"term\":\"多巴胺\",\"approved\":true,\"styles\":[\"休闲\",\"运动\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/style-terms/pending/reject")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"term\":\"多巴胺\",\"proposedStyles\":[\"休闲\"]}", true))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"STYLE_TERM_NOT_FOUND\",\"detail\":\"已被审核\"}"));
        mvc.perform(post("/api/v1/closet/style-terms/pending/approve").with(jwtAs("closet:styleterms:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.approved").value(true));
        mvc.perform(post("/api/v1/closet/style-terms/pending/reject").with(jwtAs("closet:styleterms:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"term\":\"多巴胺\",\"proposedStyles\":[\"休闲\"]}")).andExpect(status().isNotFound());
        server.verify();
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_STYLE_TERM_APPROVE")))
                .anySatisfy(e -> { assertThat(e.getTargetId()).isEqualTo("多巴胺"); assertThat(e.getResult()).isEqualTo("SUCCESS"); assertThat(e.getMetadata()).contains("运动"); });
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_STYLE_TERM_REJECT")))
                .anySatisfy(e -> { assertThat(e.getResult()).isEqualTo("FAILURE"); assertThat(e.getMetadata()).contains("NOT_FOUND"); });
    }

    @Test void styleTermBatchDecidesEveryItemOnItsOwn() throws Exception {
        server.expect(requestTo("http://closet.test/api/manage/v1/style-terms/pending/approve")).andExpect(content().json("{\"term\":\"甲词\"}"))
                .andRespond(withSuccess("{\"approved\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/style-terms/pending/approve")).andExpect(content().json("{\"term\":\"乙词\"}"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"STYLE_TERM_NOT_FOUND\",\"detail\":\"x\"}"));
        String body = "{\"action\":\"APPROVE\",\"items\":[{\"term\":\"甲词\",\"proposedStyles\":[\"休闲\"]},{\"term\":\"乙词\",\"proposedStyles\":[\"甜美\"]}]}";
        mvc.perform(post("/api/v1/closet/style-terms/pending/batch").with(jwtAs("closet:styleterms:read")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/closet/style-terms/pending/batch").with(jwtAs("closet:styleterms:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.succeeded").value(1)).andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.results[0].ok").value(true)).andExpect(jsonPath("$.data.results[1].ok").value(false))
                .andExpect(jsonPath("$.data.results[1].code").value("NOT_FOUND"));
        server.verify();
        for (String invalid : java.util.List.of("{\"action\":\"DELETE\",\"items\":[{\"term\":\"x\",\"proposedStyles\":[\"a\"]}]}", "{\"action\":\"APPROVE\",\"items\":[]}", "{\"items\":[{\"term\":\"x\",\"proposedStyles\":[\"a\"]}]}"))
            mvc.perform(post("/api/v1/closet/style-terms/pending/batch").with(jwtAs("closet:styleterms:write")).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_STYLE_TERM_APPROVE") && "甲词".equals(e.getTargetId()))).isNotEmpty();
    }

    @Test void securityAuditPermissionAndMenuAreSeeded() {
        assertThat(permissions.findByCode("closet:security:read")).isPresent();
        var menu = menus.findByCode("closet-security-audit").orElseThrow();
        assertThat(menu.getRoutePath()).isEqualTo("/closet/security-audit");
        assertThat(menu.getComponentKey()).isEqualTo("closet-security-audit");
        assertThat(menu.getRequiredPermission()).isEqualTo("closet:security:read");
        assertThat(roles.findAllWithPermissions().stream().filter(role -> role.getCode().equals("SUPER_ADMIN"))
                .findFirst().orElseThrow().getPermissions()).extracting(permission -> permission.getCode())
                .contains("closet:security:read");
    }

    @Test void securityAuditIsZeroBasedWhitelistedAndKeepsContractFields() throws Exception {
        mvc.perform(get("/api/v1/closet/security-audit")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/closet/security-audit").with(jwtAs("closet:user:read"))).andExpect(status().isForbidden());
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/security-audit?")))
                .andExpect(request -> capture(request))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"ae-1\",\"occurredAtMs\":1,\"category\":\"AUTH\",\"source\":\"ACCOUNT_EVENT\","
                        + "\"type\":\"LOGIN_FAILED\",\"subject\":\"~0123456789abcdef\",\"actor\":\"-\",\"result\":null,\"detail\":null}],"
                        + "\"total\":41,\"page\":3,\"size\":20}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/security-audit?page=2&from=2026-10-01&to=2026-10-31&username=alice&category=AUTH&type=LOGIN_FAILED")
                        .with(jwtAs("closet:security:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(41)).andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.items[0].subject").value("~0123456789abcdef"))
                .andExpect(jsonPath("$.data.items[0].source").value("ACCOUNT_EVENT"));
        server.verify();
        assertThat(recorded.get().rawQuery()).contains("page=3", "size=20", "category=AUTH", "type=LOGIN_FAILED", "username=alice");
        assertThat(recorded.get().signature()).isNotBlank();
        for (String query : java.util.List.of("category=OTHER", "type=bad-type", "username=x", "from=2026-02-30",
                "unexpected=1", "category=AUTH&category=MODEL", "size=101", "page=-1"))
            mvc.perform(get("/api/v1/closet/security-audit?" + query).with(jwtAs("closet:security:read"))).andExpect(status().isBadRequest());
    }

    @Test void integrityCheckIsSignedPermissionProtectedAndAuditedWithStatusOnly() throws Exception {
        mvc.perform(get("/api/v1/closet/security-audit/integrity").with(jwtAs("closet:ops:read"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/closet/security-audit/integrity?x=1").with(jwtAs("closet:security:read"))).andExpect(status().isBadRequest());
        server.expect(requestTo("http://closet.test/api/manage/v1/security-audit/integrity"))
                .andExpect(request -> capture(request))
                .andRespond(withSuccess("{\"status\":\"TAMPERED\",\"sealedDays\":3,\"verifiedDays\":3,\"expiredDays\":0,\"unsealedDays\":0,"
                        + "\"latestDay\":\"2026-10-30\",\"latestDigest\":\"abc\",\"problems\":[{\"day\":\"2026-10-29\",\"kind\":\"ROWS_MODIFIED\",\"message\":\"secret-detail\"}]}",
                        MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/security-audit/integrity").with(jwtAs("closet:security:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("TAMPERED"))
                .andExpect(jsonPath("$.data.problems[0].kind").value("ROWS_MODIFIED"));
        server.verify();
        assertThat(recorded.get().signature()).isNotBlank();
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_SECURITY_AUDIT_INTEGRITY_CHECKED")))
                .anySatisfy(e -> { assertThat(e.getResult()).isEqualTo("SUCCESS"); assertThat(e.getMetadata()).contains("TAMPERED").doesNotContain("secret-detail"); });
    }

    @Test void assistantTokenBudgetOverviewIsAnOpsRead() throws Exception {
        mvc.perform(get("/api/v1/closet/ops/assistant-token-budget").with(jwtAs("closet:user:read"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/closet/ops/assistant-token-budget?x=1").with(jwtAs("closet:ops:read"))).andExpect(status().isBadRequest());
        server.expect(requestTo("http://closet.test/api/manage/v1/ops/assistant-token-budget"))
                .andRespond(withSuccess("{\"site\":{\"limit\":0,\"used\":12},\"userDefault\":{\"limit\":3000000},\"resetAtMs\":9}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/ops/assistant-token-budget").with(jwtAs("closet:ops:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.site.used").value(12))
                .andExpect(jsonPath("$.data.userDefault.limit").value(3000000));
        server.verify();
    }

    @Test void userTokenBudgetReadsWithUserReadAndWritesWithQuotaWriteAlwaysSendingTheKey() throws Exception {
        String root = "http://closet.test/api/manage/v1/users/alice/token-budget";
        String budget = "{\"limit\":500,\"used\":3,\"overridden\":true,\"defaultLimit\":3000000,\"resetAtMs\":9}";
        mvc.perform(get("/api/v1/closet/users/alice/token-budget").with(jwtAs("closet:ops:read"))).andExpect(status().isForbidden());
        mvc.perform(request(HttpMethod.PUT, "/api/v1/closet/users/alice/token-budget").with(jwtAs("closet:user:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"dailyTokenLimit\":500}")).andExpect(status().isForbidden());
        for (String invalid : java.util.List.of("{\"dailyTokenLimit\":0}", "{\"dailyTokenLimit\":1000000001}"))
            mvc.perform(request(HttpMethod.PUT, "/api/v1/closet/users/alice/token-budget").with(jwtAs("closet:quota:write"))
                    .contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        server.expect(requestTo(root)).andExpect(method(HttpMethod.GET)).andRespond(withSuccess(budget, MediaType.APPLICATION_JSON));
        server.expect(requestTo(root)).andExpect(method(HttpMethod.PUT)).andExpect(content().json("{\"dailyTokenLimit\":500}", true))
                .andRespond(withSuccess(budget, MediaType.APPLICATION_JSON));
        server.expect(requestTo(root)).andExpect(method(HttpMethod.PUT)).andExpect(content().string("{\"dailyTokenLimit\":null}"))
                .andRespond(withSuccess(budget.replace("true", "false"), MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/users/alice/token-budget").with(jwtAs("closet:user:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.limit").value(500));
        mvc.perform(request(HttpMethod.PUT, "/api/v1/closet/users/alice/token-budget").with(jwtAs("closet:quota:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"dailyTokenLimit\":500}")).andExpect(status().isOk());
        mvc.perform(request(HttpMethod.PUT, "/api/v1/closet/users/alice/token-budget").with(jwtAs("closet:quota:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"dailyTokenLimit\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.overridden").value(false));
        server.verify();
        var changes = auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_USER_TOKEN_BUDGET_CHANGED")).toList();
        assertThat(changes).anySatisfy(e -> assertThat(e.getMetadata()).contains("alice", "500"))
                .anySatisfy(e -> assertThat(e.getMetadata()).contains("DEFAULT"));
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
