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

/** Curated looks and pose management proxies (design 2026-11-22, sections 3.5, 4.2 and 7). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:target/closet-try-content-api-test.db",
        "app.integrations.closet.enabled=true",
        "app.integrations.closet.key-id=test-key",
        "app.integrations.closet.secret=0123456789abcdef0123456789abcdef",
        "app.integrations.closet.connect-timeout=1s",
        "app.integrations.closet.read-timeout=150ms",
        "app.runtime.component-startup-probe.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClosetTryContentApiTest {
    private static final String UPSTREAM = "http://closet.test/api/manage/v1";
    private static final String GATEWAY = "/api/v1/closet";
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String LOOK_ID = "11111111-2222-3333-4444-555555555555";
    private static final String PRESET_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String PRESET_B = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final int LIMIT = 20 * 1024 * 1024;
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

    @Test void permissionsAndTheTryContentMenuAreSeeded() {
        var codes = java.util.List.of("closet:looks:read", "closet:looks:write", "closet:poses:read", "closet:poses:write");
        for (String code : codes) assertThat(permissions.findByCode(code)).isPresent();
        assertThat(roles.findAllWithPermissions().stream().filter(role -> role.getCode().equals("SUPER_ADMIN"))
                .findFirst().orElseThrow().getPermissions()).extracting(permission -> permission.getCode()).containsAll(codes);
        var directory = menus.findByCode("closet-try-content").orElseThrow();
        assertThat(directory.getTitle()).isEqualTo("试穿内容");
        assertThat(directory.getMenuType()).isEqualTo(NavigationMenuType.DIRECTORY);
        var looks = menus.findByCode("closet-looks").orElseThrow();
        var poses = menus.findByCode("closet-poses").orElseThrow();
        assertThat(looks.getParentId()).isEqualTo(directory.getId());
        assertThat(poses.getParentId()).isEqualTo(directory.getId());
        assertThat(looks.getTitle()).isEqualTo("精选整套");
        assertThat(looks.getRoutePath()).isEqualTo("/closet/looks");
        assertThat(looks.getComponentKey()).isEqualTo("closet-looks");
        assertThat(looks.getRequiredPermission()).isEqualTo("closet:looks:read");
        assertThat(poses.getTitle()).isEqualTo("动作管理");
        assertThat(poses.getRoutePath()).isEqualTo("/closet/poses");
        assertThat(poses.getComponentKey()).isEqualTo("closet-poses");
        assertThat(poses.getRequiredPermission()).isEqualTo("closet:poses:read");
    }

    record Route(HttpMethod method, String path, String upstreamQuery, String allowed, String denied, String body, int status) {
        @Override public String toString() { return method + " " + path; }
    }

    static Stream<Route> routes() {
        String look = "/looks/" + LOOK_ID;
        return Stream.of(
                new Route(HttpMethod.GET, "/looks/categories", "", "closet:looks:read", "closet:poses:write", null, 200),
                new Route(HttpMethod.POST, "/looks/categories", "", "closet:looks:write", "closet:looks:read", "{\"key\":\"rainy\",\"name\":\"雨天\"}", 201),
                new Route(HttpMethod.PATCH, "/looks/categories/rainy", "", "closet:looks:write", "closet:looks:read", "{\"enabled\":true}", 200),
                new Route(HttpMethod.DELETE, "/looks/categories/rainy", "", "closet:looks:write", "closet:looks:read", null, 204),
                new Route(HttpMethod.GET, "/looks", "?page=1&size=48", "closet:looks:read", "closet:poses:write", null, 200),
                new Route(HttpMethod.POST, "/looks", "", "closet:looks:write", "closet:looks:read",
                        "{\"name\":\"春日通勤\",\"category\":\"spring\",\"tags\":[\"清爽\"],\"coverage\":\"FULL\",\"gender\":\"UNISEX\"}", 201),
                new Route(HttpMethod.POST, "/looks/compose", "", "closet:looks:write", "closet:looks:read",
                        "{\"name\":\"拼一套\",\"category\":\"spring\",\"coverage\":\"UPPER\",\"presetIds\":[\"" + PRESET_A + "\",\"" + PRESET_B + "\"]}", 201),
                new Route(HttpMethod.PATCH, look, "", "closet:looks:write", "closet:looks:read", "{\"status\":\"LISTED\",\"verified\":true}", 200),
                new Route(HttpMethod.DELETE, look, "", "closet:looks:write", "closet:looks:read", null, 204),
                new Route(HttpMethod.POST, "/looks/batch", "", "closet:looks:write", "closet:looks:read",
                        "{\"ids\":[\"" + LOOK_ID + "\"],\"action\":\"VERIFY\"}", 200),
                new Route(HttpMethod.GET, "/poses", "", "closet:poses:read", "closet:looks:write", null, 200),
                new Route(HttpMethod.POST, "/poses", "", "closet:poses:write", "closet:poses:read",
                        "{\"name\":\"自然站立\",\"description\":\"正面自然站立，双臂垂在身体两侧\",\"tags\":[\"站姿\"],\"viewAngle\":\"FRONT\","
                                + "\"keypoints\":{\"HEAD\":[0.5,0.11]},\"status\":\"DRAFT\"}", 201),
                new Route(HttpMethod.PATCH, "/poses/stand", "", "closet:poses:write", "closet:poses:read", "{\"name\":\"站立\"}", 200),
                new Route(HttpMethod.DELETE, "/poses/stand", "", "closet:poses:write", "closet:poses:read", null, 204),
                new Route(HttpMethod.POST, "/poses/batch", "", "closet:poses:write", "closet:poses:read",
                        "{\"ids\":[\"stand\",\"hand-on-hip\"],\"action\":\"UNLIST\"}", 200),
                new Route(HttpMethod.POST, "/poses/check", "", "closet:poses:read", "closet:looks:write",
                        "{\"name\":\"站立\",\"description\":\"正面自然站立\"}", 200),
                new Route(HttpMethod.GET, "/poses/check-settings", "", "closet:poses:read", "closet:looks:write", null, 200),
                new Route(HttpMethod.PUT, "/poses/check-settings", "", "closet:poses:write", "closet:poses:read",
                        "{\"user\":{\"suggestive\":true,\"nudity\":true,\"minor\":false},\"admin\":{\"suggestive\":true,\"nudity\":false,\"minor\":false},\"revision\":3}", 200));
    }

    @ParameterizedTest @MethodSource("routes")
    void everyRouteChecksItsPermissionAndForwardsSigned(Route route) throws Exception {
        var forbidden = request(route.method(), GATEWAY + route.path()).with(jwtAs(route.denied()));
        if (route.body() != null) forbidden.contentType(MediaType.APPLICATION_JSON).content(route.body());
        mvc.perform(forbidden).andExpect(status().isForbidden());

        var expectation = server.expect(requestTo(UPSTREAM + route.path() + route.upstreamQuery()))
                .andExpect(method(route.method()))
                .andExpect(req -> assertThat(req.getHeaders().getFirst("X-Closet-Signature")).isNotBlank());
        if (route.body() != null) expectation = expectation.andExpect(content().json(route.body()));
        if (route.status() == 204) expectation.andRespond(withNoContent());
        else expectation.andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        var allowed = request(route.method(), GATEWAY + route.path()).with(jwtAs(route.allowed()));
        if (route.body() != null) allowed.contentType(MediaType.APPLICATION_JSON).content(route.body());
        var result = mvc.perform(allowed).andExpect(status().is(route.status()));
        if (route.status() != 204) result.andExpect(jsonPath("$.data.ok").value(true));
        server.verify();
    }

    @Test void lookListConvertsPagesAndForwardsOnlyGivenFilters() throws Exception {
        server.expect(requestTo(UPSTREAM + "/looks?page=2&size=10&status=LISTED&category=spring&verified=false&q=coat"))
                .andRespond(withSuccess("{\"items\":[],\"total\":0}", MediaType.APPLICATION_JSON));
        mvc.perform(get(GATEWAY + "/looks?page=1&size=10&status=LISTED&category=spring&verified=false&q=coat")
                .with(jwtAs("closet:looks:read"))).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        server.verify();
        for (String query : java.util.List.of("?status=REMOVED", "?size=101", "?size=0", "?page=-1", "?category=Bad_Key", "?verified=maybe"))
            mvc.perform(get(GATEWAY + "/looks" + query).with(jwtAs("closet:looks:read"))).andExpect(status().isBadRequest());
        server.verify();
    }

    @Test void partialEditsOmitAbsentFieldsAndUnknownFieldsNeverReachTheSource() throws Exception {
        server.expect(requestTo(UPSTREAM + "/looks/" + LOOK_ID)).andExpect(method(HttpMethod.PATCH))
                .andExpect(content().string("{\"status\":\"UNLISTED\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        mvc.perform(request(HttpMethod.PATCH, GATEWAY + "/looks/" + LOOK_ID).with(jwtAs("closet:looks:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"UNLISTED\"}")).andExpect(status().isOk());
        server.verify();
        mvc.perform(post(GATEWAY + "/looks").with(jwtAs("closet:looks:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"a\",\"category\":\"spring\",\"coverage\":\"FULL\",\"imageKey\":\"looks/x/1.jpg\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(GATEWAY + "/looks").with(jwtAs("closet:looks:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"a\",\"category\":\"spring\",\"coverage\":\"BODY\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(GATEWAY + "/looks/compose").with(jwtAs("closet:looks:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"a\",\"category\":\"spring\",\"coverage\":\"FULL\",\"presetIds\":[\"" + PRESET_A + "\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(GATEWAY + "/looks/batch").with(jwtAs("closet:looks:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[],\"action\":\"LIST\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(GATEWAY + "/poses/batch").with(jwtAs("closet:poses:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"stand\"],\"action\":\"VERIFY\"}")).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.PATCH, GATEWAY + "/poses/Bad_Id").with(jwtAs("closet:poses:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.PUT, GATEWAY + "/poses/check-settings").with(jwtAs("closet:poses:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user\":{\"suggestive\":true,\"nudity\":true},\"admin\":{\"suggestive\":true,\"nudity\":true,\"minor\":false},\"revision\":1}"))
                .andExpect(status().isBadRequest());
        server.verify();
    }

    @Test void lookImageUploadForwardsRawBytesSignedAndEnforcesTheLimit() throws Exception {
        String path = "/looks/" + LOOK_ID + "/image";
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 1, 2, 3};
        mvc.perform(request(HttpMethod.PUT, GATEWAY + path).with(jwtAs("closet:looks:read"))
                .contentType(MediaType.IMAGE_JPEG).content(jpeg)).andExpect(status().isForbidden());
        server.expect(requestTo(UPSTREAM + path)).andExpect(method(HttpMethod.PUT)).andExpect(content().bytes(jpeg))
                .andExpect(req -> {
                    var h = req.getHeaders();
                    assertThat(h.getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
                    assertThat(h.getFirst("X-Operator")).isEqualTo("admin");
                    assertSigned("PUT", req.getURI().getRawPath(), h, jpeg);
                }).andRespond(withSuccess("{\"id\":\"" + LOOK_ID + "\",\"hasImage\":true,\"imageVersion\":2,\"verified\":false}", MediaType.APPLICATION_JSON));
        mvc.perform(request(HttpMethod.PUT, GATEWAY + path).with(jwtAs("closet:looks:write"))
                .contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.imageVersion").value(2));
        server.verify();
        for (String type : java.util.List.of("image/gif", "application/octet-stream", "text/plain"))
            mvc.perform(request(HttpMethod.PUT, GATEWAY + path).with(jwtAs("closet:looks:write"))
                    .contentType(type).content(jpeg)).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.PUT, GATEWAY + path).with(jwtAs("closet:looks:write"))
                .contentType(MediaType.IMAGE_PNG).content(new byte[0])).andExpect(status().isBadRequest());
        mvc.perform(request(HttpMethod.PUT, GATEWAY + path).with(jwtAs("closet:looks:write"))
                .contentType(MediaType.IMAGE_PNG).content(new byte[LIMIT + 1])).andExpect(status().isPayloadTooLarge());
        server.verify();
        var audits = auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_LOOK_IMAGE_UPLOAD")).toList();
        assertThat(audits).isNotEmpty().allSatisfy(e -> {
            assertThat(e.getTargetType()).isEqualTo("ClosetLook");
            assertThat(e.getTargetId()).isEqualTo(LOOK_ID);
        });
    }

    @Test void maximumImageIsForwardedWhole() throws Exception {
        byte[] max = new byte[LIMIT];
        max[0] = (byte) 0x89; max[1] = 'P'; max[2] = 'N'; max[3] = 'G';
        max[LIMIT - 1] = 7;
        server.expect(requestTo(UPSTREAM + "/poses/extract")).andExpect(method(HttpMethod.POST)).andExpect(content().bytes(max))
                .andRespond(withSuccess("{\"draft\":{\"name\":\"站\",\"description\":\"正面站立\",\"tags\":[],\"viewAngle\":\"FRONT\",\"keypoints\":null}}",
                        MediaType.APPLICATION_JSON));
        mvc.perform(post(GATEWAY + "/poses/extract").with(jwtAs("closet:poses:write")).contentType(MediaType.IMAGE_PNG).content(max))
                .andExpect(status().isOk());
        server.verify();
    }

    @Test void poseExtractSendsThePhotoOnceAndKeepsNothing() throws Exception {
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 9, 9};
        mvc.perform(post(GATEWAY + "/poses/extract").with(jwtAs("closet:poses:read")).contentType("image/webp").content(webp))
                .andExpect(status().isForbidden());
        server.expect(requestTo(UPSTREAM + "/poses/extract")).andExpect(method(HttpMethod.POST)).andExpect(content().bytes(webp))
                .andExpect(req -> {
                    assertThat(req.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/webp"));
                    assertSigned("POST", req.getURI().getRawPath(), req.getHeaders(), webp);
                })
                .andRespond(withSuccess("{\"draft\":{\"name\":\"单手叉腰\",\"description\":\"左手叉腰\",\"tags\":[\"站姿\"],\"viewAngle\":\"FRONT\",\"keypoints\":{}}}",
                        MediaType.APPLICATION_JSON));
        mvc.perform(post(GATEWAY + "/poses/extract").with(jwtAs("closet:poses:write")).contentType("image/webp").content(webp))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.draft.name").value("单手叉腰"));
        server.verify();
        mvc.perform(post(GATEWAY + "/poses/extract").with(jwtAs("closet:poses:write")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(GATEWAY + "/poses/extract").with(jwtAs("closet:poses:write")).contentType(MediaType.IMAGE_JPEG).content(new byte[LIMIT + 1]))
                .andExpect(status().isPayloadTooLarge());
        server.verify();
        var audits = auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_POSE_EXTRACT")).toList();
        assertThat(audits).isNotEmpty().allSatisfy(e -> {
            assertThat(e.getTargetId()).isEqualTo("photo");
            assertThat(String.valueOf(e.getMetadata())).doesNotContain("单手叉腰", "RIFF");
        });
    }

    @Test void extractLimitAndRejectionsKeepTheirMeaning() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        server.expect(requestTo(UPSTREAM + "/poses/extract"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"POSE_EXTRACT_LIMIT\",\"detail\":\"今天的提取次数已用完\"}"));
        mvc.perform(post(GATEWAY + "/poses/extract").with(jwtAs("closet:poses:write")).contentType(MediaType.IMAGE_PNG).content(png))
                .andExpect(status().isTooManyRequests());
        server.verify();
        server.reset();
        server.expect(requestTo(UPSTREAM + "/poses"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"POSE_REJECTED\",\"detail\":\"动作没有通过检查\",\"hits\":[{\"kind\":\"SUGGESTIVE\",\"term\":\"撩裙\",\"reason\":\"带性暗示的动作\"}]}"));
        mvc.perform(post(GATEWAY + "/poses").with(jwtAs("closet:poses:write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"撩裙\",\"description\":\"单手撩裙看镜头\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CLOSET_POSE_REJECTED"))
                .andExpect(jsonPath("$.violations[0].field").value("SUGGESTIVE"))
                .andExpect(jsonPath("$.violations[0].message").value("撩裙：带性暗示的动作"));
        server.verify();
        server.reset();
        server.expect(requestTo(UPSTREAM + "/looks/categories/spring"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"LOOK_CATEGORY_NOT_EMPTY\",\"detail\":\"分类下还有整套\"}"));
        mvc.perform(request(HttpMethod.DELETE, GATEWAY + "/looks/categories/spring").with(jwtAs("closet:looks:write")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("分类下还有整套"));
        server.verify();
        server.reset();
        server.expect(requestTo(UPSTREAM + "/looks/" + LOOK_ID + "/image"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"LOOK_NOT_FOUND\",\"detail\":\"not found\"}"));
        mvc.perform(get(GATEWAY + "/looks/" + LOOK_ID + "/image").with(jwtAs("closet:looks:read"))).andExpect(status().isNotFound());
        server.verify();
    }

    @Test void lookImageIsLabelledByItsBytesAndNeverCached() throws Exception {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xdb, 4, 5};
        mvc.perform(get(GATEWAY + "/looks/" + LOOK_ID + "/image").with(jwtAs("closet:poses:read"))).andExpect(status().isForbidden());
        server.expect(requestTo(UPSTREAM + "/looks/" + LOOK_ID + "/image")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(jpeg, MediaType.IMAGE_JPEG));
        mvc.perform(get(GATEWAY + "/looks/" + LOOK_ID + "/image").with(jwtAs("closet:looks:read")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(jpeg))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        server.verify();
        mvc.perform(get(GATEWAY + "/looks/not-a-uuid/image").with(jwtAs("closet:looks:read"))).andExpect(status().isBadRequest());
    }

    @Test void checkSettingsChangesAreAuditedWithBothSidesAndConflictsSurface() throws Exception {
        String body = "{\"user\":{\"suggestive\":true,\"nudity\":true,\"minor\":true},\"admin\":{\"suggestive\":false,\"nudity\":true,\"minor\":false},\"revision\":7}";
        server.expect(requestTo(UPSTREAM + "/poses/check-settings")).andExpect(method(HttpMethod.PUT)).andExpect(content().json(body))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"code\":\"POSE_CHECK_SETTINGS_CONFLICT\",\"detail\":\"设置已被修改，请刷新\"}"));
        mvc.perform(request(HttpMethod.PUT, GATEWAY + "/poses/check-settings").with(jwtAs("closet:poses:write"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("设置已被修改，请刷新"));
        server.verify();
        var recorded = auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_POSE_CHECK_SETTINGS")).toList();
        assertThat(recorded).extracting(AuditEvent::getResult).contains("FAILURE");
        server.reset();
        server.expect(requestTo(UPSTREAM + "/poses/check-settings")).andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{\"revision\":8}", MediaType.APPLICATION_JSON));
        mvc.perform(request(HttpMethod.PUT, GATEWAY + "/poses/check-settings").with(jwtAs("closet:poses:write"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        server.verify();
        assertThat(auditEvents.findAll().stream().filter(e -> e.getAction().equals("CLOSET_POSE_CHECK_SETTINGS")
                && "SUCCESS".equals(e.getResult())).map(AuditEvent::getMetadata).toList())
                .contains("user[suggestive=true,nudity=true,minor=true] admin[suggestive=false,nudity=true,minor=false] revision=7");
    }

    private static void assertSigned(String method, String rawPath, org.springframework.http.HttpHeaders h, byte[] body) {
        var signer = new ClosetRequestSigner();
        String canonical = signer.canonical(method, rawPath, Map.of(), Long.parseLong(h.getFirst("X-Closet-Timestamp")),
                h.getFirst("X-Closet-Nonce"), h.getFirst("X-Operator"), h.getFirst("X-Request-Id"), body);
        assertThat(h.getFirst("X-Closet-Signature")).isEqualTo(signer.sign(canonical, SECRET));
    }

    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwtAs(String authority) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt.claim("username", "admin"))
                .authorities(() -> authority);
    }
}
