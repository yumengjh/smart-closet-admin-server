package com.yumg.starter.entities;

import com.yumg.starter.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Convert;
import com.yumg.starter.common.entity.InstantStringConverter;
import java.time.Instant;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import java.util.LinkedHashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;
import jakarta.persistence.PrePersist;

@Entity
@Table(name = "users")
public class User extends AuditedEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private UserStatus status;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "avatar_public_id", length = 36, unique = true)
    private String avatarPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "avatar_type", nullable = false, length = 32)
    private AvatarType avatarType;

    @Column(name = "avatar_seed", length = 64)
    private String avatarSeed;

    @Column(name = "avatar_object_id", length = 36)
    private String avatarObjectId;

    @Column(name = "avatar_revision", nullable = false)
    private long avatarRevision;
    @Column(name = "locked_until")
    @Convert(converter = InstantStringConverter.class)
    private Instant lockedUntil;
    @ManyToMany
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new LinkedHashSet<>();

    protected User() {
    }

    public User(String username, String displayName, String passwordHash) {
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
        initializeAvatarIdentity(ensureId());
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserStatus getStatus() {
        return status;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public String getAvatarPublicId() { return avatarPublicId; }
    public AvatarType getAvatarType() { return avatarType; }
    public String getAvatarSeed() { return avatarSeed; }
    public String getAvatarObjectId() { return avatarObjectId; }
    public long getAvatarRevision() { return avatarRevision; }

    @PrePersist
    void ensureAvatarIdentity() {
        initializeAvatarIdentity(ensureId());
    }

    public void initializeAvatarIdentity(String internalId) {
        if (avatarPublicId == null) avatarPublicId = UUID.randomUUID().toString();
        if (avatarType == null) avatarType = AvatarType.SYSTEM_INITIAL;
        if (avatarSeed == null || avatarType == AvatarType.SYSTEM_INITIAL) {
            avatarSeed = initialAvatarSeed(internalId);
        }
    }

    public void cacheGeneratedAvatar(String objectId) {
        if (avatarType != AvatarType.UPLOADED) avatarObjectId = objectId;
    }

    public void useUploadedAvatar(String objectId) {
        avatarType = AvatarType.UPLOADED;
        avatarObjectId = objectId;
        avatarRevision++;
    }

    public void useRandomAvatar(String seed, String objectId) {
        avatarType = AvatarType.SYSTEM_RANDOM;
        avatarSeed = seed;
        avatarObjectId = objectId;
        avatarRevision++;
    }

    public void resetAvatar() {
        avatarType = AvatarType.SYSTEM_INITIAL;
        avatarSeed = initialAvatarSeed(ensureId());
        avatarObjectId = null;
        avatarRevision++;
    }

    private static String initialAvatarSeed(String internalId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("avatar:lorelei:v1:" + internalId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public void changeDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.tokenVersion++;
    }
    public void invalidateSessions() { tokenVersion++; }
    public void grant(Role role) { roles.add(role); }
    public void revoke(Role role) { roles.remove(role); }
    public Set<Role> getRoles() { return Set.copyOf(roles); }
    public boolean isSuperAdmin() { return roles.stream().anyMatch(role -> "SUPER_ADMIN".equals(role.getCode())); }
    public void lock(Instant until) { status = UserStatus.LOCKED; lockedUntil = until; tokenVersion++; }
    public void unlock() { if (status == UserStatus.LOCKED) { status = UserStatus.ACTIVE; lockedUntil = null; tokenVersion++; } }
    public void disable() { status = UserStatus.DISABLED; tokenVersion++; }
    public void enable() { if (status == UserStatus.DISABLED) { status = UserStatus.ACTIVE; tokenVersion++; } }
    public boolean unlockIfExpired(Instant now) {
        if (status == UserStatus.LOCKED && lockedUntil != null && !lockedUntil.isAfter(now)) { unlock(); return true; }
        return false;
    }
}
