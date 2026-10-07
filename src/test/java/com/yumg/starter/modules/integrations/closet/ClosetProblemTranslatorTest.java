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
    private static RestClientResponseException error(HttpStatus status, String body) {
        return new RestClientResponseException("upstream", status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
