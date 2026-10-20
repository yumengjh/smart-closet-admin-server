package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.Pattern;

public record ClosetRegistrationRequest(@Pattern(regexp = "^(CLOSED|INVITE|OPEN)$") String mode) {}
