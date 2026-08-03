package com.yumg.starter.modules.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.AvatarCandidate;
import com.yumg.starter.entities.AvatarType;
import com.yumg.starter.entities.User;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponentRegistry;
import com.yumg.starter.modules.security.application.AuditService;
import com.yumg.starter.modules.storage.application.LocalStorageRuntimeComponent;
import com.yumg.starter.modules.storage.application.StorageService;
import com.yumg.starter.modules.storage.application.StoredObjectContent;
import com.yumg.starter.modules.storage.application.StoredObjectRef;
import com.yumg.starter.modules.users.infrastructure.AvatarCandidateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserAvatarManagementServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final AvatarCandidateRepository candidates = mock(AvatarCandidateRepository.class);
    private final AvatarImageProcessor images = mock(AvatarImageProcessor.class);
    private final DiceBearAvatarGenerator generator = mock(DiceBearAvatarGenerator.class);
    private final StorageService storage = mock(StorageService.class);
    private final RuntimeComponentRegistry components = mock(RuntimeComponentRegistry.class);
    private final AuditService audit = mock(AuditService.class);
    private final User user = new User("alice", "Alice", "hash");
    private UserAvatarManagementService service;

    @BeforeEach
    void setUp() {
        service = new UserAvatarManagementService(users, candidates, images, generator,
                storage, components, audit);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(components.isUp(LocalStorageRuntimeComponent.CODE)).thenReturn(true);
        when(components.isUp(DiceBearAvatarGenerator.CODE)).thenReturn(true);
        when(candidates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(candidates.findAllByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
                anyString(), any())).thenReturn(List.of());
    }

    @Test
    void uploadsResetsAndDeletesThePreviousObject() {
        user.useUploadedAvatar("old-object");
        byte[] png = {1, 2, 3};
        when(images.process(png)).thenReturn(new ProcessedAvatar("image/png", png));
        when(storage.put(any(), anyString(), anyString(), any()))
                .thenReturn(new StoredObjectRef("new-object", "image/png", 3, "sha"));

        var uploaded = service.upload(user.getId(), png);

        assertThat(uploaded.avatarUrl()).contains("?v=");
        assertThat(user.getAvatarType()).isEqualTo(AvatarType.UPLOADED);
        assertThat(user.getAvatarObjectId()).isEqualTo("new-object");
        verify(candidates).deleteAllByStorageObjectId("old-object");
        verify(storage).delete("old-object");
        verify(audit).event("USER_AVATAR_UPLOADED", "User", user.getId());

        service.reset(user.getId());
        assertThat(user.getAvatarType()).isEqualTo(AvatarType.SYSTEM_INITIAL);
        assertThat(user.getAvatarObjectId()).isNull();
        verify(storage).delete("new-object");
        verify(audit).event("USER_AVATAR_RESET", "User", user.getId());
    }

    @Test
    void createsReadsAndAppliesASystemCandidate() {
        byte[] svg = "<svg/>".getBytes();
        when(generator.generate(anyString(), org.mockito.ArgumentMatchers.eq(256))).thenReturn(svg);
        when(storage.put(any(), anyString(), anyString(), any()))
                .thenReturn(new StoredObjectRef("candidate-object", "image/svg+xml", svg.length, "sha"));

        var created = service.createCandidate(user.getId());
        var captor = org.mockito.ArgumentCaptor.forClass(AvatarCandidate.class);
        verify(candidates).save(captor.capture());
        AvatarCandidate candidate = captor.getValue();
        assertThat(created.candidateId()).isEqualTo(candidate.getPublicId());
        assertThat(created.previewUrl()).doesNotContain(user.getId()).doesNotContain(candidate.getSeed());

        when(candidates.findByPublicId(candidate.getPublicId())).thenReturn(Optional.of(candidate));
        when(storage.open("candidate-object"))
                .thenReturn(new StoredObjectContent("image/svg+xml", svg.length, "sha", svg));
        assertThat(service.readCandidate(candidate.getPublicId()).bytes()).isEqualTo(svg);

        service.applyCandidate(user.getId(), candidate.getPublicId());
        assertThat(candidate.isConsumed()).isTrue();
        assertThat(user.getAvatarType()).isEqualTo(AvatarType.SYSTEM_RANDOM);
        assertThat(user.getAvatarObjectId()).isEqualTo("candidate-object");
        verify(audit).event("USER_AVATAR_RANDOMIZED", "User", user.getId());
    }

    @Test
    void mapsProcessingAndDependencyFailuresToStableApiErrors() {
        when(images.process(any())).thenThrow(new IllegalArgumentException("Unsupported image"));
        assertThatThrownBy(() -> service.upload(user.getId(), new byte[] {1}))
                .hasMessage("Avatar image format is not supported");

        when(components.isUp(DiceBearAvatarGenerator.CODE)).thenReturn(false);
        assertThatThrownBy(() -> service.createCandidate(user.getId()))
                .hasMessage("Avatar generator is unavailable");

        when(components.isUp(LocalStorageRuntimeComponent.CODE)).thenReturn(false);
        assertThatThrownBy(() -> service.upload(user.getId(), new byte[] {1}))
                .hasMessage("Storage service is unavailable");
    }

    @Test
    void rejectsForeignAndExpiredCandidatesAndSupportsAdminReset() {
        AvatarCandidate foreign = new AvatarCandidate("another-user", "seed", "object",
                Instant.now().plusSeconds(60));
        when(candidates.findByPublicId(foreign.getPublicId())).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.applyCandidate(user.getId(), foreign.getPublicId()))
                .hasMessage("Avatar candidate is invalid");

        AvatarCandidate expired = new AvatarCandidate(user.getId(), "seed", "object",
                Instant.now().minusSeconds(1));
        when(candidates.findByPublicId(expired.getPublicId())).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.readCandidate(expired.getPublicId()))
                .hasMessage("Avatar candidate has expired");

        user.useUploadedAvatar("admin-old");
        service.adminReset(user.getId());
        verify(storage).delete("admin-old");
        verify(audit).event("ADMIN_USER_AVATAR_RESET", "User", user.getId());
    }
}
