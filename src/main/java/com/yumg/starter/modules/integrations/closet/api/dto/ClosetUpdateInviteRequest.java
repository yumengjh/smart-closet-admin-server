package com.yumg.starter.modules.integrations.closet.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Duration;

public record ClosetUpdateInviteRequest(@Size(max = 100) String note,
        @Min(1) @Max(100) Integer maxUses, Long expiresAtMs) {
    @AssertTrue(message = "至少提供一个修改字段") @JsonIgnore
    public boolean isUpdatePresent() { return note != null || maxUses != null || expiresAtMs != null; }

    @AssertTrue(message = "有效期须在现在之后 90 天以内") @JsonIgnore
    public boolean isExpiryInRange() {
        long now = System.currentTimeMillis();
        return expiresAtMs == null || (expiresAtMs > now && expiresAtMs <= now + Duration.ofDays(90).toMillis());
    }
}
