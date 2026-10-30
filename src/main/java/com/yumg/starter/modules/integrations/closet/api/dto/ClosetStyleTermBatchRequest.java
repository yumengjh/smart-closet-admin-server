package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ClosetStyleTermBatchRequest(
        @Pattern(regexp = "^(APPROVE|REJECT)$") String action,
        @NotEmpty @Size(max = 50) List<@Valid Item> items) {
    public record Item(@jakarta.validation.constraints.NotBlank @Size(max = 40) String term,
                       @NotEmpty @Size(max = 2) List<@jakarta.validation.constraints.NotBlank @Size(max = 20) String> proposedStyles) {}
}
