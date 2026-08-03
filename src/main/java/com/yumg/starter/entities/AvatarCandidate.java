package com.yumg.starter.entities;

import com.yumg.starter.common.entity.AuditedEntity;
import com.yumg.starter.common.entity.InstantStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "avatar_candidates")
public class AvatarCandidate extends AuditedEntity {
    @Column(name = "public_id", nullable = false, unique = true, length = 36)
    private String publicId;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(nullable = false, length = 64)
    private String seed;
    @Column(name = "storage_object_id", nullable = false, length = 36)
    private String storageObjectId;
    @Column(name = "expires_at", nullable = false)
    @Convert(converter = InstantStringConverter.class)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    @Convert(converter = InstantStringConverter.class)
    private Instant consumedAt;

    protected AvatarCandidate() {}

    public AvatarCandidate(String userId, String seed, String storageObjectId, Instant expiresAt) {
        ensureId();
        this.publicId = UUID.randomUUID().toString();
        this.userId = userId;
        this.seed = seed;
        this.storageObjectId = storageObjectId;
        this.expiresAt = expiresAt;
    }

    public String getPublicId() { return publicId; }
    public String getUserId() { return userId; }
    public String getSeed() { return seed; }
    public String getStorageObjectId() { return storageObjectId; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public boolean belongsTo(String expectedUserId) { return userId.equals(expectedUserId); }
    public boolean isConsumed() { return consumedAt != null; }
    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
    public boolean isAvailableTo(String expectedUserId, Instant now) {
        return belongsTo(expectedUserId) && !isConsumed() && !isExpired(now);
    }
    public void consume(Instant now) {
        if (isConsumed()) throw new IllegalStateException("Avatar candidate is already consumed");
        consumedAt = now;
    }
}
