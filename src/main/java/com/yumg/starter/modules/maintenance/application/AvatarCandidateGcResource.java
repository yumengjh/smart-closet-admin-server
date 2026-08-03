package com.yumg.starter.modules.maintenance.application;

import com.yumg.starter.entities.AvatarCandidate;
import com.yumg.starter.entities.MaintenanceGcPolicy;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.storage.application.StorageService;
import com.yumg.starter.modules.users.infrastructure.AvatarCandidateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AvatarCandidateGcResource implements GcResource {
    private final AvatarCandidateRepository candidates;
    private final UserRepository users;
    private final StorageService storage;

    public AvatarCandidateGcResource(AvatarCandidateRepository candidates,
            UserRepository users, StorageService storage) {
        this.candidates = candidates;
        this.users = users;
        this.storage = storage;
    }

    @Override
    public GcResourceDescriptor descriptor() {
        return new GcResourceDescriptor("avatar-candidates", "头像候选",
                "清理过期或已消费超过保留期的随机头像候选及其未引用对象。", 1, 200, true);
    }

    @Override
    public GcResourceResult execute(MaintenanceGcPolicy policy, boolean dryRun, Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(policy.getRetentionDays()));
        List<AvatarCandidate> expired = candidates.findAll().stream()
                .filter(candidate -> !candidate.getExpiresAt().isAfter(cutoff)
                        || candidate.getConsumedAt() != null && !candidate.getConsumedAt().isAfter(cutoff))
                .toList();
        if (dryRun) {
            return new GcResourceResult(descriptor().code(), expired.size(), 0, "预览");
        }
        long deleted = 0;
        for (AvatarCandidate candidate : expired.stream().limit(policy.getBatchSize()).toList()) {
            String objectId = candidate.getStorageObjectId();
            candidates.delete(candidate);
            candidates.flush();
            if (!users.existsByAvatarObjectId(objectId)
                    && !candidates.existsByStorageObjectId(objectId)) {
                storage.delete(objectId);
            }
            deleted++;
        }
        return new GcResourceResult(descriptor().code(), expired.size(), deleted, "已清理");
    }
}
