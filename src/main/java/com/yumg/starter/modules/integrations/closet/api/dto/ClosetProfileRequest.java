package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ClosetProfileRequest(@NotNull @Size(max = 40) String displayName) {}
