package com.yumg.starter.common.api;

public class ApiException extends RuntimeException {
    private final ApiErrorCode error;
    private final java.util.List<FieldViolation> violations;

    private ApiException(ApiErrorCode error) { this(error, error.message(), java.util.List.of()); }
    private ApiException(ApiErrorCode error, String message, java.util.List<FieldViolation> violations) {
        super(message == null || message.isBlank() ? error.message() : message);
        this.error = error;
        this.violations = violations == null ? java.util.List.of() : java.util.List.copyOf(violations);
    }

    public static ApiException closetLastAdminProtected() { return new ApiException(ApiErrorCode.CLOSET_LAST_ADMIN_PROTECTED); }
    public static ApiException closetServiceUnavailable() { return new ApiException(ApiErrorCode.CLOSET_SERVICE_UNAVAILABLE); }
    public static ApiException closetIntegrationMisconfigured() { return new ApiException(ApiErrorCode.CLOSET_INTEGRATION_MISCONFIGURED); }
    public static ApiException fromUpstream(ApiErrorCode error, String message, java.util.List<FieldViolation> violations) {
        return new ApiException(error, message, violations);
    }
    public java.util.List<FieldViolation> violations() { return violations; }

    public static ApiException notFound() {
        return new ApiException(ApiErrorCode.NOT_FOUND);
    }

    public static ApiException conflict() {
        return new ApiException(ApiErrorCode.CONFLICT);
    }
    public static ApiException invalidParameter() { return new ApiException(ApiErrorCode.INVALID_PARAMETER); }
    public static ApiException lastSuperAdminProtected() { return new ApiException(ApiErrorCode.LAST_SUPER_ADMIN_PROTECTED); }
    public static ApiException systemRoleProtected() { return new ApiException(ApiErrorCode.SYSTEM_ROLE_PROTECTED); }

    public static ApiException rateLimited() {
        return new ApiException(ApiErrorCode.RATE_LIMITED);
    }
    public static ApiException registrationDisabled() { return new ApiException(ApiErrorCode.REGISTRATION_DISABLED); }
    public static ApiException avatarFileTooLarge() { return new ApiException(ApiErrorCode.AVATAR_FILE_TOO_LARGE); }
    public static ApiException avatarFormatUnsupported() { return new ApiException(ApiErrorCode.AVATAR_FORMAT_UNSUPPORTED); }
    public static ApiException avatarImageInvalid() { return new ApiException(ApiErrorCode.AVATAR_IMAGE_INVALID); }
    public static ApiException avatarProcessingBusy() { return new ApiException(ApiErrorCode.AVATAR_PROCESSING_BUSY); }
    public static ApiException avatarCandidateExpired() { return new ApiException(ApiErrorCode.AVATAR_CANDIDATE_EXPIRED); }
    public static ApiException avatarCandidateInvalid() { return new ApiException(ApiErrorCode.AVATAR_CANDIDATE_INVALID); }
    public static ApiException avatarGeneratorUnavailable() { return new ApiException(ApiErrorCode.AVATAR_GENERATOR_UNAVAILABLE); }
    public static ApiException storageUnavailable() { return new ApiException(ApiErrorCode.STORAGE_UNAVAILABLE); }

    public static ApiException unauthorized() {
        return new ApiException(ApiErrorCode.AUTHENTICATION_REQUIRED);
    }

    public static ApiException invalidCredentials() { return new ApiException(ApiErrorCode.INVALID_CREDENTIALS); }
    public static ApiException accountLocked() { return new ApiException(ApiErrorCode.ACCOUNT_LOCKED); }
    public static ApiException accountDisabled() { return new ApiException(ApiErrorCode.ACCOUNT_DISABLED); }

    public ApiErrorCode error() { return error; }
}
