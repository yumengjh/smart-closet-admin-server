package com.yumg.starter.modules.integrations.closet;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Explicit local-only test against a disposable service using the documented fixture key. */
@EnabledIfSystemProperty(named = "creation.live.test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:sqlite:target/creation-live-test.db",
        "app.integrations.closet.enabled=true", "app.integrations.closet.base-url=http://127.0.0.1:18862",
        "app.integrations.closet.key-id=primary", "app.integrations.closet.secret=management-test-secret-0123456789abcdef",
        "app.runtime.component-startup-probe.enabled=false"})
class ClosetCreationLiveContractTest {
    @Autowired WebApplicationContext context;
    private static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor jwt(String permission) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.claim("username", "fixture"))
                .authorities(() -> permission);
    }
    @Test void signedGatewayTalksToRealTemplateAndCapabilityEndpoints() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var base = "/api/v1/closet/creations";
        mvc.perform(get(base + "/templates").with(jwt("closet:creation:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(3));
        mvc.perform(get(base + "/capabilities").with(jwt("closet:creation:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
        mvc.perform(put(base + "/capabilities").with(jwt("closet:creation:write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"fixture\",\"model\":\"fixture-image\",\"purpose\":\"ART_CREATION\",\"maxReferences\":4,\"verified\":false,\"version\":0,\"notes\":\"local test\",\"outputOptions\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pricingKey").value(org.hamcrest.Matchers.matchesPattern("art:[a-f0-9]{64}")));
        mvc.perform(get(base + "/usage?from=2026-11-01&to=2026-11-02").with(jwt("closet:creation:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.timeZone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.items").isArray());
        var result = mvc.perform(post(base + "/templates/sunlit-flatlay/versions").with(jwt("closet:creation:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fromVersionId\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        var id = JsonMapper.builder().build().readTree(result).path("data").path("versionId").asText();
        String publish = base + "/templates/sunlit-flatlay/versions/" + id + "/publish";
        mvc.perform(post(publish).with(jwt("closet:creation:write"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(publish).with(jwt("closet:creation:publish"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":0}"))
                .andExpect(status().isConflict());
    }
}
