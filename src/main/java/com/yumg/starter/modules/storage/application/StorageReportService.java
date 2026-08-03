package com.yumg.starter.modules.storage.application;

import com.yumg.starter.entities.StorageObject;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.storage.api.dto.StorageReportResponse;
import com.yumg.starter.modules.storage.infrastructure.LocalStorageBackend;
import com.yumg.starter.modules.storage.infrastructure.StorageObjectRepository;
import com.yumg.starter.modules.users.infrastructure.AvatarCandidateRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageReportService {
    private final StorageObjectRepository objects;
    private final UserRepository users;
    private final AvatarCandidateRepository candidates;
    private final LocalStorageBackend backend;

    public StorageReportService(StorageObjectRepository objects, UserRepository users,
            AvatarCandidateRepository candidates, LocalStorageBackend backend) {
        this.objects = objects;
        this.users = users;
        this.candidates = candidates;
        this.backend = backend;
    }

    @Transactional(readOnly = true)
    public StorageReportResponse report() {
        Instant now = Instant.now();
        List<StorageObject> all = objects.findAll();
        Set<String> userReferences = new HashSet<>(users.findAllAvatarObjectIds());
        Set<String> candidateReferences = new HashSet<>(candidates.findAllStorageObjectIds());
        StorageReportSummary summary = summarize(all, userReferences, candidateReferences,
                objectKey -> backend.exists(objectKey));
        Set<String> knownKeys = all.stream().map(StorageObject::getObjectKey)
                .collect(java.util.stream.Collectors.toSet());
        long untracked = backend.untrackedObjectKeys(knownKeys, now.plusSeconds(1), Integer.MAX_VALUE).size();
        var capacity = backend.capacity();
        return new StorageReportResponse(now, summary.totalObjects(), summary.totalBytes(),
                summary.userReferencedObjects(), summary.candidateReferencedObjects(),
                summary.orphanObjects(), summary.missingFileObjects(), untracked,
                backend.temporaryFileCount(), capacity.usableBytes(), capacity.totalBytes());
    }

    static StorageReportSummary summarize(List<StorageObject> objects, Set<String> userReferences,
                                          Set<String> candidateReferences,
                                          Predicate<String> fileExists) {
        long bytes = 0;
        long userReferenced = 0;
        long candidateReferenced = 0;
        long orphan = 0;
        long missing = 0;
        for (StorageObject object : objects) {
            bytes += object.getSizeBytes();
            boolean user = userReferences.contains(object.getId());
            boolean candidate = candidateReferences.contains(object.getId());
            if (user) userReferenced++;
            if (candidate) candidateReferenced++;
            if (!user && !candidate) orphan++;
            if (!fileExists.test(object.getObjectKey())) missing++;
        }
        return new StorageReportSummary(objects.size(), bytes, userReferenced,
                candidateReferenced, orphan, missing);
    }
}
