package com.yumg.starter.modules.integrations.closet.api.dto;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
public record ClosetRoleRequest(@NotBlank @Pattern(regexp = "^(USER|ADMIN)$") String role) {}
