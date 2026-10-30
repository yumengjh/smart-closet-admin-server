package com.yumg.starter.modules.auth.application;

import com.yumg.starter.entities.RefreshSession;
import com.yumg.starter.entities.User;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.auth.api.dto.TokenResponse;
import com.yumg.starter.modules.auth.infrastructure.RefreshSessionRepository;
import com.yumg.starter.modules.security.application.AuditService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JwtEncoder encoder;
    private final RefreshSessionRepository refreshSessions;
    private final AuditService audit;

    public TokenService(JwtEncoder encoder, RefreshSessionRepository refreshSessions, AuditService audit) {
        this.encoder = encoder;
        this.refreshSessions = refreshSessions;
        this.audit = audit;
    }

    @Transactional
    public TokenResponse issue(User user, boolean persistent) {
        return issue(user, UUID.randomUUID().toString(), persistent);
    }

    /**
     * Rejections here persist security state (family revocation, reuse audit) before throwing.
     * The rollback rule must match {@link AuthenticationService#refresh}: a default inner
     * {@code @Transactional} would mark the shared transaction rollback-only on the
     * {@link ApiException}, and the outer commit would then discard the revocation (#157).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse rotate(User user, String rawRefreshToken) {
        Instant now = Instant.now();
        RefreshSession session = refreshSessions.findByTokenHash(sha256(rawRefreshToken))
                .orElseThrow(ApiException::unauthorized);
        if (!session.getUserId().equals(user.getId()) || session.isExpired(now) || session.isRevoked()) {
            throw ApiException.unauthorized();
        }
        if (session.isConsumed()) {
            refreshSessions.revokeFamily(session.getFamilyId(), now);
            audit.event("REFRESH_TOKEN_REUSE_DETECTED", "RefreshSession", session.getId(), "FAILURE", "{\"action\":\"family_revoked\"}");
            throw ApiException.unauthorized();
        }
        if (session.getTokenVersion() != user.getTokenVersion()) {
            // Issued from a credential check that predates a password/role/status change: a late login
            // racing a password change must not be promoted to the user's current version (#171).
            refreshSessions.revokeFamily(session.getFamilyId(), now);
            audit.event("REFRESH_TOKEN_STALE_VERSION", "RefreshSession", session.getId(), "FAILURE", "{\"action\":\"family_revoked\"}");
            throw ApiException.unauthorized();
        }
        session.consume(now);
        return issue(user, session.getFamilyId(), session.isPersistent());
    }

    @Transactional
    public void revoke(String rawRefreshToken) {
        refreshSessions.findByTokenHash(sha256(rawRefreshToken)).ifPresent(session ->
                session.revoke(Instant.now()));
    }

    @Transactional
    public void revokeAllForUser(String userId) {
        refreshSessions.revokeAllForUser(userId, Instant.now());
    }

    public Optional<RefreshSession> findSession(String tokenHash) {
        return refreshSessions.findByTokenHash(tokenHash);
    }

    private TokenResponse issue(User user, String familyId, boolean persistent) {
        Instant now = Instant.now();
        Instant accessExpiry = now.plus(15, ChronoUnit.MINUTES);
        String rawRefreshToken = newRefreshToken();
        refreshSessions.save(new RefreshSession(user.getId(), familyId,
                sha256(rawRefreshToken), now, now.plus(30, ChronoUnit.DAYS), persistent,
                user.getTokenVersion()));
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("springboot-start").subject(user.getId())
                .issuedAt(now).expiresAt(accessExpiry).id(UUID.randomUUID().toString())
                .claim("username", user.getUsername()).claim("token_version", user.getTokenVersion())
                .claim("permissions", user.getRoles().stream().flatMap(role -> role.getPermissions().stream())
                        .map(permission -> permission.getCode()).collect(java.util.stream.Collectors.toSet()))
                .build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new TokenResponse(accessToken, rawRefreshToken, "Bearer", 900, persistent);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
