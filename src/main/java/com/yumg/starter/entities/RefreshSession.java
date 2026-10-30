package com.yumg.starter.entities;

import com.yumg.starter.common.entity.BaseUuidEntity;
import com.yumg.starter.common.entity.InstantStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "refresh_sessions")
public class RefreshSession extends BaseUuidEntity {
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(name = "issued_at", nullable = false)
    @Convert(converter = InstantStringConverter.class)
    private Instant issuedAt;
    @Column(name = "expires_at", nullable = false)
    @Convert(converter = InstantStringConverter.class)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    @Convert(converter = InstantStringConverter.class)
    private Instant consumedAt;
    @Column(name = "revoked_at")
    @Convert(converter = InstantStringConverter.class)
    private Instant revokedAt;
    @Column(name = "persistent", nullable = false)
    private boolean persistent;
    /** User token_version at the credential check that started this family; refresh is refused once it moves. */
    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    protected RefreshSession() {}

    public RefreshSession(String userId, String familyId, String tokenHash, Instant issuedAt,
                          Instant expiresAt, boolean persistent, long tokenVersion) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.persistent = persistent;
        this.tokenVersion = tokenVersion;
    }

    public String getUserId() { return userId; }
    public String getFamilyId() { return familyId; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public boolean isPersistent() { return persistent; }
    public long getTokenVersion() { return tokenVersion; }
    public boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
    public boolean isConsumed() { return consumedAt != null; }
    public boolean isRevoked() { return revokedAt != null; }
    public void consume(Instant now) { consumedAt = now; }
    public void revoke(Instant now) { revokedAt = now; }
}
