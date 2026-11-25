package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yumg.starter.entities.AuditEvent;
import com.yumg.starter.entities.NavigationMenuType;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner;
import com.yumg.starter.modules.navigation.infrastructure.NavigationMenuRepository;
import com.yumg.starter.modules.rbac.infrastructure.PermissionRepository;
import com.yumg.starter.modules.rbac.infrastructure.RoleRepository;
import com.yumg.starter.modules.security.infrastructure.AuditEventRepository;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;

/** Outfit sample management proxy (management API 1.11). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:target/closet-sample-api-test.db",
        "app.integrations.closet.enabled=true",
        "app.integrations.closet.key-id=test-key",
        "app.integrations.closet.secret=0123456789abcdef0123456789abcdef",
        "app.integrations.closet.connect-timeout=1s",
        "app.integrations.closet.read-timeout=150ms",
        "app.runtime.component-startup-probe.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClosetSampleApiTest {
    private static final String UPSTREAM = "http://closet.test/api/manage/v1";
    private static final String GATEWAY = "/api/v1/closet";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ID = "s-1";
    private static final AtomicInteger clientSequence = new AtomicInteger();

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
        server.reset();
        String clientIp = "198.51.100." + clientSequence.incrementAndGet();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").with(request -> { request.setRemoteAddr(clientIp); return request; })).build();
    }

    @Test void permissionsAndMenuAreSeeded() {
        for (String code : java.util.List.of("closet:samples:read", "closet:samples:write")) assertThat(permissions.findByCode(code)).isPresent();
        assertThat(roles.findAllWithPermissions().stream().filter(role -> role.getCode().equals("SUPER_ADMIN"))
                .findFirst().orElseThrow().getPermissions()).extracting(permission -> permission.getCode())
                .contains("closet:samples:read", "closet:samples:write");
        var menu = menus.findByCode("closet-samples").orElseThrow();
        assertThat(menu.getTitle()).isEqualTo("整套样本");
        assertThat(menu.getRoutePath()).isEqualTo("/closet/samples");
        assertThat(menu.getComponentKey()).isEqualTo("closet-samples");
        assertThat(menu.getRequiredPermission()).isEqualTo("closet:samples:read");
        assertThat(menu.getParentId()).isEqualTo(menus.findByCode("closet").orElseThrow().getId());
    }

    record Route(HttpMethod method, String path, String upstream, String allowed, String denied, String body, int status) {
        @Override public String toString() { return method + " " + path; }
    }

    static Stream<Route> routes() {
        return Stream.of(
                new Route(HttpMethod.GET, "/samples", "/samples?limit=200", "closet:samples:read", "closet:samples:write", null, 200),
                new Route(HttpMethod.GET, "/samples/stats", "/samples/stats", "closet:samples:read", "closet:ops:read", null, 200),
                new Route(HttpMethod.GET, "/samples/" + ID, "/samples/" + ID, "closet:samples:read", "closet:ops:read", null, 200),
                new Route(HttpMethod.PUT, "/samples/" + ID, "/samples/" + ID, "closet:samples:write", "closet:samples:read", "{\"presentation\":\"NEUTRAL\",\"items\":[]}", 200),
                new Route(HttpMethod.POST, "/samples/" + ID + "/extract", "/samples/" + ID + "/extract", "closet:samples:write", "closet:samples:read", null, 200),
                new Route(HttpMethod.POST, "/samples/" + ID + "/review", "/samples/" + ID + "/review", "closet:samples:write", "closet:samples:read", "{\"decision\":\"APPROVED\"}", 200),
                new Route(HttpMethod.POST, "/samples/" + ID + "/template-draft", "/samples/" + ID + "/template-draft", "closet:samples:write", "closet:samples:read", "{\"kind\":\"TREND\"}", 200),
                new Route(HttpMethod.DELETE, "/samples/" + ID, "/samples/" + ID, "closet:samples:write", "closet:samples:read", null, 204));
    }

    @ParameterizedTest @MethodSource("routes")
    void everyRouteChecksItsPermissionAndForwardsSigned(Route route) throws Exception {
        var forbidden = request(route.method(), GATEWAY + route.path()).with(jwtAs(route.denied()));
        if (route.body() != null) forbidden.contentType(MediaType.APPLICATION_JSON).content(route.body());
        mvc.perform(forbidden).andExpect(status().isForbidden());
        var expectation = server.expect(requestTo(UPSTREAM + route.upstream())).andExpect(method(route.method()))
                .andExpect(req -> assertThat(req.getHeaders().getFirst("X-Closet-Signature")).isNotBlank())
                .andExpect(req -> { if (route.method() != HttpMethod.GET) assertThat(req.getHeaders().getFirst("X-Operator")).isEqualTo("admin"); });
        if (route.body() != null) expectation = expectation.andExpect(content().json(route.body()));
        if (route.status() == 204) expectation.andRespond(withNoContent());
        else expectation.andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        var allowed = request(route.method(), GATEWAY + route.path()).with(jwtAs(route.allowed()));
        if (route.body() != null) allowed.contentType(MediaType.APPLICATION_JSON).content(route.body());
        var result = mvc.perform(allowed).andExpect(status().is(route.status()));
        if (route.status() != 204) result.andExpect(jsonPath("$.data.ok").value(true));
        server.verify();
    }

    @Test void listFiltersAreValidatedAndForwarded() throws Exception {
        server.expect(requestTo(UPSTREAM + "/samples?status=EXTRACTED&limit=50"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        mvc.perform(get(GATEWAY + "/samples?status=EXTRACTED&limit=50").with(jwtAs("closet:samples:read"))).andExpect(status().isOk());
        server.verify();
        for (String query : java.util.List.of("?limit=0", "?limit=501", "?status=bad-status"))
            mvc.perform(get(GATEWAY + "/samples" + query).with(jwtAs("closet:samples:read"))).andExpect(status().isBadRequest());
        mvc.perform(get(GATEWAY + "/samples/bad%20id").with(jwtAs("closet:samples:read"))).andExpect(status().isBadRequest());
        mvc.perform(post(GATEWAY + "/samples/" + ID + "/review").with(jwtAs("closet:samples:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"MAYBE\"}")).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.PUT, GATEWAY + "/samples/" + ID).with(jwtAs("closet:samples:write"))
                .contentType(MediaType.APPLICATION_JSON).content("[1]")).andExpect(status().isBadRequest());
        server.verify();
    }

    @Test void multipartUploadIsForwardedAsTheSignedRawBody() throws Exception {
        byte[] body = ("--bnd\r\nContent-Disposition: form-data; name=\"image\"; filename=\"a.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"
                + "JPEGDATA\r\n--bnd--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(post(GATEWAY + "/samples").with(jwtAs("closet:samples:read")).contentType("multipart/form-data; boundary=bnd").content(body))
                .andExpect(status().isForbidden());
        server.expect(requestTo(UPSTREAM + "/samples")).andExpect(method(HttpMethod.POST)).andExpect(content().bytes(body))
                .andExpect(req -> {
                    assertThat(req.getHeaders().getContentType().toString()).startsWith("multipart/form-data");
                    assertThat(req.getHeaders().getFirst("X-Operator")).isEqualTo("admin");
                    assertThat(req.getHeaders().getFirst("X-Closet-Signature")).isNotBlank();
                }).andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"s-2\",\"hasImage\":true}"));
        mvc.perform(post(GATEWAY + "/samples").with(jwtAs("closet:samples:write")).contentType("multipart/form-data; boundary=bnd").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value("s-2"));
        server.verify();
        mvc.perform(post(GATEWAY + "/samples").with(jwtAs("closet:samples:write")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        var audits = auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_SAMPLE_UPLOAD")).toList();
        assertThat(audits).isNotEmpty().allSatisfy(e -> assertThat(e.getTargetType()).isEqualTo("ClosetSample"));
    }

    @Test void imageIsNoStoreAndMissingOriginalIs404() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        server.expect(requestTo(UPSTREAM + "/samples/" + ID + "/image"))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));
        mvc.perform(get(GATEWAY + "/samples/" + ID + "/image").with(jwtAs("closet:samples:read")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Type", "image/png"));
        server.verify();
        server.reset();
        server.expect(requestTo(UPSTREAM + "/samples/" + ID + "/image"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"SAMPLE_NOT_FOUND\",\"detail\":\"gone\"}"));
        mvc.perform(get(GATEWAY + "/samples/" + ID + "/image").with(jwtAs("closet:samples:read"))).andExpect(status().isNotFound());
        server.verify();
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtAs(String authority) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt.claim("username", "admin"))
                .authorities(() -> authority);
    }
}
