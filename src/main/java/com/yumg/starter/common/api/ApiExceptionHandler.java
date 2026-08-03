package com.yumg.starter.common.api;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldViolation(error.getField(), safeMessage(error.getDefaultMessage())))
            .toList();
        return ApiErrorFactory.response(ApiErrorCode.VALIDATION_FAILED, violations);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> methodValidation(HandlerMethodValidationException exception) {
        List<FieldViolation> violations = exception.getParameterValidationResults().stream()
            .flatMap(result -> result.getResolvableErrors().stream()
                .map(error -> new FieldViolation(parameterName(result), safeMessage(error.getDefaultMessage()))))
            .toList();
        return ApiErrorFactory.response(ApiErrorCode.VALIDATION_FAILED, violations);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedJson(HttpMessageNotReadableException exception) {
        if (hasUnknownField(exception)) {
            return ApiErrorFactory.response(ApiErrorCode.UNKNOWN_FIELD, List.of());
        }
        return ApiErrorFactory.response(ApiErrorCode.MALFORMED_JSON, List.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> authentication(AuthenticationException ignored) {
        return authentication();
    }

    ResponseEntity<ApiError> authentication() {
        return ApiErrorFactory.response(ApiErrorCode.AUTHENTICATION_REQUIRED, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> authorization(AccessDeniedException ignored) {
        return authorization();
    }

    ResponseEntity<ApiError> authorization() {
        return ApiErrorFactory.response(ApiErrorCode.ACCESS_DENIED, List.of());
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    ResponseEntity<ApiError> optimisticLock(Exception ignored) { return optimisticLock(); }
    ResponseEntity<ApiError> optimisticLock() {
        return ApiErrorFactory.response(ApiErrorCode.OPTIMISTIC_LOCK_CONFLICT, List.of());
    }

    @ExceptionHandler({EntityNotFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiError> notFound(Exception ignored) { return notFound(); }
    ResponseEntity<ApiError> notFound() {
        return ApiErrorFactory.response(ApiErrorCode.NOT_FOUND, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(DataIntegrityViolationException ignored) { return conflict(); }
    ResponseEntity<ApiError> conflict() {
        return ApiErrorFactory.response(ApiErrorCode.CONFLICT, List.of());
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> apiException(ApiException exception) {
        return ApiErrorFactory.response(exception.error(), List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMediaType() {
        return ApiErrorFactory.response(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE, List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> maxUploadSize(MaxUploadSizeExceededException ignored) {
        return maxUploadSize();
    }

    ResponseEntity<ApiError> maxUploadSize() {
        return ApiErrorFactory.response(ApiErrorCode.AVATAR_FILE_TOO_LARGE, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidParameter() {
        return ApiErrorFactory.response(ApiErrorCode.INVALID_PARAMETER, List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter() {
        return ApiErrorFactory.response(ApiErrorCode.MISSING_REQUIRED_PARAMETER, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed() {
        return ApiErrorFactory.response(ApiErrorCode.METHOD_NOT_ALLOWED, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("Unhandled request failure, traceId={}",
                org.slf4j.MDC.get(com.yumg.starter.common.web.TraceIdFilter.TRACE_ID_MDC_KEY), exception);
        return unexpected();
    }
    ResponseEntity<ApiError> unexpected() {
        return ApiErrorFactory.response(ApiErrorCode.INTERNAL_ERROR, List.of());
    }

    private String parameterName(org.springframework.validation.method.ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        return name == null ? "request" : name;
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "Invalid value" : message;
    }

    private boolean hasUnknownField(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof UnrecognizedPropertyException) return true;
        }
        return false;
    }
}
