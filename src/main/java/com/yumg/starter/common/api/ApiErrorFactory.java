package com.yumg.starter.common.api;

import com.yumg.starter.common.web.TraceIdFilter;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;

final class ApiErrorFactory {
    private ApiErrorFactory() {}

    static ApiError create(ApiErrorCode error, List<FieldViolation> violations) {
        return new ApiError(error.name(), error.message(), traceId(), violations, System.currentTimeMillis());
    }

    static ApiError create(ApiErrorCode error, String message, List<FieldViolation> violations) {
        return new ApiError(error.name(), message == null || message.isBlank() ? error.message() : message,
                traceId(), violations, System.currentTimeMillis());
    }

    static ResponseEntity<ApiError> response(ApiErrorCode error, List<FieldViolation> violations) {
        ApiError body = create(error, violations);
        return ResponseEntity.status(error.status())
            .header(TraceIdFilter.TRACE_ID_HEADER, body.traceId())
            .body(body);
    }

    static ResponseEntity<ApiError> response(ApiErrorCode error, String message, List<FieldViolation> violations) {
        ApiError body = create(error, message, violations);
        return ResponseEntity.status(error.status())
            .header(TraceIdFilter.TRACE_ID_HEADER, body.traceId())
            .body(body);
    }

    private static String traceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        return traceId == null || traceId.isBlank()
            ? UUID.randomUUID().toString().replace("-", "") : traceId;
    }
}
