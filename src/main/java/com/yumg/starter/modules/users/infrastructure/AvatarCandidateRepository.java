package com.yumg.starter.modules.users.infrastructure;

import com.yumg.starter.entities.AvatarCandidate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AvatarCandidateRepository extends JpaRepository<AvatarCandidate, String> {
    Optional<AvatarCandidate> findByPublicId(String publicId);
    List<AvatarCandidate> findAllByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
            String userId, Instant now);
    void deleteAllByStorageObjectId(String storageObjectId);
    boolean existsByStorageObjectId(String storageObjectId);

    @org.springframework.data.jpa.repository.Query(
            "select candidate.storageObjectId from AvatarCandidate candidate")
    List<String> findAllStorageObjectIds();
}
