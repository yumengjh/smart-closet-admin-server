package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Request fields and limits follow the source management contract. */
public final class ClosetBillingRequests {
    private ClosetBillingRequests() {}
    public record SettingsChange(Boolean enabled, Boolean tryonEnabled, Boolean imageEnabled, Boolean agentEnabled, Boolean showPrices) {}
    public record TierChange(@Size(min = 1, max = 80) String model, @Min(0) @Max(1_000_000_000) Long milliPointsPerUnit, Long effectiveFromMs) {}
    public record CostRateCreate(@NotNull @NotBlank @Size(max = 80) String model, @NotNull @Pattern(regexp = "^(PER_IMAGE|PER_MTOK_IN|PER_MTOK_OUT|PER_SECOND)$") String unit,
            @NotNull @Min(0) @Max(1_000_000_000_000L) Long amountMicro, @NotNull @Size(min = 1, max = 8) String currency, Long effectiveFromMs, @Size(max = 200) String note) {}
    public record BatchCreate(@NotNull @NotBlank @Size(max = 120) String name, @NotNull @Min(0) @Max(10_000_000) Long points, @Min(0) Long unitPriceMicro, @Size(max = 8) String currency,
            @Min(0) @Max(1000) Integer imageDailyLimit, @Min(0) @Max(1000) Integer agentDailyLimit, @Min(1) @Max(3650) Integer entitlementDays,
            @NotNull @Min(1) @Max(500) Integer quantity, Long validFromMs, Long validUntilMs) {}
    public record ProductCreate(@NotNull @Pattern(regexp = "^(POINTS|SUBSCRIPTION)$") String kind, @NotNull @NotBlank @Size(max = 120) String name, @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotNull @Min(1) @Max(100_000_000L) Long priceMinor, @Min(0) @Max(10_000_000) Long points, @Min(1) @Max(3650) Integer entDays,
            @Min(0) @Max(1000) Integer entImageDailyLimit, @Min(0) @Max(1000) Integer entAgentDailyLimit, Boolean enabled, Integer sortOrder) {}
    public record ProductUpdate(@Size(min = 1, max = 120) String name, @Min(1) @Max(100_000_000L) Long priceMinor, Boolean enabled, Integer sortOrder) {}
    public record PointsChange(@NotNull @Min(-10_000_000) @Max(10_000_000) Long points, @Size(max = 200) String note) {
        @AssertTrue @JsonIgnore public boolean isNonzero() { return points == null || points != 0; }
    }
    public record EntitlementCreate(@Min(0) @Max(1000) Integer imageDailyLimit, @Min(0) @Max(1000) Integer agentDailyLimit, @NotNull @Min(1) @Max(3650) Integer days, @Size(max = 80) String note) {}

}
