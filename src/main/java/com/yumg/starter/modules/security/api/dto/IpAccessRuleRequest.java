package com.yumg.starter.modules.security.api.dto;

import com.yumg.starter.entities.IpAccessRule.Type;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record IpAccessRuleRequest(@NotNull Type type,
                                  @NotBlank @Size(max = 64) @Pattern(regexp = "[0-9a-fA-F:.]+(?:/[0-9]{1,3})?") String network,
                                  Long expiresAt, @NotBlank @Size(max = 500) String reason) { }
