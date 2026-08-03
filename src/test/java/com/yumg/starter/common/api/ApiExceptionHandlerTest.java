package com.yumg.starter.common.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

class ApiExceptionHandlerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
        .setControllerAdvice(new ApiExceptionHandler())
        .addFilters(new com.yumg.starter.common.web.TraceIdFilter())
        .build();

    @Test
    void invalidInputReturnsStableErrorWithFieldViolationAndTraceId() throws Exception {
        mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(header().exists("X-Trace-Id"))
            .andExpect(result -> assertThat(result.getResponse().getHeader("X-Trace-Id"))
                .isEqualTo(result.getResponse().getContentAsString().replaceAll(
                    ".*\\\"traceId\\\":\\\"([^\\\"]+)\\\".*", "$1")))
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("Request validation failed"))
            .andExpect(jsonPath("$.traceId").isNotEmpty())
            .andExpect(jsonPath("$.violations[0].field").value("name"))
            .andExpect(jsonPath("$.violations[0].message").isNotEmpty())
            .andExpect(jsonPath("$.timestamp").isNumber());
    }

    @Test
    void malformedJsonDoesNotExposeImplementationDetails() throws Exception {
        mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
            .andExpect(jsonPath("$.message").value("Malformed JSON request"))
            .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void unknownJsonFieldUsesDedicatedStableErrorCode() {
        var handler = new ApiExceptionHandler();
        var unknown = new tools.jackson.databind.exc.UnrecognizedPropertyException(
                null, "unknown field", null, TestRequest.class, "extra", java.util.List.of());

        var response = handler.malformedJson(
                new org.springframework.http.converter.HttpMessageNotReadableException("bad body", unknown,
                        new org.springframework.mock.http.MockHttpInputMessage(new byte[0])));

        assertError(response, 400, "UNKNOWN_FIELD");
    }

    @Test
    void rateLimitUsesStableCodeAndStatus() throws Exception {
        mvc.perform(post("/limited"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
            .andExpect(jsonPath("$.message").value("Too many requests"));
    }

    @Test
    void methodParameterViolationUsesValidationEnvelope() throws Exception {
        mvc.perform(post("/parameter").param("count", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.violations[0].field").value("count"));
    }

    @Test
    void unexpectedFailureDoesNotExposeDiagnosticDetails() throws Exception {
        mvc.perform(post("/unexpected"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
            .andExpect(jsonPath("$.exception").doesNotExist())
            .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void requiredExceptionMatrixUsesStableSafeResponses() {
        assertError(new ApiExceptionHandler().authentication(), 401, "AUTHENTICATION_REQUIRED");
        assertError(new ApiExceptionHandler().authorization(), 403, "ACCESS_DENIED");
        assertError(new ApiExceptionHandler().optimisticLock(), 409, "OPTIMISTIC_LOCK_CONFLICT");
        assertError(new ApiExceptionHandler().notFound(), 404, "NOT_FOUND");
        assertError(new ApiExceptionHandler().conflict(), 409, "CONFLICT");
        var unexpected = new ApiExceptionHandler().unexpected();
        assertError(unexpected, 500, "INTERNAL_ERROR");
        assertThat(unexpected.getBody().message()).doesNotContain("Exception", "stack", "secret");
        assertError(new ApiExceptionHandler().maxUploadSize(), 413, "AVATAR_FILE_TOO_LARGE");
    }

    @Test
    void frameworkExceptionsAreAssignedToRequiredMappings() {
        var handler = new ApiExceptionHandler();
        assertThat(handler.authentication(new BadCredentialsException("secret")).getStatusCode().value()).isEqualTo(401);
        assertThat(handler.authorization(new AccessDeniedException("secret")).getStatusCode().value()).isEqualTo(403);
        assertThat(handler.optimisticLock(new ObjectOptimisticLockingFailureException(Object.class, 1)).getStatusCode().value()).isEqualTo(409);
        assertThat(handler.notFound(new EntityNotFoundException("secret")).getStatusCode().value()).isEqualTo(404);
        assertThat(handler.conflict(new DataIntegrityViolationException("secret")).getStatusCode().value()).isEqualTo(409);
        assertThat(handler.unexpected(new IllegalStateException("secret")).getBody().message()).isEqualTo("An unexpected error occurred");
    }

    private void assertError(org.springframework.http.ResponseEntity<ApiError> response,
                             int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().traceId()).isNotBlank();
    }

    @RestController
    static class TestController {
        @PostMapping("/test")
        TestRequest test(@Valid @RequestBody TestRequest request) {
            return request;
        }

        @PostMapping("/limited")
        void limited() {
            throw ApiException.rateLimited();
        }

        @PostMapping("/parameter")
        void parameter(@RequestParam @Min(1) int count) {
        }

        @PostMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException("secret implementation detail");
        }
    }

    record TestRequest(@NotBlank String name) {}
}
