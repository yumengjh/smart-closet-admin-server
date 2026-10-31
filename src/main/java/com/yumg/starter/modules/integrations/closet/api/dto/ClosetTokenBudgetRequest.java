package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** {@code dailyTokenLimit} null restores the closet default limit. */
public record ClosetTokenBudgetRequest(@Min(1) @Max(1_000_000_000L) Long dailyTokenLimit) {}
