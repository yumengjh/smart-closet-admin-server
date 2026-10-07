package com.yumg.starter.modules.integrations.closet.api.dto;
import jakarta.validation.constraints.NotNull;
public record ClosetStatusRequest(@NotNull Boolean enabled) {}
