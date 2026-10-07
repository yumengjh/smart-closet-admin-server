package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
public record ClosetCreateUserRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{2,80}$") String username,
        @NotBlank @Pattern(regexp = "^(USER|ADMIN)$") String role,
        @Size(min = 12, max = 200) String password) {}
