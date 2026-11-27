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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @Test void presetSourceAuditsAvoidCredentialsAndSessionIdsAndUseDedicatedPermission() throws Exception {
        String root = "/api/v1/closet/presets/source-sessions";
        String upstream = "http://closet.test/api/manage/v1/presets/source-sessions";
        String token = "X".repeat(43);
        String password = "Sensitive-Test-Password";
        assertThat(permissions.findByCode("closet:presets:write")).isPresent();
        assertThat(menus.findByCode("closet-presets")).isPresent();
        assertThat(menus.findByCode("closet-onboarding")).isPresent();
        String credentials = "{\"username\":\"fixture-admin\",\"password\":\"" + password + "\"}";
        mvc.perform(post(root).with(jwtAs("closet:presets:read")).contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isForbidden());
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.POST)).andExpect(content().json(credentials))
                .andRespond(withSuccess("{\"sessionId\":\"" + token + "\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(upstream + "/" + token + "/import")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        mvc.perform(post(root).with(jwtAs("closet:presets:write")).contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sessionId").value(token));
        mvc.perform(post(root + "/" + token + "/import").with(jwtAs("closet:presets:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"garmentIds\":[],\"list\":false}")).andExpect(status().isOk());
        server.verify();
        var recorded = auditEvents.findAll().stream().filter(e -> e.getAction().startsWith("CLOSET_PRESET_SOURCE_")).toList();
        assertThat(recorded).extracting(AuditEvent::getAction).contains("CLOSET_PRESET_SOURCE_ACCESS","CLOSET_PRESET_SOURCE_IMPORT");
        assertThat(recorded).allSatisfy(e -> {
            assertThat(String.valueOf(e.getMetadata()) + e.getTargetId()).doesNotContain(token,password);
        });
    }

    @Test void onboardingCoverUsesRawBytesAndForwardsTheSignedPut() throws Exception {
        String path = "/api/v1/closet/onboarding/style-covers/fresh/image";
        byte[] input = {1, 2, 3, 4};
        server.expect(requestTo("http://closet.test/api/manage/v1/onboarding/style-covers/fresh/image"))
                .andExpect(method(HttpMethod.PUT)).andExpect(content().bytes(input))
                .andRespond(withSuccess("{\"configured\":true}", MediaType.APPLICATION_JSON));
        mvc.perform(request(HttpMethod.PUT,path).with(jwtAs("closet:onboarding:write"))
                .contentType(MediaType.IMAGE_PNG).content(input))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.configured").value(true));
        server.verify();
    }

    @Test void creationPublishRequiresSeparatePermissionAndRejectsUnknownRoutes() throws Exception {
        String publish = "/api/v1/closet/creations/templates/sunlit-flatlay/versions/v1/publish";
        mvc.perform(post(publish).with(jwtAs("closet:creation:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/closet/creations/templates/Bad_Id/versions/v1")
                .with(jwtAs("closet:creation:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/creations/arbitrary/private")
                .with(jwtAs("closet:creation:read"))).andExpect(status().isNotFound());
        server.verify();
    }

    @Test void creationNavigationAndPermissionsAreSeeded() {
        assertThat(roles.findAllWithPermissions().stream().filter(role -> role.getCode().equals("SUPER_ADMIN"))
                .findFirst().orElseThrow().getPermissions()).extracting(permission -> permission.getCode())
                .contains("closet:creation:read", "closet:creation:write", "closet:creation:publish");
        var parent = menus.findByCode("closet-creation").orElseThrow();
        for (String key : java.util.List.of("templates", "usage")) {
            var page = menus.findByCode("closet-creation-" + key).orElseThrow();
            assertThat(page.getParentId()).isEqualTo(parent.getId());
            assertThat(page.getRequiredPermission()).isEqualTo("closet:creation:read");
        }
        var effects = menus.findByCode("closet-creation-templates").orElseThrow();
        assertThat(effects.getTitle()).isEqualTo("创作效果");
        assertThat(effects.getSortOrder()).isZero();
        assertThat(effects.isVisible()).isTrue();
        var guide = menus.findByCode("closet-creation-guide").orElseThrow();
        assertThat(guide.isVisible()).isFalse();
        assertThat(guide.getComponentKey()).isEqualTo("closet-creation-guide");
        assertThat(menus.findByCode("closet-creation-capabilities")).isEmpty();
        assertThat(menus.findByCode("closet-creation-usage").orElseThrow().isVisible()).isTrue();
        var business = menus.findByCode("closet").orElseThrow();
        var showcase = menus.findByCode("closet-creation-showcases").orElseThrow();
        assertThat(showcase.getParentId()).isEqualTo(business.getId());
        assertThat(showcase.getSortOrder()).isEqualTo(57);
        assertThat(showcase.getRequiredPermission()).isEqualTo("closet:showcase:read");
    }

    @Test void creationPublishSignsRequestAndAuditsWithoutBody() throws Exception {
        String path = "/creations/templates/sunlit-flatlay/versions/v1/publish";
        server.expect(requestTo("http://closet.test/api/manage/v1" + path))
                .andExpect(method(HttpMethod.POST)).andExpect(ClosetIntegrationApiTest::capture)
                .andExpect(content().json("{\"revision\":1}"))
                .andRespond(withSuccess("{\"status\":\"PUBLISHED\"}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/v1/closet" + path).with(jwtAs("closet:creation:publish"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        server.verify();
        assertThat(recorded.get().keyId()).isEqualTo("test-key");
        assertThat(recorded.get().signature()).isNotBlank();
        AuditEvent event = auditEvents.findAll().stream()
                .filter(item -> item.getAction().equals("CLOSET_CREATION_VERSION_PUBLISHED"))
                .reduce((first, second) -> second).orElseThrow();
        assertThat(event.getResult()).isEqualTo("SUCCESS");
        assertThat(event.getMetadata()).isNull();
    }

    @Test void creationConflictIsPreserved() throws Exception {
        String path = "/creations/templates/sunlit-flatlay/versions/v1/publish";
        server.expect(requestTo("http://closet.test/api/manage/v1" + path))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"REVISION_CONFLICT\",\"detail\":\"版本已变更，请重新载入\"}"));
        mvc.perform(post("/api/v1/closet" + path).with(jwtAs("closet:creation:publish"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("版本已变更，请重新载入"));
        server.verify();
    }

    private record ShowcaseRoute(HttpMethod method, String path, String permission, String body) {}
    static java.util.stream.Stream<ShowcaseRoute> showcaseRoutes() {
        return java.util.stream.Stream.of(
                new ShowcaseRoute(HttpMethod.POST, "", "write", "{\"uploadId\":\"upload-1\",\"title\":\"Official\",\"description\":\"\",\"sortOrder\":0}"),
                new ShowcaseRoute(HttpMethod.PUT, "/asset-1", "write", "{\"revision\":0,\"title\":\"Official\",\"description\":\"\",\"sortOrder\":0}"),
                new ShowcaseRoute(HttpMethod.POST, "/asset-1/publish", "publish", "{\"revision\":0}"),
                new ShowcaseRoute(HttpMethod.POST, "/asset-1/retire", "publish", "{\"revision\":1}"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("showcaseRoutes")
    void showcaseJsonRoutesRequireSeparatePermissionAndForwardExactBody(ShowcaseRoute route) throws Exception {
        String path = "/creations/showcases" + route.path();
        mvc.perform(request(route.method(), "/api/v1/closet" + path).with(jwtAs("closet:creation:publish"))
                .contentType(MediaType.APPLICATION_JSON).content(route.body())).andExpect(status().isForbidden());
        if (route.permission().equals("publish")) mvc.perform(request(route.method(), "/api/v1/closet" + path)
                .with(jwtAs("closet:showcase:write")).contentType(MediaType.APPLICATION_JSON).content(route.body()))
                .andExpect(status().isForbidden());
        server.expect(requestTo("http://closet.test/api/manage/v1" + path)).andExpect(method(route.method()))
                .andExpect(content().json(route.body())).andRespond(withSuccess("{\"id\":\"asset-1\"}", MediaType.APPLICATION_JSON));
        mvc.perform(request(route.method(), "/api/v1/closet" + path).with(jwtAs("closet:showcase:" + route.permission()))
                .contentType(MediaType.APPLICATION_JSON).content(route.body())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("asset-1"));
        server.verify();
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().startsWith("CLOSET_SHOWCASE_")))
                .allSatisfy(e -> assertThat(java.util.Objects.toString(e.getMetadata(), "")).isIn("", "CONFLICT"));
    }
    @Test void showcaseListConvertsPaginationAndDoesNotRequireCreationPermission() throws Exception {
        String root = "/api/v1/closet/creations/showcases";
        server.expect(req -> {
                    assertThat(req.getURI().getPath()).isEqualTo("/api/manage/v1/creations/showcases");
                    assertThat(req.getURI().getQuery().split("&")).containsExactlyInAnyOrder("page=2", "size=20");
                })
                .andRespond(withSuccess("{\"items\":[],\"total\":21,\"page\":2,\"size\":20}", MediaType.APPLICATION_JSON));
        server.expect(req -> assertThat(req.getURI().getQuery().split("&"))
                        .containsExactlyInAnyOrder("page=1", "size=1"))
                .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":1,\"size\":1}", MediaType.APPLICATION_JSON));
        server.expect(req -> assertThat(req.getURI().getQuery().split("&"))
                        .containsExactlyInAnyOrder("page=1000000", "size=50"))
                .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":1000000,\"size\":50}", MediaType.APPLICATION_JSON));
        mvc.perform(get(root + "?page=1").with(jwtAs("closet:showcase:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.total").value(21))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        mvc.perform(get(root + "?page=0&size=1").with(jwtAs("closet:showcase:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1));
        mvc.perform(get(root + "?size=50&page=999999").with(jwtAs("closet:showcase:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(999999))
                .andExpect(jsonPath("$.data.size").value(50));
        mvc.perform(get(root + "?size=0").with(jwtAs("closet:showcase:read")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(root + "?size=51").with(jwtAs("closet:showcase:read")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(root + "?page=-1").with(jwtAs("closet:showcase:read")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(root + "?page=1000000").with(jwtAs("closet:showcase:read")))
                .andExpect(status().isBadRequest());
        server.verify();
    }
    private record ShowcaseBoundaryCase(String boundary, String contentType, String parsedParameter) {}
    static java.util.stream.Stream<ShowcaseBoundaryCase> showcaseBoundaryCases() {
        return java.util.stream.Stream.of(
                new ShowcaseBoundaryCase("plainBoundary", "multipart/form-data;boundary=plainBoundary", "plainBoundary"),
                new ShowcaseBoundaryCase("quoted boundary", "multipart/form-data; boundary=\"quoted boundary\"", "\"quoted boundary\""));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("showcaseBoundaryCases")
    void showcaseAcceptsValidPngWithPlainAndQuotedBoundary(ShowcaseBoundaryCase boundary) throws Exception {
        var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var pngOutput = new java.io.ByteArrayOutputStream();
        assertThat(javax.imageio.ImageIO.write(image, "png", pngOutput)).isTrue();
        byte[] png = pngOutput.toByteArray();
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png))).isNotNull();
        byte[] raw = multipartPng(boundary.boundary(), png);
        server.expect(requestTo("http://closet.test/api/manage/v1/creations/showcases/uploads"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().bytes(raw))
                .andExpect(req -> {
                    var h = req.getHeaders();
                    assertThat(h.getContentType().getParameter("boundary")).isEqualTo(boundary.parsedParameter());
                    assertThat(h.getFirst("X-Operator")).isEqualTo("admin");
                    var signer = new com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner();
                    String canonical = signer.canonical("POST", req.getURI().getRawPath(), java.util.Map.of(),
                            Long.parseLong(h.getFirst("X-Closet-Timestamp")), h.getFirst("X-Closet-Nonce"),
                            h.getFirst("X-Operator"), h.getFirst("X-Request-Id"), raw);
                    assertThat(h.getFirst("X-Closet-Signature")).isEqualTo(signer.sign(canonical, "0123456789abcdef0123456789abcdef"));
                })
                .andRespond(withSuccess("{\"uploadId\":\"upload-1\",\"hash\":\"hash\",\"width\":1,\"height\":1}", MediaType.APPLICATION_JSON));
        var request = post("/api/v1/closet/creations/showcases/uploads").with(jwtAs("closet:showcase:write"))
                .content(raw).with(servletRequest -> {
                    servletRequest.setContentType(boundary.contentType());
                    return servletRequest;
                });
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.uploadId").value("upload-1"));
        server.verify();
    }

    private static byte[] multipartPng(String boundary, byte[] png) {
        var body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.writeBytes(png);
        body.writeBytes(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"sourceNote\"\r\n\r\n"
                + "Generated valid 1x1 PNG fixture\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    @Test void showcaseMaximumBodyIsOpaqueTransportFixtureAndSignedByteForByte() throws Exception {
        byte[] raw = new byte[12 * 1024 * 1024];
        String boundary = "transportBoundary";
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"transport-fixture.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(prefix, 0, raw, 0, prefix.length);
        byte[] suffix = ("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"sourceNote\"\r\n\r\n"
                + "Opaque max-size transport fixture\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(suffix, 0, raw, raw.length - suffix.length, suffix.length);
        raw[raw.length - suffix.length - 1] = (byte) 255;
        server.expect(requestTo("http://closet.test/api/manage/v1/creations/showcases/uploads"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().bytes(raw))
                .andExpect(req -> {
                    var h = req.getHeaders();
                    assertThat(h.getContentType().getParameter("boundary")).isEqualTo(boundary);
                    assertThat(h.getFirst("X-Operator")).isEqualTo("admin");
                    var signer = new com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner();
                    String canonical = signer.canonical("POST", req.getURI().getRawPath(), java.util.Map.of(),
                            Long.parseLong(h.getFirst("X-Closet-Timestamp")), h.getFirst("X-Closet-Nonce"),
                            h.getFirst("X-Operator"), h.getFirst("X-Request-Id"), raw);
                    assertThat(h.getFirst("X-Closet-Signature")).isEqualTo(signer.sign(canonical, "0123456789abcdef0123456789abcdef"));
                }).andRespond(withSuccess("{\"uploadId\":\"upload-1\",\"hash\":\"hash\",\"width\":240,\"height\":320}", MediaType.APPLICATION_JSON));
        var request = post("/api/v1/closet/creations/showcases/uploads").with(jwtAs("closet:showcase:write"))
                .contentType("multipart/form-data;boundary=" + boundary).content(raw);
        var result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        server.verify();
    }
    @Test void showcaseUploadBoundariesAreLocalAndOnlyExactRouteSkipsMultipartResolver() throws Exception {
        String root = "/api/v1/closet/creations/showcases/uploads";
        for (String type : java.util.List.of("image/png", "multipart/*;boundary=fixture", "multipart/form-data",
                "multipart/form-data;boundary=bad%boundary", "multipart/form-data;boundary=\"trailing \"",
                "multipart/form-data;boundary=\"" + "a".repeat(71) + "\""))
            mvc.perform(post(root).with(jwtAs("closet:showcase:write")).contentType(type).content("body"))
                    .andExpect(status().isBadRequest());
        mvc.perform(post(root).with(jwtAs("closet:showcase:read")).contentType("multipart/form-data;boundary=fixture").content("body"))
                .andExpect(status().isForbidden());
        mvc.perform(post(root).with(jwtAs("closet:showcase:write")).contentType("multipart/form-data;boundary=fixture")
                .content(new byte[12 * 1024 * 1024 + 1])).andExpect(status().isPayloadTooLarge());
        var resolver = context.getBean("multipartResolver", org.springframework.web.multipart.MultipartResolver.class);
        var upload = new org.springframework.mock.web.MockHttpServletRequest("POST", root);
        upload.setContentType("multipart/form-data;boundary=fixture");
        assertThat(resolver.isMultipart(upload)).isFalse();
        upload.setMethod("GET");
        assertThat(resolver.isMultipart(upload)).isTrue();
        upload.setMethod("PUT");
        assertThat(resolver.isMultipart(upload)).isTrue();
        upload.setMethod("POST");
        upload.setRequestURI("/api/v1/users/me/avatar");
        assertThat(resolver.isMultipart(upload)).isTrue();
        upload.setRequestURI(root + "/other");
        assertThat(resolver.isMultipart(upload)).isTrue();
        upload.setRequestURI(root + "/");
        assertThat(resolver.isMultipart(upload)).isTrue();
        upload.setContextPath("/ctx");
        upload.setRequestURI("/ctx" + root);
        assertThat(resolver.isMultipart(upload)).isFalse();
        server.verify();
    }
    @Test void showcaseImageBypassesJsonEnvelopeAndIsNoStore() throws Exception {
        byte[] png = new byte[]{(byte)137, 80, 78, 71, 13, 10, 26, 10};
        mvc.perform(get("/api/v1/closet/creations/showcases/asset-1/image").with(jwtAs("closet:showcase:write")))
                .andExpect(status().isForbidden());
        server.expect(requestTo("http://closet.test/api/manage/v1/creations/showcases/asset-1/image"))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));
        var result = mvc.perform(get("/api/v1/closet/creations/showcases/asset-1/image").with(jwtAs("closet:showcase:read")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(png))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_PNG))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        server.verify();
    }
    @Test void showcaseRejectsPrivateFieldsAndPreservesRevisionConflict() throws Exception {
        String root = "/api/v1/closet/creations/showcases";
        mvc.perform(post(root).with(jwtAs("closet:showcase:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"uploadId\":\"upload-1\",\"title\":\"Official\",\"description\":\"\",\"sortOrder\":0,\"mediaId\":\"private\"}"))
                .andExpect(status().isBadRequest());
        server.expect(requestTo("http://closet.test/api/manage/v1/creations/showcases/asset-1/publish"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"SHOWCASE_REVISION_CONFLICT\",\"detail\":\"Reload revision\"}"));
        mvc.perform(post(root + "/asset-1/publish").with(jwtAs("closet:showcase:publish"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Reload revision"));
        assertThat(menus.findByCode("closet-creation-showcases")).get()
                .extracting(m -> m.getRequiredPermission()).isEqualTo("closet:showcase:read");
        for (String permission : java.util.List.of("read", "write", "publish"))
            assertThat(permissions.findByCode("closet:showcase:" + permission)).isPresent();
        server.verify();
    }

    private record CreationRoute(HttpMethod method, String path, String permission, String body) {}
    static java.util.stream.Stream<CreationRoute> creationRoutes() {
        String template = "/creations/templates/sunlit-flatlay";
        String version = template + "/versions/v1";
        return java.util.stream.Stream.of(
                new CreationRoute(HttpMethod.GET, "/creations/templates", "read", null),
                new CreationRoute(HttpMethod.POST, template + "/versions", "write", "{\"fromVersionId\":null}"),
                new CreationRoute(HttpMethod.GET, version, "read", null),
                new CreationRoute(HttpMethod.PUT, version, "write", "{\"revision\":1}"),
                new CreationRoute(HttpMethod.POST, version + "/verify", "write", "{\"revision\":1,\"evidence\":\"fixture evidence\"}"),
                new CreationRoute(HttpMethod.POST, version + "/publish", "publish", "{\"revision\":1}"),
                new CreationRoute(HttpMethod.POST, template + "/retire", "publish", "{\"revision\":1}"),
                new CreationRoute(HttpMethod.GET, "/creations/generation-options", "read", null));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("creationRoutes")
    void creationRoutesRequireOwnPermissionAndForwardToTheirExactPath(CreationRoute route) throws Exception {
        var denied = request(route.method(), "/api/v1/closet" + route.path())
                .with(jwtAs("system:user:read")).contentType(MediaType.APPLICATION_JSON);
        if (route.body() != null) denied.content(route.body());
        mvc.perform(denied).andExpect(status().isForbidden());
        var expectation = server.expect(requestTo("http://closet.test/api/manage/v1" + route.path()))
                .andExpect(method(route.method())).andExpect(ClosetIntegrationApiTest::capture);
        if (route.body() != null) expectation.andExpect(content().json(route.body()));
        expectation.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var allowed = request(route.method(), "/api/v1/closet" + route.path())
                .with(jwtAs("closet:creation:" + route.permission())).contentType(MediaType.APPLICATION_JSON);
        if (route.body() != null) allowed.content(route.body());
        mvc.perform(allowed).andExpect(status().isOk());
        server.verify();
        assertThat(recorded.get().signature()).isNotBlank();
    }

    @Test void retiredCreationCapabilitiesAreNotExposed() throws Exception {
        mvc.perform(get("/api/v1/closet/creations/capabilities").with(jwtAs("closet:creation:read")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/closet/creations/capabilities").with(jwtAs("closet:creation:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        server.verify();
    }

    @Test void creationUsageRejectsInvalidDateRangeBeforeForwarding() throws Exception {
        mvc.perform(get("/api/v1/closet/creations/usage?from=2026-01-01&to=2026-02-01")
                .with(jwtAs("closet:creation:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/creations/usage?from=2026-01-02&to=2026-01-01")
                .with(jwtAs("closet:creation:read"))).andExpect(status().isBadRequest());
        server.verify();
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
            "ops/status,closet:ops:read", "ops/mail,closet:ops:read", "ops/monitor,closet:ops:read", "dashboard/snapshot,closet:dashboard:read",
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

    @Test void mailUsageForwardsDaysAndRejectsOutOfRangeOrExtraQuery() throws Exception {
        server.expect(requestTo("http://closet.test/api/manage/v1/ops/mail?days=14"))
                .andRespond(withSuccess("{\"enabled\":false}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/ops/mail?days=14").with(jwtAs("closet:ops:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        server.verify();
        for (String query : java.util.List.of("?days=0", "?days=31", "?days=x", "?days=1&days=2", "?x=1"))
            mvc.perform(get("/api/v1/closet/ops/mail" + query).with(jwtAs("closet:ops:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/ops/monitor?x=1").with(jwtAs("closet:ops:read"))).andExpect(status().isBadRequest());
        server.verify();
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
        server.expect(requestTo("http://closet.test/api/manage/v1/users?userKey=aaaabbbbccccdddd&page=1&size=20"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess("{\"items\":[{\"username\":\"alice\",\"userKey\":\"aaaabbbbccccdddd\"}],\"total\":1,\"page\":1,\"size\":20}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/users?userKey=aaaabbbbccccdddd").with(jwtAs("closet:user:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].userKey").value("aaaabbbbccccdddd"));
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
            "users,registeredVia=INVALID", "users,userKey=AAAABBBBCCCCDDDD", "users,userKey=aaaabbbb", "users,userKey=alice", "users,deactivated=yes", "users,sort=invalid", "users,unexpected=x", "users,page=1&page=2", "users,page=2147483647",
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

    @Test void modelsPermissionsAndMenuAreSeeded() {
        for (String permission : java.util.List.of("closet:models:read", "closet:models:write")) assertThat(permissions.findByCode(permission)).isPresent();
        assertThat(menus.findByCode("closet-models").orElseThrow().getRequiredPermission()).isEqualTo("closet:models:read");
        assertThat(roles.findAllWithPermissions().stream().filter(role -> role.getCode().equals("SUPER_ADMIN"))
                .findFirst().orElseThrow().getPermissions()).extracting(permission -> permission.getCode())
                .contains("closet:models:read", "closet:models:write");
    }

    @Test void modelReferencesRequireWriteAndForwardSettingsAndConflictsWithoutAuditingBody() throws Exception {
        String id = "cfg:image:vendor:reference-test";
        String local = "/api/v1/closet/models/entries/" + id + "/references";
        String upstream = "http://closet.test/api/manage/v1/models/entries/cfg%3Aimage%3Avendor%3Areference-test/references";
        String body = "{\"maxReferenceImages\":null,\"overLimit\":\"REJECT\",\"revision\":7}";
        mvc.perform(put(local).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put(local).with(jwtAs("closet:models:read")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.PUT)).andExpect(content().json(body, true))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"references\":{\"limit\":3,\"revision\":8}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.PUT)).andExpect(content().json(body, true))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"MODEL_CONFLICT\",\"detail\":\"stale revision\"}"));
        mvc.perform(put(local).with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.references.revision").value(8));
        mvc.perform(put(local).with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        server.verify();
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_MODELS_ENTRY_REFERENCES_UPDATE")
                && e.getTargetId().equals(id)).toList()).extracting(AuditEvent::getResult).contains("SUCCESS", "FAILURE");
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_MODELS_ENTRY_REFERENCES_UPDATE")
                && e.getTargetId().equals(id))).allSatisfy(e -> assertThat(e.getMetadata()).isEqualTo("{}"));
    }

    @Test void modelTimeoutRequiresWriteAndForwardsSettingsAndErrorsWithoutAuditingBody() throws Exception {
        String id = "cfg:image:vendor:timeout-test";
        String local = "/api/v1/closet/models/entries/" + id + "/timeout";
        String upstream = "http://closet.test/api/manage/v1/models/entries/cfg%3Aimage%3Avendor%3Atimeout-test/timeout";
        String body = "{\"timeoutSeconds\":180,\"revision\":7}";
        String reset = "{\"timeoutSeconds\":null,\"revision\":8}";
        mvc.perform(put(local).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(put(local).with(jwtAs("closet:models:read")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.PUT)).andExpect(content().json(body, true))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"timeout\":{\"seconds\":180,\"custom\":180,\"providerSeconds\":120,\"appliesToChat\":false,\"revision\":8}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.PUT)).andExpect(content().json(reset, true))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"timeout\":{\"seconds\":120,\"custom\":null,\"providerSeconds\":120,\"appliesToChat\":false,\"revision\":9}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.PUT)).andExpect(content().json(body, true))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"MODEL_CONFLICT\",\"detail\":\"stale revision\"}"));
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.PUT)).andExpect(content().json(body, true))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"MODEL_INVALID\",\"detail\":\"unsupported model\"}"));
        mvc.perform(put(local).with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.timeout.seconds").value(180))
                .andExpect(jsonPath("$.data.timeout.revision").value(8));
        mvc.perform(put(local).with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(reset))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.timeout.seconds").value(120))
                .andExpect(jsonPath("$.data.timeout.revision").value(9));
        mvc.perform(put(local).with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mvc.perform(put(local).with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        server.verify();
        var events = auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_MODELS_ENTRY_TIMEOUT_UPDATE")
                && e.getTargetId().equals(id)).toList();
        assertThat(events).extracting(AuditEvent::getResult).containsExactlyInAnyOrder("SUCCESS", "SUCCESS", "FAILURE", "FAILURE");
        assertThat(events).allSatisfy(e -> assertThat(e.getMetadata()).isEqualTo("{}"));
    }

    @Test void existingModelMenuIsMovedFirstAndLegacyIconIsUpgraded() throws Exception {
        var menu = menus.findByCode("closet-models").orElseThrow();
        menu.update(menu.getParentId(), menu.getTitle(), menu.getRoutePath(), menu.getComponentKey(),
                "ri/settings-3-line", 31, menu.getMenuType(), menu.getRequiredPermission(),
                menu.isVisible(), menu.isEnabled(), menu.isKeepAlive());
        menus.save(menu);
        navigationBootstrap.run(null);
        var upgraded = menus.findByCode("closet-models").orElseThrow();
        assertThat(upgraded.getSortOrder()).isZero();
        assertThat(upgraded.getIcon()).isEqualTo("ri/robot-2-line");
        assertThat(menus.findByCode("closet-insights").orElseThrow().getSortOrder()).isGreaterThan(upgraded.getSortOrder());
    }

    @Test void modelUsageRequiresReadPermissionAndForwardsEncodedFiltersWithSignatureWithoutAudit() throws Exception {
        String local = "/api/v1/closet/models/usage";
        long before = auditEvents.count();
        mvc.perform(get(local)).andExpect(status().isUnauthorized());
        mvc.perform(get(local).with(jwtAs("closet:models:write"))).andExpect(status().isForbidden());
        mvc.perform(get(local).with(jwtAs("closet:feedback:read"))).andExpect(status().isForbidden());

        var query = java.util.Map.of(
                "from", java.util.List.of("2026-11-20"), "to", java.util.List.of("2026-11-26"),
                "capability", java.util.List.of("VISION"), "providerId", java.util.List.of("阿里 & cloud"),
                "model", java.util.List.of("qwen/视觉+v1"));
        server.expect(requestTo("http://closet.test/api/manage/v1/models/usage?from=2026-11-20&to=2026-11-26"
                        + "&capability=VISION&providerId=%E9%98%BF%E9%87%8C%20%26%20cloud&model=qwen%2F%E8%A7%86%E8%A7%89%2Bv1"))
                .andExpect(method(HttpMethod.GET)).andExpect(req -> {
                    var h = req.getHeaders();
                    var signer = new com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner();
                    String canonical = signer.canonical("GET", req.getURI().getRawPath(), query,
                            Long.parseLong(h.getFirst("X-Closet-Timestamp")), h.getFirst("X-Closet-Nonce"),
                            h.getFirst("X-Operator"), h.getFirst("X-Request-Id"), new byte[0]);
                    assertThat(h.getFirst("X-Closet-Signature")).isEqualTo(signer.sign(canonical, "0123456789abcdef0123456789abcdef"));
                }).andRespond(withSuccess("{\"totals\":{\"calls\":3},\"byModel\":[{\"model\":\"qwen\",\"timeoutSeconds\":180}],\"recent\":[{\"timeoutSeconds\":120}]}", MediaType.APPLICATION_JSON));
        mvc.perform(get(local).with(jwtAs("closet:models:read"))
                        .param("from", "2026-11-20").param("to", "2026-11-26").param("capability", "VISION")
                        .param("providerId", "阿里 & cloud").param("model", "qwen/视觉+v1").param("ignored", "value"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totals.calls").value(3))
                .andExpect(jsonPath("$.data.byModel[0].model").value("qwen"))
                .andExpect(jsonPath("$.data.byModel[0].timeoutSeconds").value(180))
                .andExpect(jsonPath("$.data.recent[0].timeoutSeconds").value(120));
        server.verify();
        assertThat(auditEvents.count()).isEqualTo(before);
    }

    @Test void modelUsagePassesThroughDefaultsAndUpstreamErrorsWithoutAudit() throws Exception {
        String local = "/api/v1/closet/models/usage";
        String upstream = "http://closet.test/api/manage/v1/models/usage";
        long before = auditEvents.count();
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"totals\":{\"calls\":0}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(upstream + "?from=invalid")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"INVALID_PARAMETER\",\"detail\":\"invalid date\"}"));
        mvc.perform(get(local).with(jwtAs("closet:models:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totals.calls").value(0));
        mvc.perform(get(local).with(jwtAs("closet:models:read")).param("from", "invalid"))
                .andExpect(status().isBadRequest());
        server.verify();
        assertThat(auditEvents.count()).isEqualTo(before);
    }

    @Test void modelsForwardSignedRequestsAndAuditWithoutSecrets() throws Exception {
        String secret = "secret-model-key-123";
        String body = "{\"id\":\"vendor\",\"name\":\"Vendor\",\"kind\":\"OPENAI_COMPATIBLE\",\"baseUrl\":\"https://example.com/v1\",\"apiKey\":\"" + secret + "\"}";
        mvc.perform(get("/api/v1/closet/models")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/closet/models").with(jwtAs("closet:feedback:read"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/closet/models/providers").with(jwtAs("closet:models:read")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        server.expect(requestTo("http://closet.test/api/manage/v1/models")).andExpect(method(HttpMethod.GET))
                .andExpect(request -> capture(request)).andRespond(withSuccess("{\"providers\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/models/providers")).andExpect(method(HttpMethod.POST)).andExpect(content().json(body, true))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"vendor\",\"apiKeyHint\":\"****y-123\"}"));
        server.expect(requestTo("http://closet.test/api/manage/v1/models/providers/vendor")).andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"MODEL_CONFLICT\",\"detail\":\"conflict\"}"));
        mvc.perform(get("/api/v1/closet/models").with(jwtAs("closet:models:read"))).andExpect(status().isOk());
        assertThat(recorded.get().signature()).isNotBlank();
        mvc.perform(post("/api/v1/closet/models/providers").with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(patch("/api/v1/closet/models/providers/vendor").with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        server.verify();
        var events = auditEvents.findAll().stream().filter(e -> e.getAction().startsWith("CLOSET_MODELS_")).toList();
        assertThat(events).extracting(AuditEvent::getAction).contains("CLOSET_MODELS_PROVIDER_CREATE", "CLOSET_MODELS_PROVIDER_UPDATE");
        assertThat(events).anySatisfy(e -> assertThat(e.getResult()).isEqualTo("FAILURE"));
        assertThat(events).allSatisfy(e -> assertThat(String.valueOf(e.getMetadata()) + e.getTargetId()).doesNotContain(secret));
    }

    @Test void modelsForwardEntryRoutingAndOperations() throws Exception {
        String root = "http://closet.test/api/manage/v1/models";
        String local = "/api/v1/closet/models";
        String body = "{\"revision\":1}";
        for (String path : java.util.List.of("/providers/vendor/probe", "/entries/entry/test", "/entries/entry/resume"))
            server.expect(requestTo(root + path)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/entries")).andExpect(method(HttpMethod.POST)).andExpect(content().json(body, true))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{\"id\":\"entry\"}"));
        server.expect(requestTo(root + "/entries/entry")).andExpect(method(HttpMethod.PATCH)).andExpect(content().json(body, true)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(root + "/routing/VISION")).andExpect(method(HttpMethod.PUT)).andExpect(content().json(body, true)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        for (String path : java.util.List.of("/entries/entry", "/providers/vendor"))
            server.expect(requestTo(root + path)).andExpect(method(HttpMethod.DELETE)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(root + "/routing/VISION")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"active\":\"cfg:vision:vendor:model\",\"fallback\":null,\"configured\":false,\"revision\":0}", MediaType.APPLICATION_JSON));
        mvc.perform(post(local + "/providers/vendor/probe").with(jwtAs("closet:models:write"))).andExpect(status().isOk());
        mvc.perform(post(local + "/entries").with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value("entry"));
        mvc.perform(post(local + "/entries/entry/test").with(jwtAs("closet:models:write"))).andExpect(status().isOk());
        mvc.perform(post(local + "/entries/entry/resume").with(jwtAs("closet:models:write"))).andExpect(status().isOk());
        mvc.perform(patch(local + "/entries/entry").with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(put(local + "/routing/VISION").with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        for (String path : java.util.List.of("/entries/entry", "/providers/vendor"))
            mvc.perform(delete(local + path).with(jwtAs("closet:models:write"))).andExpect(status().isNoContent());
        mvc.perform(delete(local + "/routing/VISION").with(jwtAs("closet:models:write")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value("cfg:vision:vendor:model"))
                .andExpect(jsonPath("$.data.configured").value(false));
        server.verify();
    }

    @Test void modelPoolsRequireWriteAndForwardRevisionWithoutAuditingBody() throws Exception {
        String local = "/api/v1/closet/models/pools/VISION";
        String body = "{\"members\":[],\"revision\":4}";
        mvc.perform(put(local).with(jwtAs("closet:models:read")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        server.expect(requestTo("http://closet.test/api/manage/v1/models/pools/VISION"))
                .andExpect(method(HttpMethod.PUT)).andExpect(content().json(body, true))
                .andExpect(ClosetIntegrationApiTest::capture).andRespond(withSuccess("{\"revision\":5}", MediaType.APPLICATION_JSON));
        mvc.perform(put(local).with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(5));
        assertThat(recorded.get().signature()).isNotBlank();
        server.verify();
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_MODELS_POOL_UPDATE")).toList())
                .isNotEmpty().allSatisfy(e -> assertThat(String.valueOf(e.getMetadata())).doesNotContain(body));
    }

    @Test void portraitRoutingForwardsRevisionAndResetResponse() throws Exception {
        String local = "/api/v1/closet/models/routing/PORTRAIT";
        String upstream = "http://closet.test/api/manage/v1/models/routing/PORTRAIT";
        String body = "{\"active\":\"cfg:image:vendor:model\",\"fallback\":null,\"revision\":3}";
        mvc.perform(put(local).with(jwtAs("closet:models:read")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(delete(local).with(jwtAs("closet:models:read"))).andExpect(status().isForbidden());
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.PUT)).andExpect(content().json(body, true))
                .andExpect(ClosetIntegrationApiTest::capture)
                .andRespond(withSuccess("{\"active\":\"cfg:image:vendor:model\",\"revision\":4}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{\"active\":null,\"configured\":false,\"followsImage\":true,\"revision\":0}", MediaType.APPLICATION_JSON));
        mvc.perform(put(local).with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(4));
        assertThat(recorded.get().signature()).isNotBlank();
        mvc.perform(delete(local).with(jwtAs("closet:models:write")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.followsImage").value(true))
                .andExpect(jsonPath("$.data.revision").value(0));
        server.verify();
    }

    @Test void modelIdsWithColonsAreEncodedAndModelErrorsKeepTheirStatuses() throws Exception {
        String local = "/api/v1/closet/models";
        String id = "cfg:vision:yumg:gpt-6-luna";
        String upstream = "http://closet.test/api/manage/v1/models/entries/cfg%3Avision%3Ayumg%3Agpt-6-luna";
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json("{\"revision\":1}", true))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"MODEL_SOURCE_READ_ONLY\",\"detail\":\"read only\"}"));
        server.expect(requestTo(upstream)).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"MODEL_IN_USE\",\"detail\":\"in use\"}"));
        server.expect(requestTo(upstream + "/resume")).andExpect(method(HttpMethod.POST))
                .andExpect(req -> {
                    var h = req.getHeaders();
                    var signer = new com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner();
                    String canonical = signer.canonical("POST", req.getURI().getRawPath(), java.util.Map.of(),
                            Long.parseLong(h.getFirst("X-Closet-Timestamp")), h.getFirst("X-Closet-Nonce"),
                            h.getFirst("X-Operator"), h.getFirst("X-Request-Id"), new byte[0]);
                    assertThat(h.getFirst("X-Closet-Signature")).isEqualTo(signer.sign(canonical, "0123456789abcdef0123456789abcdef"));
                })
                .andRespond(withSuccess("{\"id\":\"" + id + "\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(upstream + "/test")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"MODEL_NOT_FOUND\",\"detail\":\"not found\"}"));
        mvc.perform(patch(local + "/entries/" + id).with(jwtAs("closet:models:write")).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isConflict());
        mvc.perform(delete(local + "/entries/" + id).with(jwtAs("closet:models:write"))).andExpect(status().isConflict());
        mvc.perform(post(local + "/entries/" + id + "/resume").with(jwtAs("closet:models:write")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(post(local + "/entries/" + id + "/test").with(jwtAs("closet:models:write"))).andExpect(status().isNotFound());
        mvc.perform(delete(local + "/routing/INVALID").with(jwtAs("closet:models:write"))).andExpect(status().isBadRequest());
        server.verify();
    }

    @Test void modelEntryIdAcceptsContractMaximumLength() throws Exception {
        String id = "a".repeat(300);
        String local = "/api/v1/closet/models/entries/";
        server.expect(requestTo("http://closet.test/api/manage/v1/models/entries/" + id + "/resume"))
                .andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{\"id\":\"" + id + "\"}", MediaType.APPLICATION_JSON));
        mvc.perform(post(local + id + "/resume").with(jwtAs("closet:models:write")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(post(local + id + "a/resume").with(jwtAs("closet:models:write"))).andExpect(status().isNotFound());
        server.verify();
    }

    @Test void feedbackPermissionsAndMenuAreSeeded() {
        for (String permission : java.util.List.of("closet:feedback:read", "closet:feedback:write")) assertThat(permissions.findByCode(permission)).isPresent();
        assertThat(menus.findByCode("closet-feedback").orElseThrow().getRequiredPermission()).isEqualTo("closet:feedback:read");
        assertThat(roles.findAllWithPermissions().stream().filter(role -> role.getCode().equals("SUPER_ADMIN"))
                .findFirst().orElseThrow().getPermissions()).extracting(permission -> permission.getCode())
                .contains("closet:feedback:read", "closet:feedback:write");
    }

    @Test void feedbackListIsSignedZeroBasedFilteredAndPermissionProtected() throws Exception {
        mvc.perform(get("/api/v1/closet/feedback")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/closet/feedback").with(jwtAs("closet:styleterms:read"))).andExpect(status().isForbidden());
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/manage/v1/feedback?")))
                .andExpect(request -> capture(request))
                .andRespond(withSuccess("{\"items\":[{\"id\":\"00000000-0000-4000-8000-000000000001\",\"submitter\":\"ACCOUNT\",\"username\":\"alice\",\"status\":\"NEW\"}],"
                        + "\"total\":21,\"page\":2,\"size\":10,\"counts\":{\"NEW\":5,\"PROCESSING\":1,\"RESOLVED\":12,\"CLOSED\":3}}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/feedback?page=1&size=10&status=NEW&q=%E9%BB%91%E5%B1%8F&submitter=").with(jwtAs("closet:feedback:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.totalPages").value(3)).andExpect(jsonPath("$.data.items[0].username").value("alice"))
                .andExpect(jsonPath("$.data.counts.RESOLVED").value(12));
        assertThat(recorded.get().rawQuery()).contains("page=2", "size=10", "status=NEW", "q=").doesNotContain("submitter");
        assertThat(recorded.get().signature()).isNotBlank(); server.verify();
        mvc.perform(get("/api/v1/closet/feedback?username=alice").with(jwtAs("closet:feedback:read"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/feedback?size=101").with(jwtAs("closet:feedback:read"))).andExpect(status().isBadRequest());
    }

    @Test void feedbackEditsDeletesAndSwitchNeedWritePermissionAndAuditWithoutText() throws Exception {
        String id = "00000000-0000-4000-8000-000000000002";
        String body = "{\"revision\":3,\"status\":\"RESOLVED\",\"reply\":\"已经修好了\",\"note\":\"内部备注\"}";
        mvc.perform(patch("/api/v1/closet/feedback/" + id).with(jwtAs("closet:feedback:read")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        for (String invalid : java.util.List.of("{}", "{\"revision\":1,\"status\":\"DONE\"}", "{\"revision\":1,\"reply\":\"" + "字".repeat(1001) + "\"}"))
            mvc.perform(patch("/api/v1/closet/feedback/" + id).with(jwtAs("closet:feedback:write")).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        server.expect(requestTo("http://closet.test/api/manage/v1/feedback/" + id)).andExpect(method(HttpMethod.PATCH))
                .andExpect(content().json(body, true))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"status\":\"RESOLVED\",\"revision\":4}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/feedback/" + id)).andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"FEEDBACK_REPLY_UNAVAILABLE\",\"detail\":\"这条反馈没有附带账号，没法回复。\"}"));
        server.expect(requestTo("http://closet.test/api/manage/v1/feedback/" + id)).andExpect(method(HttpMethod.DELETE)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo("http://closet.test/api/manage/v1/feedback/settings")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"anonymousEnabled\":false,\"revision\":2}", true))
                .andRespond(withSuccess("{\"anonymousEnabled\":false,\"defaultAnonymousEnabled\":true,\"revision\":3,\"updatedAtMs\":1}", MediaType.APPLICATION_JSON));
        mvc.perform(patch("/api/v1/closet/feedback/" + id).with(jwtAs("closet:feedback:write")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(4));
        mvc.perform(patch("/api/v1/closet/feedback/" + id).with(jwtAs("closet:feedback:write")).contentType(MediaType.APPLICATION_JSON).content("{\"revision\":4,\"reply\":\"回复\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("这条反馈没有附带账号，没法回复。"));
        mvc.perform(delete("/api/v1/closet/feedback/" + id).with(jwtAs("closet:feedback:write"))).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/closet/feedback/settings").with(jwtAs("closet:feedback:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"anonymousEnabled\":false,\"revision\":2}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.anonymousEnabled").value(false));
        server.verify();
        var events = auditEvents.findAll().stream().filter(e -> e.getAction().startsWith("CLOSET_FEEDBACK_")).toList();
        assertThat(events).extracting(AuditEvent::getAction).contains("CLOSET_FEEDBACK_UPDATE", "CLOSET_FEEDBACK_DELETE", "CLOSET_FEEDBACK_SETTINGS");
        assertThat(events).anySatisfy(e -> { assertThat(e.getResult()).isEqualTo("FAILURE"); assertThat(e.getMetadata()).contains("CONFLICT"); });
        assertThat(events).allSatisfy(e -> assertThat(String.valueOf(e.getMetadata())).doesNotContain("修好", "内部备注", "回复"));
        mvc.perform(delete("/api/v1/closet/feedback/not-an-id").with(jwtAs("closet:feedback:write"))).andExpect(status().isNotFound());
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
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> tryOnOperations() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("GET", "/settings", "read", "settings", ""),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/vendors", "read", "vendors", ""),
                org.junit.jupiter.params.provider.Arguments.of("PUT", "/settings", "write", "updateSettings", "{\"revision\":4,\"value\":{\"mode\":\"FOLLOW_USER\",\"defaultEnabled\":false,\"dailySeconds\":180,\"dailyPoints\":1800,\"totalPoints\":3000,\"sessionSeconds\":180,\"startupSeconds\":30,\"firstFrameSeconds\":20,\"queueSeconds\":60,\"offerSeconds\":10,\"recordingSeconds\":60,\"recordingBytes\":104857600,\"storageBytes\":1073741824,\"accountConcurrency\":1,\"accountGuardPoints\":60}}"),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/settings/reset", "write", "resetSettings", "{\"revision\":4}"),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/users/alice/policy", "read", "userPolicy", ""),
                org.junit.jupiter.params.provider.Arguments.of("PUT", "/users/alice/policy", "write", "updateUserPolicy", "{\"accessMode\":\"INHERIT\",\"dailySeconds\":null,\"startupFailureLimit\":0}"),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/users/alice/usage", "read", "userUsage", ""),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/users/alice/startup-failures/reset", "write", "resetStartupFailures", ""),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/accounts", "read", "accounts", ""),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/accounts", "credentials", "registerAccount", "{\"name\":\"integration-account\",\"environment\":\"china\",\"apiKey\":\"uk-testsecret12345\"}"),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/accounts/12345678-1234-4234-8234-123456789abc", "read", "account", ""),
                org.junit.jupiter.params.provider.Arguments.of("PATCH", "/accounts/12345678-1234-4234-8234-123456789abc", "write", "updateAccount", "{\"name\":\"renamed\",\"accepting\":false,\"weight\":2,\"concurrencyLimit\":1}"),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/accounts/12345678-1234-4234-8234-123456789abc/credentials", "credentials", "rotateCredentials", "{\"name\":\"integration-account\",\"environment\":\"china\",\"apiKey\":\"uk-testsecret12345\"}"),
                org.junit.jupiter.params.provider.Arguments.of("DELETE", "/accounts/12345678-1234-4234-8234-123456789abc/credentials/12345678-1234-4234-8234-123456789abc", "credentials", "removeCredential", ""),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/accounts/12345678-1234-4234-8234-123456789abc/probe", "probe", "probeAccount", ""),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/accounts/12345678-1234-4234-8234-123456789abc/budget", "budget", "updateBudget", "{\"budgetPoints\":3000}"),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/pool", "read", "pool", ""),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/reference-source", "read", "referenceSource", ""),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/usage", "read", "usage", ""),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/sessions", "read", "sessions", ""),
                org.junit.jupiter.params.provider.Arguments.of("GET", "/sessions/12345678-1234-4234-8234-123456789abc", "read", "session", ""),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/sessions/12345678-1234-4234-8234-123456789abc/stop", "stop", "stopSession", ""),
                org.junit.jupiter.params.provider.Arguments.of("POST", "/accounts/12345678-1234-4234-8234-123456789abc/stop-sessions", "stop", "stopAccountSessions", ""));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("tryOnOperations")
    void tryOnRoutesEnforcePermissionsAndForwardSignedRequests(String method, String path, String permission,
            String operation, String body) throws Exception {
        String endpoint = "/api/v1/closet/tryon" + path;
        var httpMethod = HttpMethod.valueOf(method);
        mvc.perform(request(httpMethod, endpoint).with(jwtAs("system:user:read"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        server.expect(requestTo("http://closet.test/api/manage/v1/tryon" + path))
                .andExpect(method(httpMethod)).andExpect(request -> capture(request))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getFirst("X-Closet-Signature")).isNotBlank();
                    assertThat(request.getHeaders().getFirst("X-Operator")).isNotBlank();
                })
                .andExpect(content().string(body))
                .andRespond(withSuccess("{\"forwarded\":true}", MediaType.APPLICATION_JSON));
        mvc.perform(request(httpMethod, endpoint).with(jwtAs("closet:tryon:" + permission))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.forwarded").value(true));
        server.verify();
        if (!method.equals("GET")) {
            String action = "CLOSET_TRYON_" + operation.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
            assertThat(auditEvents.findAll().stream().filter(event -> event.getAction().equals(action)
                    && event.getResult().equals("SUCCESS")).toList()).isNotEmpty()
                    .allSatisfy(event -> assertThat(event.getMetadata()).isNull());
        }
    }

    @Test void tryOnValidationRejectsUnknownMissingAndOutOfRangeFieldsBeforeAnyUpstreamCall() throws Exception {
        for (String body : java.util.List.of("{}", "{\"revision\":-1,\"extra\":true}")) {
            mvc.perform(post("/api/v1/closet/tryon/settings/reset").with(jwtAs("closet:tryon:write"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        for (String body : java.util.List.of(
                "{\"accessMode\":\"INHERIT\",\"dailySeconds\":-1}",
                "{\"accessMode\":\"INHERIT\",\"dailySeconds\":null,\"startupFailureLimit\":1000}",
                "{\"accessMode\":\"ALL\",\"dailySeconds\":null}")) {
            mvc.perform(request(HttpMethod.PUT, "/api/v1/closet/tryon/users/alice/policy").with(jwtAs("closet:tryon:write"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/closet/tryon/accounts?unknown=value").with(jwtAs("closet:tryon:read")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/closet/tryon/accounts/invalid").with(jwtAs("closet:tryon:read")))
                .andExpect(status().isBadRequest());
        server.verify();
    }

    @Test void tryOnStaleRevisionAndCredentialFailuresKeepStatusAndNeverExposeProviderDetails() throws Exception {
        String id = "12345678-1234-4234-8234-123456789abc";
        server.expect(requestTo("http://closet.test/api/manage/v1/tryon/settings/reset"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("{\"code\":\"TRYON_REVISION_CONFLICT\",\"detail\":\"private-provider-value\"}"));
        server.expect(requestTo("http://closet.test/api/manage/v1/tryon/accounts/" + id + "/credentials"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("{\"code\":\"TRYON_ACCOUNT_MISMATCH\",\"detail\":\"uk-testsecret12345\"}"));
        mvc.perform(post("/api/v1/closet/tryon/settings/reset").with(jwtAs("closet:tryon:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":4}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        var response = mvc.perform(post("/api/v1/closet/tryon/accounts/" + id + "/credentials")
                .with(jwtAs("closet:tryon:credentials")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"运营账户\",\"environment\":\"china\",\"apiKey\":\"uk-testsecret12345\"}"))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("uk-testsecret12345");
        assertThat(auditEvents.findAll().stream().filter(event -> event.getAction().equals("CLOSET_TRYON_ROTATE_CREDENTIALS")
                && event.getResult().equals("FAILURE")).toList()).isNotEmpty()
                .allSatisfy(event -> assertThat(event.getMetadata()).contains("CONFLICT").doesNotContain("uk-", "apiKey"));
        server.verify();
    }

    @Test void tryOnSettingsValidateTheCrossFieldStartupDeadline() throws Exception {
        mvc.perform(request(HttpMethod.PUT, "/api/v1/closet/tryon/settings").with(jwtAs("closet:tryon:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":4,\"value\":{\"mode\":\"FOLLOW_USER\",\"defaultEnabled\":false,\"dailySeconds\":180,\"dailyPoints\":1800,\"totalPoints\":3000,\"sessionSeconds\":180,\"startupSeconds\":30,\"firstFrameSeconds\":31,\"queueSeconds\":60,\"offerSeconds\":10,\"recordingSeconds\":60,\"recordingBytes\":104857600,\"storageBytes\":1073741824,\"accountConcurrency\":1,\"accountGuardPoints\":60}}"))
                .andExpect(status().isBadRequest());
        assertThat(permissions.findByCode("closet:tryon:credentials")).isPresent();
        assertThat(menus.findByCode("closet-tryon")).isPresent();
        server.verify();
    }

    @Test void tryOnNormalizesH2AndPostgresSqlRowsWithoutChangingDiagnosticPayloads() throws Exception {
        server.expect(requestTo("http://closet.test/api/manage/v1/tryon/usage"))
                .andRespond(withSuccess("[{\"QUOTA_DAY\":\"2026-11-04T16:00:00.000Z\",\"SESSIONS\":2,\"CONSUMED_POINTS\":5,\"RESERVED_POINTS\":0,\"USER_SECONDS\":0}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/tryon/accounts"))
                .andRespond(withSuccess("[{\"id\":\"account\",\"credentials\":[{\"ID\":\"credential\",\"MASKED_KEY\":\"uk-…demo\",\"ACTIVE\":0,\"CREATED_AT_MS\":1}]}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/tryon/pool"))
                .andRespond(withSuccess("{\"accounts\":[{\"credentials\":[{\"id\":\"credential\",\"masked_key\":\"uk-…demo\",\"active\":0}]}]}", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/v1/closet/tryon/usage").with(jwtAs("closet:tryon:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].quota_day").value("2026-11-05"))
                .andExpect(jsonPath("$.data[0].sessions").value(2)).andExpect(jsonPath("$.data[0].consumed_points").value(5));
        mvc.perform(get("/api/v1/closet/tryon/accounts").with(jwtAs("closet:tryon:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].credentials[0].id").value("credential"))
                .andExpect(jsonPath("$.data[0].credentials[0].masked_key").value("uk-…demo"))
                .andExpect(jsonPath("$.data[0].credentials[0].ID").doesNotExist());
        mvc.perform(get("/api/v1/closet/tryon/pool").with(jwtAs("closet:tryon:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accounts[0].credentials[0].masked_key").value("uk-…demo"));
        server.verify();
    }

    @Test void everyUpstreamContractOperationHasAnActualGatewayMapping() throws Exception {
        var mapping = context.getBean("requestMappingHandlerMapping", org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        var actual = new java.util.TreeSet<String>();
        for (var info : mapping.getHandlerMethods().keySet()) {
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/v1/closet")) continue;
                String relative = path.substring("/api/v1/closet".length());
                // Platform batch review is an extension implemented with individual upstream operations.
                if (relative.equals("/style-terms/pending/batch")) continue;
                for (var method : info.getMethodsCondition().getMethods()) {
                    actual.add(method.name() + " " + relative.replaceAll("\\{[^}]+}", "{}"));
                }
            }
        }
        var expected = ClosetContractSnapshotTest.operations(java.nio.file.Files.readString(
                java.nio.file.Path.of("docs/integrations/closet/management-api.yaml")));
        assertThat(expected).containsAll(ClosetContractSnapshotTest.UPSTREAM_ONLY);
        expected.removeAll(ClosetContractSnapshotTest.UPSTREAM_ONLY);
        expected = expected.stream().map(operation -> operation.replaceAll("\\{[^}]+}", "{}"))
                .collect(java.util.stream.Collectors.toSet());
        // Six insight reports deliberately share one whitelisted dynamic mapping. Exercise every declared report.
        if (actual.remove("GET /insights/{}")) {
            var reports = expected.stream().filter(operation -> operation.startsWith("GET /insights/")).sorted().toList();
            for (String operation : reports) {
                String path = operation.substring("GET ".length());
                server.expect(requestTo("http://closet.test/api/manage/v1" + path))
                        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
            }
            for (String operation : reports) {
                String path = operation.substring("GET ".length());
                mvc.perform(get("/api/v1/closet" + path).with(jwtAs("closet:insights:read"))).andExpect(status().isOk());
                actual.add(operation);
            }
            server.verify();
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> billingOperations() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/settings", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("PUT", "/billing/settings", "", "{\"enabled\":true}", 200),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/tiers", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("PUT", "/billing/tiers/image.standard", "", "{\"model\":\"test-model\",\"milliPointsPerUnit\":1000}", 200),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/cost-rates", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("POST", "/billing/cost-rates", "", "{\"model\":\"test-model\",\"unit\":\"PER_IMAGE\",\"amountMicro\":10000,\"currency\":\"CNY\"}", 201),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/margin", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/margin/alerts", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/margin/explain", "?tier=image.standard&limit=100", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/products", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("POST", "/billing/products", "", "{\"kind\":\"POINTS\",\"name\":\"test product\",\"currency\":\"CNY\",\"priceMinor\":100,\"points\":100,\"enabled\":true}", 201),
            org.junit.jupiter.params.provider.Arguments.of("PUT", "/billing/products/12345678-1234-1234-1234-123456789abc", "", "{\"name\":\"updated\",\"priceMinor\":200,\"enabled\":false}", 200),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/orders", "?limit=100", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("POST", "/billing/orders/12345678-1234-1234-1234-123456789abc/sandbox-dispute", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/redemption-batches", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("POST", "/billing/redemption-batches", "", "{\"name\":\"test batch\",\"points\":100,\"quantity\":1}", 201),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/billing/redemption-batches/12345678-1234-1234-1234-123456789abc/codes", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("POST", "/billing/redemption-batches/12345678-1234-1234-1234-123456789abc/disable", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("DELETE", "/billing/entitlements/12345678-1234-1234-1234-123456789abc", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("GET", "/users/alice/billing", "", "{}", 200),
            org.junit.jupiter.params.provider.Arguments.of("POST", "/users/alice/points", "", "{\"points\":100,\"note\":\"test grant\"}", 200),
            org.junit.jupiter.params.provider.Arguments.of("POST", "/users/alice/entitlements", "", "{\"imageDailyLimit\":40,\"agentDailyLimit\":40,\"days\":1}", 201));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("billingOperations")
    void billingRoutesForwardEveryOperationWithPermissions(String method, String path, String query, String body, int expectedStatus) throws Exception {
        HttpMethod verb = HttpMethod.valueOf(method);
        String authority = "closet:billing:" + (method.equals("GET") ? "read" : "write");
        mvc.perform(request(verb, "/api/v1/closet" + path + query).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(verb, "/api/v1/closet" + path + query).with(jwtAs("closet:user:read"))
                        .contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", "billing-key-1"))
                .andExpect(status().isForbidden());
        if (!method.equals("GET")) mvc.perform(request(verb, "/api/v1/closet" + path + query).with(jwtAs("closet:billing:read"))
                        .contentType(MediaType.APPLICATION_JSON).content(body).header("Idempotency-Key", "billing-key-1"))
                .andExpect(status().isForbidden());
        server.expect(requestTo("http://closet.test/api/manage/v1" + path + query))
                .andExpect(method(verb))
                .andExpect(request -> {
                    assertThat(request.getHeaders().getFirst("X-Closet-Signature")).isNotBlank();
                    if (path.endsWith("/points")) assertThat(request.getHeaders().getFirst("Idempotency-Key")).isEqualTo("billing-key-1");
                })
                .andRespond(withSuccess("{\"id\":\"billing-result\",\"codes\":[\"ONE-TIME-BILLING-CODE\"]}", MediaType.APPLICATION_JSON));
        mvc.perform(request(verb, "/api/v1/closet" + path + query).with(jwtAs(authority))
                        .header("Idempotency-Key", "billing-key-1").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.data.id").value("billing-result"));
        server.verify();
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().startsWith("CLOSET_BILLING_")).toList())
                .allSatisfy(e -> assertThat(java.util.Objects.toString(e.getMetadata(), "")).doesNotContain("ONE-TIME-BILLING-CODE"));
    }

    @Test void billingValidationAndSeeds() throws Exception {
        assertThat(permissions.findByCode("closet:billing:read")).isPresent();
        assertThat(permissions.findByCode("closet:billing:write")).isPresent();
        for (String page : java.util.List.of("settings", "tiers", "products", "redemptions", "margin"))
            assertThat(menus.findByCode("closet-billing-" + page)).isPresent();
        mvc.perform(get("/api/v1/closet/billing/orders?limit=501").with(jwtAs("closet:billing:read"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/closet/users/alice/points").with(jwtAs("closet:billing:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"points\":100}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/closet/billing/products").with(jwtAs("closet:billing:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"POINTS\",\"name\":\"test\",\"priceMinor\":0,\"currency\":\"CNY\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test void billingFailureIsAuditedAndKeepsConflictStatus() throws Exception {
        server.expect(requestTo("http://closet.test/api/manage/v1/users/alice/points"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"code\":\"IDEMPOTENCY_CONFLICT\",\"detail\":\"Request already used\"}"));
        mvc.perform(post("/api/v1/closet/users/alice/points").with(jwtAs("closet:billing:write"))
                .header("Idempotency-Key", "billing-conflict").contentType(MediaType.APPLICATION_JSON).content("{\"points\":100}"))
                .andExpect(status().isConflict());
        server.verify();
        assertThat(auditEvents.findAll()).anySatisfy(e -> {
            assertThat(e.getAction()).isEqualTo("CLOSET_BILLING_POINTS_CHANGED");
            assertThat(e.getResult()).isEqualTo("FAILURE");
        });
    }

    @Autowired com.yumg.starter.modules.integrations.closet.application.ClosetNavigationBootstrap navigationBootstrap;

    @Test void upgradingAnOldDatabaseRemovesCapabilitiesAndRenamesTemplates() throws Exception {
        for (String code : java.util.List.of("closet-creation-guide", "closet-creation-templates")) {
            var menu = menus.findByCode(code).orElseThrow();
            menu.setVisible(true);
            if (code.endsWith("templates")) { menu.rename("模板"); menu.reorder(10); }
            menus.save(menu);
        }
        var parent = menus.findByCode("closet-creation").orElseThrow();
        menus.save(new com.yumg.starter.entities.NavigationMenu(parent.getId(), "closet-creation-capabilities",
                "模型能力", "/creation/capabilities", "closet-creation-capabilities", "ri/settings-3-line", 20,
                com.yumg.starter.entities.NavigationMenuType.PAGE, "closet:creation:read", true, true, false, true));
        navigationBootstrap.run(null);
        assertThat(menus.findByCode("closet-creation-guide").orElseThrow().isVisible()).isFalse();
        assertThat(menus.findByCode("closet-creation-capabilities")).isEmpty();
        navigationBootstrap.run(null);
        assertThat(menus.findByCode("closet-creation-capabilities")).isEmpty();
        var effects = menus.findByCode("closet-creation-templates").orElseThrow();
        assertThat(effects.getTitle()).isEqualTo("创作效果");
        assertThat(effects.getSortOrder()).isZero();
        assertThat(effects.isVisible()).isTrue();
    }

    private static final String UUID_A = "11111111-2222-3333-4444-555555555555";
    private static final String UUID_B = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private record TrialRoute(HttpMethod method, String path, String permission, String body) {}
    static java.util.stream.Stream<TrialRoute> trialRoutes() {
        String v = "/templates/sunlit-flatlay/versions/" + UUID_A;
        return java.util.stream.Stream.of(
                new TrialRoute(HttpMethod.GET, "/prompt-samples", "read", null),
                new TrialRoute(HttpMethod.POST, "/templates", "write", "{\"name\":\"New\",\"personRequired\":true,\"sampleKey\":\"x\"}"),
                new TrialRoute(HttpMethod.PUT, "/templates/order", "write", "{\"ids\":[\"fx-0a1b2c3d\",\"sunlit-flatlay\"]}"),
                new TrialRoute(HttpMethod.DELETE, "/templates/fx-0a1b2c3d", "write", null),
                new TrialRoute(HttpMethod.GET, "/templates/fx-0a1b2c3d/trials", "read", null),
                new TrialRoute(HttpMethod.GET, "/trial-garments", "read", null),
                new TrialRoute(HttpMethod.GET, "/trial-persons", "read", null),
                new TrialRoute(HttpMethod.DELETE, "/trial-persons/" + UUID_B, "write", null),
                new TrialRoute(HttpMethod.POST, v + "/trials", "write", "{\"garmentIds\":[\"" + UUID_B + "\"]}"),
                new TrialRoute(HttpMethod.GET, "/templates/dual-view-editorial/trials", "read", null),
                new TrialRoute(HttpMethod.GET, "/trials/" + UUID_B, "read", null),
                new TrialRoute(HttpMethod.POST, v + "/preview", "write", "{\"trialId\":\"" + UUID_B + "\"}"),
                new TrialRoute(HttpMethod.POST, v + "/launch", "publish", "{\"revision\":3,\"trialId\":\"" + UUID_B + "\"}"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("trialRoutes")
    void trialRoutesRequirePermissionAndForwardMethodPathAndBody(TrialRoute route) throws Exception {
        String path = "/creations" + route.path();
        String other = route.permission().equals("read") ? "closet:showcase:read" : "closet:creation:read";
        var denied = request(route.method(), "/api/v1/closet" + path).with(jwtAs(other));
        if (route.body() != null) denied.contentType(MediaType.APPLICATION_JSON).content(route.body());
        mvc.perform(denied).andExpect(status().isForbidden());
        var expect = server.expect(requestTo("http://closet.test/api/manage/v1" + path)).andExpect(method(route.method()));
        if (route.body() != null) expect.andExpect(content().json(route.body()));
        expect.andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        var call = request(route.method(), "/api/v1/closet" + path).with(jwtAs("closet:creation:" + route.permission()));
        if (route.body() != null) call.contentType(MediaType.APPLICATION_JSON).content(route.body());
        mvc.perform(call).andExpect(status().isOk());
        server.verify();
    }

    @Test void trialWritesAreAuditedWithTheirActions() throws Exception {
        String v = "/creations/templates/sunlit-flatlay/versions/" + UUID_A;
        server.expect(requestTo("http://closet.test/api/manage/v1" + v + "/launch")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/creations/trial-persons/" + UUID_B))
                .andRespond(withSuccess("{\"deleted\":true}", MediaType.APPLICATION_JSON));
        mvc.perform(post("/api/v1/closet" + v + "/launch").with(jwtAs("closet:creation:publish"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"trialId\":\"" + UUID_B + "\"}")).andExpect(status().isOk());
        mvc.perform(request(HttpMethod.DELETE, "/api/v1/closet/creations/trial-persons/" + UUID_B).with(jwtAs("closet:creation:write")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
        assertThat(auditEvents.findAll().stream().map(e -> e.getAction()))
                .contains("CLOSET_CREATION_VERSION_LAUNCHED", "CLOSET_CREATION_TRIAL_PERSON_DELETED");
    }

    @Test void effectSlugsAreValidatedByPatternAndDeleteRejectsBadIds() throws Exception {
        String root = "/api/v1/closet/creations";
        for (String bad : java.util.List.of("A", "x", "Bad_Id", "-ab", "a".repeat(81)))
            mvc.perform(request(HttpMethod.DELETE, root + "/templates/" + bad).with(jwtAs("closet:creation:write")))
                    .andExpect(status().isBadRequest());
    }

    @Test void orderRouteIsLiteralAndDoesNotCollideWithTemplateId() throws Exception {
        server.expect(requestTo("http://closet.test/api/manage/v1/creations/templates/order"))
                .andExpect(method(HttpMethod.PUT)).andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://closet.test/api/manage/v1/creations/templates/fx-0a1b2c3d"))
                .andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess("{\"deleted\":true}", MediaType.APPLICATION_JSON));
        mvc.perform(request(HttpMethod.PUT, "/api/v1/closet/creations/templates/order").with(jwtAs("closet:creation:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}")).andExpect(status().isOk());
        mvc.perform(request(HttpMethod.DELETE, "/api/v1/closet/creations/templates/fx-0a1b2c3d").with(jwtAs("closet:creation:write")))
                .andExpect(status().isOk());
        server.verify();
        assertThat(auditEvents.findAll().stream().map(e -> e.getAction()))
                .contains("CLOSET_CREATION_EFFECTS_REORDERED", "CLOSET_CREATION_EFFECT_DELETED");
    }

    @Test void trialRoutesRejectMalformedPathParameters() throws Exception {
        String root = "/api/v1/closet/creations";
        String body = "{\"trialId\":\"" + UUID_B + "\"}";
        mvc.perform(post(root + "/templates/Bad_Id/versions/" + UUID_A + "/trials").with(jwtAs("closet:creation:write"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(root + "/templates/sunlit-flatlay/versions/v1/preview").with(jwtAs("closet:creation:write"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(root + "/templates/sunlit-flatlay/versions/not-a-uuid/launch").with(jwtAs("closet:creation:publish"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(get(root + "/templates/-nope/trials").with(jwtAs("closet:creation:read"))).andExpect(status().isBadRequest());
        mvc.perform(get(root + "/templates/sunlit-flatlay/versions/v1/preview-image").with(jwtAs("closet:creation:read")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(root + "/trials/abc").with(jwtAs("closet:creation:read"))).andExpect(status().isBadRequest());
        mvc.perform(get(root + "/trials/abc/image").with(jwtAs("closet:creation:read"))).andExpect(status().isBadRequest());
        mvc.perform(get(root + "/trial-garments/abc/image").with(jwtAs("closet:creation:read"))).andExpect(status().isBadRequest());
        mvc.perform(get(root + "/trial-persons/abc/image").with(jwtAs("closet:creation:read"))).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.DELETE, root + "/trial-persons/abc").with(jwtAs("closet:creation:write")))
                .andExpect(status().isBadRequest());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"trial-garments", "trial-persons", "trials", "preview"})
    void trialImagesPassBytesAndPngTypeThrough(String kind) throws Exception {
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3};
        String path = kind.equals("preview") ? "/creations/templates/sunlit-flatlay/versions/" + UUID_A + "/preview-image"
                : "/creations/" + kind + "/" + UUID_B + "/image";
        mvc.perform(get("/api/v1/closet" + path).with(jwtAs("closet:showcase:read"))).andExpect(status().isForbidden());
        server.expect(requestTo("http://closet.test/api/manage/v1" + path)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(png, MediaType.IMAGE_PNG));
        // Plain byte[] response: no async streaming thread racing the security header writers.
        mvc.perform(get("/api/v1/closet" + path).with(jwtAs("closet:creation:read")))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(png))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_PNG))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        server.verify();
    }

    @Test void trialPersonUploadForwardsSignedRawMultipartAndEnforcesLimits() throws Exception {
        byte[] raw = multipartPng("personBoundary", new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        String url = "/api/v1/closet/creations/trial-persons";
        String type = "multipart/form-data;boundary=personBoundary";
        mvc.perform(post(url).with(jwtAs("closet:creation:read")).content(raw)
                .with(r -> { r.setContentType(type); return r; })).andExpect(status().isForbidden());
        server.expect(requestTo("http://closet.test/api/manage/v1/creations/trial-persons"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().bytes(raw))
                .andExpect(req -> {
                    var h = req.getHeaders();
                    assertThat(h.getContentType().getParameter("boundary")).isEqualTo("personBoundary");
                    var signer = new com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner();
                    String canonical = signer.canonical("POST", req.getURI().getRawPath(), java.util.Map.of(),
                            Long.parseLong(h.getFirst("X-Closet-Timestamp")), h.getFirst("X-Closet-Nonce"),
                            h.getFirst("X-Operator"), h.getFirst("X-Request-Id"), raw);
                    assertThat(h.getFirst("X-Closet-Signature")).isEqualTo(signer.sign(canonical, "0123456789abcdef0123456789abcdef"));
                })
                .andRespond(withSuccess("{\"id\":\"" + UUID_B + "\"}", MediaType.APPLICATION_JSON));
        mvc.perform(post(url).with(jwtAs("closet:creation:write")).content(raw).with(r -> { r.setContentType(type); return r; }))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(UUID_B));
        server.verify();
        mvc.perform(post(url).with(jwtAs("closet:creation:write")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        byte[] big = new byte[12 * 1024 * 1024 + 1];
        mvc.perform(post(url).with(jwtAs("closet:creation:write")).content(big).with(r -> { r.setContentType(type); return r; }))
                .andExpect(status().isPayloadTooLarge());
    }
}
