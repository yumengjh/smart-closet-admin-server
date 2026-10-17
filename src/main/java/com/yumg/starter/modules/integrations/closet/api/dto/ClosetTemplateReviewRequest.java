package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;

public record ClosetTemplateReviewRequest(
        @Pattern(regexp = "^(PENDING_REVIEW|APPROVED|REJECTED)$") String reviewStatus,
        Boolean enabled, @DecimalMin("0") @DecimalMax("5") Double bonus) {}
