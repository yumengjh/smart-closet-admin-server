package com.yumg.starter.modules.integrations.closet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Identifies one pending style word by its text and the mapping the assistant proposed; {@code styles} optionally corrects it on approval. */
public record ClosetStyleTermReviewRequest(
        @NotBlank @Size(max = 40) String term,
        @NotEmpty @Size(max = 2) List<@NotBlank @Size(max = 20) String> proposedStyles,
        @Size(max = 2) List<@NotBlank @Size(max = 20) String> styles) {}
