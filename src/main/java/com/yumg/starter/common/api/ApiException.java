package com.yumg.starter.common.api;

public class ApiException extends RuntimeException {
    private final ApiErrorCode error;

    private ApiException(ApiErrorCode error) {
        super(error.message());
        this.error = error;
    }

    public static ApiException notFound() {
        return new ApiException(ApiErrorCode.NOT_FOUND);
    }

    public static ApiException conflict() {
        return new ApiException(ApiErrorCode.CONFLICT);
    }
    public static ApiException invalidParameter() { return new ApiException(ApiErrorCode.INVALID_PARAMETER); }
    public static ApiException lastSuperAdminProtected() { return new ApiException(ApiErrorCode.LAST_SUPER_ADMIN_PROTECTED); }

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

    ApiErrorCode error() { return error; }
}
