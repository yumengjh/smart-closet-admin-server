package com.yumg.starter.common.api;

import org.springframework.http.HttpStatus;

public enum ApiErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "Malformed JSON request"),
    UNKNOWN_FIELD(HttpStatus.BAD_REQUEST, "Request contains an unknown field"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Request parameter is invalid"),
    MISSING_REQUIRED_PARAMETER(HttpStatus.BAD_REQUEST, "A required request parameter is missing"),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Username or password is incorrect"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "Account is temporarily locked"),
    ACCOUNT_DISABLED(HttpStatus.FORBIDDEN, "Account is disabled"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access is denied"),
    OPTIMISTIC_LOCK_CONFLICT(HttpStatus.CONFLICT, "The resource was modified by another request"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with existing data"),
    LAST_SUPER_ADMIN_PROTECTED(HttpStatus.CONFLICT, "The last super administrator cannot be removed or disabled"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    ENDPOINT_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "This endpoint is temporarily disabled"),
    REGISTRATION_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "Registration is temporarily disabled"),
    AVATAR_FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Avatar image exceeds the size limit"),
    AVATAR_FORMAT_UNSUPPORTED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Avatar image format is not supported"),
    AVATAR_IMAGE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Avatar image is invalid"),
    AVATAR_PROCESSING_BUSY(HttpStatus.TOO_MANY_REQUESTS, "Avatar processing is busy"),
    AVATAR_CANDIDATE_EXPIRED(HttpStatus.GONE, "Avatar candidate has expired"),
    AVATAR_CANDIDATE_INVALID(HttpStatus.NOT_FOUND, "Avatar candidate is invalid"),
    AVATAR_GENERATOR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Avatar generator is unavailable"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Storage service is unavailable"),
    IP_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Client IP is not allowed"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not allowed for this resource"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    private final HttpStatus status;
    private final String message;

    ApiErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    HttpStatus status() { return status; }
    String message() { return message; }
}
