package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record ClosetCreateInviteRequest(@Size(max = 100) String note,
        @Min(1) @Max(100) Integer maxUses, @Min(1) @Max(90) Integer expiresInDays) {}
