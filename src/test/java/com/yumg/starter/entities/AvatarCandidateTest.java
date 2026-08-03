package com.yumg.starter.entities;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AvatarCandidateTest {
    @Test
    void canOnlyBeAppliedOnceByItsOwnerBeforeExpiry() {
        Instant now = Instant.parse("2030-08-03T10:00:00Z");
        AvatarCandidate candidate = new AvatarCandidate(
                "user-1", "seed", "object-1", now.plusSeconds(1800));

        assertThat(candidate.belongsTo("user-1")).isTrue();
        assertThat(candidate.belongsTo("user-2")).isFalse();
        assertThat(candidate.isExpired(now)).isFalse();
        assertThat(candidate.isConsumed()).isFalse();

        candidate.consume(now.plusSeconds(60));

        assertThat(candidate.isConsumed()).isTrue();
        assertThat(candidate.isAvailableTo("user-1", now.plusSeconds(120))).isFalse();
    }

    @Test
    void usesAnOpaquePublicIdentifierAndTreatsDeadlineAsExpired() {
        Instant expiresAt = Instant.parse("2030-08-03T10:30:00Z");
        AvatarCandidate candidate = new AvatarCandidate("user-1", "seed", "object-1", expiresAt);

        assertThat(candidate.getPublicId()).matches("[0-9a-f-]{36}");
        assertThat(candidate.getPublicId()).doesNotContain("user-1", "object-1", "seed");
        assertThat(candidate.isExpired(expiresAt)).isTrue();
    }
}
