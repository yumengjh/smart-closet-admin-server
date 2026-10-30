package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
public record ClosetCreateUserRequest(
        @NotBlank @Pattern(regexp = com.yumg.starter.modules.integrations.closet.application.ClosetUsernames.NEW) String username,
        @NotBlank @Pattern(regexp = "^(USER|ADMIN)$") String role,
        @Size(min = 12, max = 200) String password,
        @Size(max = 40) String displayName) {}
