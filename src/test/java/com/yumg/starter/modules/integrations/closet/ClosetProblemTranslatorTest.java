package com.yumg.starter.modules.integrations.closet;

import static org.assertj.core.api.Assertions.assertThat;
import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetProblemTranslator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

class ClosetProblemTranslatorTest {
    private final ClosetProblemTranslator translator = new ClosetProblemTranslator(new ObjectMapper());
    @Test void mapsUnauthorizedAsMisconfigurationAndTransportErrorsAsUnavailable() {
        assertThat(translator.translate(error(HttpStatus.UNAUTHORIZED, "{\"code\":\"MANAGEMENT_SIGNATURE_INVALID\"}"))
                .error()).isEqualTo(ApiErrorCode.CLOSET_INTEGRATION_MISCONFIGURED);
        assertThat(translator.translate(error(HttpStatus.BAD_GATEWAY, "{}")).error())
                .isEqualTo(ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE);
        assertThat(translator.translate(new ResourceAccessException("Read timed out")).error())
                .isEqualTo(ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE);
    }
    @Test void presetVerificationFailuresAreIndependentOfTheGatewayLogin() {
        assertThat(translator.translate(error(HttpStatus.UNAUTHORIZED, "{\"code\":\"BAD_CREDENTIALS\"}")).error())
                .isEqualTo(ApiErrorCode.CLOSET_PRESET_SOURCE_BAD_CREDENTIALS);
        assertThat(translator.translate(error(HttpStatus.UNAUTHORIZED, "{\"code\":\"PRESET_SOURCE_SESSION_EXPIRED\"}")).error())
                .isEqualTo(ApiErrorCode.CLOSET_PRESET_SOURCE_SESSION_EXPIRED);
    }
    @Test void templateNotFoundKeeps404Semantics() {
        var error = translator.translate(error(HttpStatus.NOT_FOUND, "{\"code\":\"TEMPLATE_NOT_FOUND\",\"detail\":\"template missing\"}"));
        assertThat(error.error()).isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThat(error.getMessage()).isEqualTo("template missing");
    }
    @Test void tryContentFailuresKeepTheirStatusAndPoseHitsReachTheAdministrator() {
        var rejected = translator.translate(error(HttpStatus.UNPROCESSABLE_CONTENT,
                "{\"code\":\"POSE_REJECTED\",\"detail\":\"rejected\",\"hits\":[{\"kind\":\"SUGGESTIVE\",\"term\":\"撩裙\",\"reason\":\"性暗示\"},{\"kind\":\"NUDITY\",\"term\":\"\",\"reason\":\"裸露\"},{\"term\":\"x\"}]}"));
        assertThat(rejected.error()).isEqualTo(ApiErrorCode.CLOSET_POSE_REJECTED);
        assertThat(rejected.error().status().value()).isEqualTo(422);
        assertThat(rejected.violations()).extracting(v -> v.field() + "|" + v.message())
                .containsExactly("SUGGESTIVE|撩裙：性暗示", "NUDITY|裸露");
        assertThat(translator.translate(error(HttpStatus.NOT_FOUND, "{\"code\":\"LOOK_NOT_FOUND\"}")).error())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThat(translator.translate(error(HttpStatus.NOT_FOUND, "{\"code\":\"POSE_NOT_FOUND\"}")).error())
                .isEqualTo(ApiErrorCode.NOT_FOUND);
        assertThat(translator.translate(error(HttpStatus.PAYLOAD_TOO_LARGE, "{\"code\":\"LOOK_IMAGE_TOO_LARGE\"}")).error())
                .isEqualTo(ApiErrorCode.CLOSET_REQUEST_TOO_LARGE);
        assertThat(translator.translate(error(HttpStatus.TOO_MANY_REQUESTS, "{\"code\":\"POSE_EXTRACT_LIMIT\"}")).error())
                .isEqualTo(ApiErrorCode.RATE_LIMITED);
        var conflict = translator.translate(error(HttpStatus.CONFLICT, "{\"code\":\"LOOK_CATEGORY_NOT_EMPTY\",\"detail\":\"分类下还有整套\"}"));
        assertThat(conflict.error()).isEqualTo(ApiErrorCode.CONFLICT);
        assertThat(conflict.getMessage()).isEqualTo("分类下还有整套");
        assertThat(translator.translate(error(HttpStatus.BAD_REQUEST, "{\"code\":\"LOOK_IMAGE_REQUIRED\"}")).error())
                .isEqualTo(ApiErrorCode.INVALID_PARAMETER);
        var unavailable = translator.translate(error(HttpStatus.SERVICE_UNAVAILABLE, "{\"code\":\"POSE_EXTRACT_UNAVAILABLE\",\"detail\":\"provider secret\"}"));
        assertThat(unavailable.error()).isEqualTo(ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE);
        assertThat(unavailable.getMessage()).doesNotContain("provider secret");
    }
    private static RestClientResponseException error(HttpStatus status, String body) {
        return new RestClientResponseException("upstream", status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
