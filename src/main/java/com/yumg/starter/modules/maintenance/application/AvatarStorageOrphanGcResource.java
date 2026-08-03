package com.yumg.starter.modules.maintenance.application;

import com.yumg.starter.entities.MaintenanceGcPolicy;
import com.yumg.starter.entities.StorageObject;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.storage.application.StorageNamespace;
import com.yumg.starter.modules.storage.application.StorageService;
import com.yumg.starter.modules.storage.infrastructure.LocalStorageBackend;
import com.yumg.starter.modules.storage.infrastructure.StorageObjectRepository;
import com.yumg.starter.modules.users.infrastructure.AvatarCandidateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AvatarStorageOrphanGcResource implements GcResource {
    private static final Set<StorageNamespace> AVATAR_NAMESPACES = Set.of(
            StorageNamespace.AVATAR_GENERATED,
            StorageNamespace.AVATAR_UPLOADED,
            StorageNamespace.AVATAR_CANDIDATE);

    private final StorageObjectRepository objects;
    private final UserRepository users;
    private final AvatarCandidateRepository candidates;
    private final StorageService storage;
    private final LocalStorageBackend backend;

    public AvatarStorageOrphanGcResource(StorageObjectRepository objects, UserRepository users,
            AvatarCandidateRepository candidates, StorageService storage, LocalStorageBackend backend) {
        this.objects = objects;
        this.users = users;
        this.candidates = candidates;
        this.storage = storage;
        this.backend = backend;
    }

    @Override
    public GcResourceDescriptor descriptor() {
        return new GcResourceDescriptor("avatar-storage-orphans", "头像存储孤儿",
                "清理安全窗口外无用户/候选引用的头像对象、未登记文件和临时文件。", 1, 200, true);
    }

    @Override
    public GcResourceResult execute(MaintenanceGcPolicy policy, boolean dryRun, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(policy.getRetentionDays()));
        Set<String> userReferences = new HashSet<>(users.findAllAvatarObjectIds());
        Set<String> candidateReferences = new HashSet<>(candidates.findAllStorageObjectIds());
        List<StorageObject> all = objects.findAll();
        List<StorageObject> metadataOrphans = all.stream()
                .filter(object -> AVATAR_NAMESPACES.contains(object.getNamespace()))
                .filter(object -> object.getCreatedAt() != null && object.getCreatedAt().isBefore(cutoff))
                .filter(object -> !userReferences.contains(object.getId())
                        && !candidateReferences.contains(object.getId()))
                .toList();
        Set<String> knownKeys = all.stream().map(StorageObject::getObjectKey)
                .collect(java.util.stream.Collectors.toSet());
        int limit = policy.getBatchSize();
        List<String> untracked = backend.untrackedObjectKeys(knownKeys, cutoff, limit);
        List<String> temporary = backend.staleTemporaryKeys(cutoff, limit);
        long total = metadataOrphans.size() + untracked.size() + temporary.size();
        if (dryRun) return new GcResourceResult(descriptor().code(), total, 0, "预览");

        long deleted = 0;
        for (StorageObject object : metadataOrphans.stream().limit(limit).toList()) {
            storage.delete(object.getId());
            deleted++;
        }
        int remaining = Math.max(0, limit - (int) deleted);
        for (String key : untracked.stream().limit(remaining).toList()) {
            if (backend.delete(key)) deleted++;
        }
        remaining = Math.max(0, limit - (int) deleted);
        for (String key : temporary.stream().limit(remaining).toList()) {
            if (backend.delete(key)) deleted++;
        }
        return new GcResourceResult(descriptor().code(), total, deleted, "已清理");
    }
}
