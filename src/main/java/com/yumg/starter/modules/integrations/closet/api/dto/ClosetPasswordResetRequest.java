package com.yumg.starter.modules.integrations.closet.api.dto;
import jakarta.validation.constraints.Size;
public record ClosetPasswordResetRequest(@Size(min = 12, max = 200) String password) {}
