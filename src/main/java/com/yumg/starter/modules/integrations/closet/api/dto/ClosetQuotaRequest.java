package com.yumg.starter.modules.integrations.closet.api.dto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
public record ClosetQuotaRequest(@Min(0) @Max(1000) Integer imageDailyLimit,
                                 @Min(0) @Max(1000) Integer agentDailyLimit) {}
