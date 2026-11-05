package com.yumg.starter.modules.integrations.closet.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/** Complete replacement payloads from the upstream try-on contract. */
public final class ClosetTryOnRequests {
    private ClosetTryOnRequests() {}
    public record SettingsValue(
            @NotNull @Pattern(regexp = "^(FORCE_OFF|FORCE_ON|FOLLOW_USER)$") String mode,
            @NotNull Boolean defaultEnabled,
            @NotNull @Min(0) @Max(86400L) Integer dailySeconds,
            @NotNull @Min(0) @Max(1000000000L) Integer dailyPoints,
            @NotNull @Min(0) @Max(1000000000000L) Long totalPoints,
            @NotNull @Min(1) @Max(3600L) Integer sessionSeconds,
            @NotNull @Min(1) @Max(120L) Integer startupSeconds,
            @NotNull @Min(1) @Max(120L) Integer firstFrameSeconds,
            @NotNull @Min(1) @Max(600L) Integer queueSeconds,
            @NotNull @Min(1) @Max(60L) Integer offerSeconds,
            @NotNull @Min(1) @Max(600L) Integer recordingSeconds,
            @NotNull @Min(1024) @Max(536870912L) Integer recordingBytes,
            @NotNull @Min(0) @Max(1099511627776L) Long storageBytes,
            @NotNull @Min(1) @Max(100L) Integer accountConcurrency,
            @NotNull @Min(0) @Max(3600L) Integer accountGuardPoints) {
        @AssertTrue(message = "首帧等待时间不能超过启动总时限") @JsonIgnore
        public boolean isFirstFrameWithinStartup() {
            return firstFrameSeconds == null || startupSeconds == null || firstFrameSeconds <= startupSeconds;
        }
    }
    public record SettingsChange(
            @NotNull @Min(0) Long revision,
            @NotNull @Valid SettingsValue value) {
    }
    public record Reset(
            @NotNull @Min(0) Long revision) {
    }
    public record UserPolicy(
            @NotNull @Pattern(regexp = "^(INHERIT|ENABLED|DISABLED)$") String accessMode,
            @Min(0) @Max(86400L) Integer dailySeconds,
            @Min(0) @Max(999L) Integer startupFailureLimit) {
    }
    public record CredentialInput(
            @NotNull @NotBlank @Size(max = 80) String name,
            @NotNull @Pattern(regexp = "^(china|global)$") String environment,
            @NotNull @Pattern(regexp = "^uk-[A-Za-z0-9_-]{8,500}$") String apiKey) {
        @Override public String toString() { return "CredentialInput[redacted]"; }
    }
    public record AccountChange(
            @NotNull @NotBlank @Size(max = 80) String name,
            @NotNull Boolean accepting,
            @NotNull @Min(1) @Max(100L) Integer weight,
            @NotNull @Min(1) @Max(100L) Integer concurrencyLimit) {
    }
    public record BudgetChange(
            @NotNull @Min(0) @Max(1000000000000L) Long budgetPoints) {
    }
}
