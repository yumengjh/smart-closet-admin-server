package com.yumg.starter.modules.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.User;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponentRegistry;
import com.yumg.starter.modules.storage.application.LocalStorageRuntimeComponent;
import com.yumg.starter.modules.storage.application.StorageService;
import com.yumg.starter.modules.storage.application.StoredObjectContent;
import com.yumg.starter.modules.storage.application.StoredObjectRef;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserAvatarServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final StorageService storage = mock(StorageService.class);
    private final DiceBearAvatarGenerator generator = mock(DiceBearAvatarGenerator.class);
    private final RuntimeComponentRegistry components = mock(RuntimeComponentRegistry.class);
    private final User user = new User("alice", "Alice", "hash");
    private UserAvatarService service;

    @BeforeEach
    void setUp() {
        service = new UserAvatarService(users, storage, generator, components);
        when(users.findByAvatarPublicId(user.getAvatarPublicId())).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(components.isUp(LocalStorageRuntimeComponent.CODE)).thenReturn(true);
        when(components.isUp(DiceBearAvatarGenerator.CODE)).thenReturn(true);
    }

    @Test
    void readsAnExistingStoredAvatar() {
        user.useUploadedAvatar("object-1");
        when(storage.open("object-1"))
                .thenReturn(new StoredObjectContent("image/png", 3, "sha", new byte[] {1, 2, 3}));

        AvatarContent content = service.read(user.getAvatarPublicId());

        assertThat(content.contentType()).isEqualTo("image/png");
        assertThat(content.fallback()).isFalse();
        assertThat(content.bytes()).containsExactly(1, 2, 3);
    }

    @Test
    void lazilyGeneratesAndCachesTheInitialSystemAvatar() {
        byte[] svg = "<svg/>".getBytes();
        when(generator.generate(user.getAvatarSeed(), 256)).thenReturn(svg);
        when(storage.put(any(), eq("image/svg+xml"), eq("svg"), eq(svg)))
                .thenReturn(new StoredObjectRef("generated", "image/svg+xml", svg.length, "sha"));

        AvatarContent content = service.read(user.getAvatarPublicId());

        assertThat(content.bytes()).isEqualTo(svg);
        assertThat(user.getAvatarObjectId()).isEqualTo("generated");
        verify(users).save(user);
    }

    @Test
    void returnsBuiltInFallbackWhenGenerationFails() {
        when(generator.generate(anyString(), eq(256))).thenThrow(new IllegalStateException("down"));

        AvatarContent content = service.read(user.getAvatarPublicId());

        assertThat(content.fallback()).isTrue();
        assertThat(content.contentType()).isEqualTo("image/svg+xml");
        assertThat(content.bytes()).isNotEmpty();
        verify(components).reportFailure(DiceBearAvatarGenerator.CODE, "avatar generation failed");
    }

    @Test
    void uploadedAvatarNeverFallsBackToRegeneration() {
        user.useUploadedAvatar("missing");
        when(storage.open("missing")).thenThrow(new IllegalStateException("missing"));

        AvatarContent content = service.read(user.getAvatarPublicId());

        assertThat(content.fallback()).isTrue();
        verify(components).reportFailure(LocalStorageRuntimeComponent.CODE, "avatar object read failed");
    }
}
