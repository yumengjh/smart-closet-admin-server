package com.yumg.starter.modules.users.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.AvatarCandidate;
import com.yumg.starter.entities.User;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponentRegistry;
import com.yumg.starter.modules.security.application.AuditService;
import com.yumg.starter.modules.storage.application.LocalStorageRuntimeComponent;
import com.yumg.starter.modules.storage.application.StorageNamespace;
import com.yumg.starter.modules.storage.application.StorageService;
import com.yumg.starter.modules.storage.application.StoredObjectContent;
import com.yumg.starter.modules.users.api.dto.AvatarCandidateResponse;
import com.yumg.starter.modules.users.api.dto.CurrentUserResponse;
import com.yumg.starter.modules.users.infrastructure.AvatarCandidateRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class UserAvatarManagementService {
    private static final Duration CANDIDATE_TTL = Duration.ofMinutes(30);
    private static final int MAX_ACTIVE_CANDIDATES = 5;

    private final UserRepository users;
    private final AvatarCandidateRepository candidates;
    private final AvatarImageProcessor images;
    private final DiceBearAvatarGenerator generator;
    private final StorageService storage;
    private final RuntimeComponentRegistry components;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public UserAvatarManagementService(UserRepository users, AvatarCandidateRepository candidates,
            AvatarImageProcessor images, DiceBearAvatarGenerator generator, StorageService storage,
            RuntimeComponentRegistry components, AuditService audit) {
        this.users = users;
        this.candidates = candidates;
        this.images = images;
        this.generator = generator;
        this.storage = storage;
        this.components = components;
        this.audit = audit;
    }

    @Transactional
    public CurrentUserResponse upload(String userId, byte[] content) {
        requireStorage();
        ProcessedAvatar processed;
        try {
            processed = images.process(content);
        } catch (IllegalStateException exception) {
            throw ApiException.avatarProcessingBusy();
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("too large")) {
                throw ApiException.avatarFileTooLarge();
            }
            if (exception.getMessage() != null && exception.getMessage().contains("Unsupported")) {
                throw ApiException.avatarFormatUnsupported();
            }
            throw ApiException.avatarImageInvalid();
        }
        User user = user(userId);
        String oldObjectId = user.getAvatarObjectId();
        String newObjectId;
        try {
            newObjectId = storage.put(StorageNamespace.AVATAR_UPLOADED,
                    processed.contentType(), "png", processed.png()).id();
        } catch (RuntimeException exception) {
            components.reportFailure(LocalStorageRuntimeComponent.CODE, exception.getMessage());
            throw ApiException.storageUnavailable();
        }
        user.useUploadedAvatar(newObjectId);
        detachAndDeleteAfterCommit(oldObjectId, newObjectId);
        audit.event("USER_AVATAR_UPLOADED", "User", userId);
        return CurrentUserResponse.from(user);
    }

    @Transactional
    public CurrentUserResponse reset(String userId) {
        User user = user(userId);
        String oldObjectId = user.getAvatarObjectId();
        user.resetAvatar();
        detachAndDeleteAfterCommit(oldObjectId, null);
        audit.event("USER_AVATAR_RESET", "User", userId);
        return CurrentUserResponse.from(user);
    }

    @Transactional
    public CurrentUserResponse adminReset(String userId) {
        User user = user(userId);
        String oldObjectId = user.getAvatarObjectId();
        user.resetAvatar();
        detachAndDeleteAfterCommit(oldObjectId, null);
        audit.event("ADMIN_USER_AVATAR_RESET", "User", userId);
        return CurrentUserResponse.from(user);
    }

    @Transactional
    public AvatarCandidateResponse createCandidate(String userId) {
        user(userId);
        if (!components.isUp(DiceBearAvatarGenerator.CODE)) throw ApiException.avatarGeneratorUnavailable();
        requireStorage();
        Instant now = Instant.now();
        evictExcessCandidates(userId, now);
        String seed = randomSeed();
        byte[] svg;
        try {
            svg = generator.generate(seed, 256);
        } catch (RuntimeException exception) {
            components.reportFailure(DiceBearAvatarGenerator.CODE, exception.getMessage());
            throw ApiException.avatarGeneratorUnavailable();
        }
        String objectId;
        try {
            objectId = storage.put(StorageNamespace.AVATAR_CANDIDATE,
                    "image/svg+xml", "svg", svg).id();
        } catch (RuntimeException exception) {
            components.reportFailure(LocalStorageRuntimeComponent.CODE, exception.getMessage());
            throw ApiException.storageUnavailable();
        }
        AvatarCandidate candidate = candidates.save(new AvatarCandidate(
                userId, seed, objectId, now.plus(CANDIDATE_TTL)));
        audit.event("USER_AVATAR_CANDIDATE_CREATED", "AvatarCandidate", candidate.getId());
        return AvatarCandidateResponse.from(candidate);
    }

    @Transactional
    public CurrentUserResponse applyCandidate(String userId, String publicId) {
        Instant now = Instant.now();
        AvatarCandidate candidate = candidates.findByPublicId(publicId)
                .orElseThrow(ApiException::avatarCandidateInvalid);
        if (!candidate.belongsTo(userId)) throw ApiException.avatarCandidateInvalid();
        if (candidate.isExpired(now)) throw ApiException.avatarCandidateExpired();
        if (candidate.isConsumed()) throw ApiException.avatarCandidateInvalid();
        User user = user(userId);
        String oldObjectId = user.getAvatarObjectId();
        candidate.consume(now);
        user.useRandomAvatar(candidate.getSeed(), candidate.getStorageObjectId());
        detachAndDeleteAfterCommit(oldObjectId, candidate.getStorageObjectId());
        audit.event("USER_AVATAR_RANDOMIZED", "User", userId);
        return CurrentUserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public AvatarContent readCandidate(String publicId) {
        Instant now = Instant.now();
        AvatarCandidate candidate = candidates.findByPublicId(publicId)
                .orElseThrow(ApiException::avatarCandidateInvalid);
        if (candidate.isExpired(now)) throw ApiException.avatarCandidateExpired();
        try {
            StoredObjectContent content = storage.open(candidate.getStorageObjectId());
            return new AvatarContent(content.contentType(), content.sizeBytes(), content.sha256(),
                    content.bytes(), false);
        } catch (RuntimeException exception) {
            components.reportFailure(LocalStorageRuntimeComponent.CODE, exception.getMessage());
            throw ApiException.storageUnavailable();
        }
    }

    private void requireStorage() {
        if (!components.isUp(LocalStorageRuntimeComponent.CODE)) throw ApiException.storageUnavailable();
    }

    private User user(String userId) {
        return users.findById(userId).orElseThrow(ApiException::notFound);
    }

    private String randomSeed() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void evictExcessCandidates(String userId, Instant now) {
        List<AvatarCandidate> active = candidates
                .findAllByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(userId, now);
        int remove = Math.max(0, active.size() - MAX_ACTIVE_CANDIDATES + 1);
        for (int index = 0; index < remove; index++) {
            AvatarCandidate candidate = active.get(index);
            candidates.delete(candidate);
            deleteAfterCommit(candidate.getStorageObjectId());
        }
    }

    private void detachAndDeleteAfterCommit(String oldObjectId, String replacementObjectId) {
        if (oldObjectId == null || oldObjectId.equals(replacementObjectId)) return;
        candidates.deleteAllByStorageObjectId(oldObjectId);
        deleteAfterCommit(oldObjectId);
    }

    private void deleteAfterCommit(String objectId) {
        Runnable delete = () -> {
            try {
                storage.delete(objectId);
            } catch (RuntimeException exception) {
                components.reportFailure(LocalStorageRuntimeComponent.CODE, exception.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            delete.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { delete.run(); }
        });
    }
}
