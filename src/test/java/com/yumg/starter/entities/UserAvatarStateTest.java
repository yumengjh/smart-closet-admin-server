package com.yumg.starter.entities;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserAvatarStateTest {

    @Test
    void createsAnOpaquePublicIdentityAndDeterministicInitialSeed() {
        User first = new User("alice", "Alice", "hash");
        User sameIdentity = new User("other", "Other", "hash");
        sameIdentity.initializeAvatarIdentity(first.getId());

        assertThat(first.getId()).isNotBlank();
        assertThat(first.getAvatarPublicId()).isNotBlank().isNotEqualTo(first.getId());
        assertThat(first.getAvatarType()).isEqualTo(AvatarType.SYSTEM_INITIAL);
        assertThat(first.getAvatarSeed()).isEqualTo(sameIdentity.getAvatarSeed());
        assertThat(first.getAvatarObjectId()).isNull();
        assertThat(first.getAvatarRevision()).isZero();
    }

    @Test
    void changesAvatarWithoutInvalidatingAuthenticationTokens() {
        User user = new User("alice", "Alice", "hash");
        long tokenVersion = user.getTokenVersion();

        user.useUploadedAvatar("uploaded-object");
        assertThat(user.getAvatarType()).isEqualTo(AvatarType.UPLOADED);
        assertThat(user.getAvatarObjectId()).isEqualTo("uploaded-object");
        assertThat(user.getAvatarRevision()).isEqualTo(1);

        user.useRandomAvatar("random-seed", "generated-object");
        assertThat(user.getAvatarType()).isEqualTo(AvatarType.SYSTEM_RANDOM);
        assertThat(user.getAvatarSeed()).isEqualTo("random-seed");
        assertThat(user.getAvatarObjectId()).isEqualTo("generated-object");
        assertThat(user.getAvatarRevision()).isEqualTo(2);

        user.resetAvatar();
        assertThat(user.getAvatarType()).isEqualTo(AvatarType.SYSTEM_INITIAL);
        assertThat(user.getAvatarObjectId()).isNull();
        assertThat(user.getAvatarRevision()).isEqualTo(3);
        assertThat(user.getTokenVersion()).isEqualTo(tokenVersion);
    }
}
