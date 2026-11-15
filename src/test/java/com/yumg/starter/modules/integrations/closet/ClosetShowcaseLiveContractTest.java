package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit opt-in against a disposable local source, using a synthetic labelled PNG. */
@EnabledIfSystemProperty(named = "showcase.live.test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:target/p2-m-showcase-live-test.db",
        "app.integrations.closet.enabled=true", "app.integrations.closet.base-url=http://127.0.0.1:18764",
        "app.integrations.closet.key-id=primary", "app.integrations.closet.secret=management-test-secret-0123456789abcdef",
        "app.runtime.component-startup-probe.enabled=false"})
class ClosetShowcaseLiveContractTest {
    @Autowired WebApplicationContext context;
    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwt(String permission) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.claim("username", "showcase-fixture"))
                .authorities(() -> "closet:showcase:" + permission);
    }
    private static JsonNode data(String json) { return JsonMapper.builder().build().readTree(json).path("data"); }
    @Test void rawUploadDraftPreviewConflictPublishAndRetireUseRealHttpHmac() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String root = "/api/v1/closet/creations/showcases";
        byte[] fixture = Files.readAllBytes(Path.of(System.getProperty("showcase.fixture")));
        var raw = new ByteArrayOutputStream();
        raw.write("--liveFixture\r\nContent-Disposition: form-data; name=\"file\"; filename=\"official.png\"\r\nContent-Type: image/png\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        raw.write(fixture);
        raw.write("\r\n--liveFixture\r\nContent-Disposition: form-data; name=\"sourceNote\"\r\n\r\nSynthetic public fixture prepared locally\r\n--liveFixture--\r\n".getBytes(StandardCharsets.UTF_8));
        var upload = data(mvc.perform(post(root + "/uploads").with(jwt("write"))
                .contentType("multipart/form-data;boundary=liveFixture").content(raw.toByteArray()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String uploadId = upload.path("uploadId").asText();
        assertThat(upload.path("hash").asText()).hasSize(64);
        String create = "{\"uploadId\":\"" + uploadId + "\",\"title\":\"Official fixture\",\"description\":\"Local protocol test\",\"sortOrder\":10000}";
        var asset = data(mvc.perform(post(root).with(jwt("write")).contentType(MediaType.APPLICATION_JSON).content(create))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString());
        String id = asset.path("id").asText();
        mvc.perform(post(root).with(jwt("write")).contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isConflict());
        mvc.perform(get(root).with(jwt("read"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.total").isNumber());
        var image = mvc.perform(get(root + "/" + id + "/image").with(jwt("read")))
                .andExpect(request().asyncStarted()).andReturn();
        byte[] imageBytes = mvc.perform(asyncDispatch(image)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG)).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(imageBytes).startsWith((byte)137,(byte)80,(byte)78,(byte)71);
        mvc.perform(post(root + "/" + id + "/publish").with(jwt("write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0}")).andExpect(status().isForbidden());
        mvc.perform(post(root + "/" + id + "/publish").with(jwt("publish")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PUBLISHED"));
        mvc.perform(put(root + "/" + id).with(jwt("write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":0,\"title\":\"stale\",\"description\":\"\",\"sortOrder\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(post(root + "/" + id + "/retire").with(jwt("publish")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RETIRED"));
        mvc.perform(put(root + "/" + id).with(jwt("write")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":2,\"title\":\"Revised fixture\",\"description\":\"\",\"sortOrder\":10000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"));
    }
}
