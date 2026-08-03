package com.yumg.starter.modules.users.api.dto;

import com.yumg.starter.entities.AvatarCandidate;
import java.time.Instant;

public record AvatarCandidateResponse(String candidateId, String previewUrl, Instant expiresAt) {
    public static AvatarCandidateResponse from(AvatarCandidate candidate) {
        return new AvatarCandidateResponse(candidate.getPublicId(),
                "/api/v1/avatar-candidates/" + candidate.getPublicId(), candidate.getExpiresAt());
    }
}
