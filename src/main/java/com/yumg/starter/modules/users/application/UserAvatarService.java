package com.yumg.starter.modules.users.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.AvatarType;
import com.yumg.starter.entities.User;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponentRegistry;
import com.yumg.starter.modules.storage.application.LocalStorageRuntimeComponent;
import com.yumg.starter.modules.storage.application.StorageNamespace;
import com.yumg.starter.modules.storage.application.StorageService;
import com.yumg.starter.modules.storage.application.StoredObjectContent;
import com.yumg.starter.modules.storage.application.StoredObjectRef;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class UserAvatarService {
    private final UserRepository users;
    private final StorageService storage;
    private final DiceBearAvatarGenerator generator;
    private final RuntimeComponentRegistry components;
    private final byte[] fallback;
    private final String fallbackEtag;
    private final ConcurrentHashMap<String, Object> generationLocks = new ConcurrentHashMap<>();

    public UserAvatarService(UserRepository users, StorageService storage,
                             DiceBearAvatarGenerator generator,
                             RuntimeComponentRegistry components) {
        this.users = users;
        this.storage = storage;
        this.generator = generator;
        this.components = components;
        try {
            this.fallback = new ClassPathResource("avatars/default-lorelei.svg")
                    .getInputStream().readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Built-in avatar fallback is missing", exception);
        }
        this.fallbackEtag = sha256(fallback);
    }

    public AvatarContent read(String publicId) {
        User user = users.findByAvatarPublicId(publicId).orElseThrow(ApiException::notFound);
        if (user.getAvatarObjectId() != null && components.isUp(LocalStorageRuntimeComponent.CODE)) {
            try {
                return content(storage.open(user.getAvatarObjectId()), false);
            } catch (RuntimeException exception) {
                components.reportFailure(LocalStorageRuntimeComponent.CODE, "avatar object read failed");
            }
        }
        if (user.getAvatarType() != AvatarType.UPLOADED
                && components.isUp(DiceBearAvatarGenerator.CODE)
                && components.isUp(LocalStorageRuntimeComponent.CODE)) {
            AvatarContent generated = generateAndCache(user);
            if (generated != null) return generated;
        }
        return fallback();
    }

    private AvatarContent generateAndCache(User user) {
        Object lock = generationLocks.computeIfAbsent(user.getId(), ignored -> new Object());
        try {
            synchronized (lock) {
                User current = users.findById(user.getId()).orElse(user);
                if (current.getAvatarObjectId() != null) {
                    try { return content(storage.open(current.getAvatarObjectId()), false); }
                    catch (RuntimeException exception) {
                        components.reportFailure(LocalStorageRuntimeComponent.CODE,
                                "avatar object read failed");
                    }
                }
                byte[] svg;
                try {
                    svg = generator.generate(current.getAvatarSeed(), 256);
                } catch (RuntimeException exception) {
                    components.reportFailure(DiceBearAvatarGenerator.CODE, "avatar generation failed");
                    return null;
                }
                StoredObjectRef stored;
                try {
                    stored = storage.put(StorageNamespace.AVATAR_GENERATED,
                            "image/svg+xml", "svg", svg);
                } catch (RuntimeException exception) {
                    components.reportFailure(LocalStorageRuntimeComponent.CODE,
                            "generated avatar storage failed");
                    return null;
                }
                try {
                    current.cacheGeneratedAvatar(stored.id());
                    users.save(current);
                    return new AvatarContent("image/svg+xml", svg.length, stored.sha256(), svg, false);
                } catch (RuntimeException exception) {
                    try { storage.delete(stored.id()); } catch (RuntimeException cleanup) {
                        exception.addSuppressed(cleanup);
                    }
                    return null;
                }
            }
        } finally {
            generationLocks.remove(user.getId(), lock);
        }
    }

    private AvatarContent content(StoredObjectContent stored, boolean fallback) {
        return new AvatarContent(stored.contentType(), stored.sizeBytes(), stored.sha256(),
                stored.bytes(), fallback);
    }

    private AvatarContent fallback() {
        return new AvatarContent("image/svg+xml", fallback.length, fallbackEtag, fallback, true);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
