package com.yumg.starter.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.RefreshSession;
import com.yumg.starter.entities.User;
import com.yumg.starter.modules.auth.api.dto.LoginRequest;
import com.yumg.starter.modules.auth.api.dto.TokenResponse;
import com.yumg.starter.modules.auth.application.AuthenticationUseCase;
import com.yumg.starter.modules.auth.infrastructure.RefreshSessionRepository;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.security.infrastructure.AuditEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Real Spring transactions across AuthenticationService and TokenService proxies: replaying a
 * consumed refresh token must commit the family revocation and its audit row (#157).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/refresh-token-reuse-test.db",
        "app.integrations.closet.enabled=false"
})
class RefreshTokenReuseTransactionTest {
    static {
        new java.io.File("target/refresh-token-reuse-test.db").delete();
    }

    @Autowired private AuthenticationUseCase auth;
    @Autowired private UserRepository users;
    @Autowired private RefreshSessionRepository sessions;
    @Autowired private AuditEventRepository auditEvents;
    @Autowired private PasswordEncoder encoder;

    @Test
    void replayedRefreshTokenRevokesItsFamilyDurablyAndLeavesOtherFamiliesAlone() {
        String username = "reuse-" + UUID.randomUUID().toString().substring(0, 8);
        User user = users.saveAndFlush(new User(username, "Reuse", encoder.encode("correct-horse-1")));
        TokenResponse t0 = auth.login(new LoginRequest(username, "correct-horse-1", false));
        String replayedFamily = sessions.findAllByUserIdOrderByIssuedAtDesc(user.getId())
                .getFirst().getFamilyId();
        TokenResponse otherFamily = auth.login(new LoginRequest(username, "correct-horse-1", false));
        TokenResponse t1 = auth.refresh(t0.refreshToken());
        long reuseAuditsBefore = reuseAudits();

        assertUnauthorized(() -> auth.refresh(t0.refreshToken()));
        assertUnauthorized(() -> auth.refresh(t1.refreshToken()));

        List<RefreshSession> stored = sessions.findAllByUserIdOrderByIssuedAtDesc(user.getId());
        assertThat(stored).filteredOn(session -> session.getFamilyId().equals(replayedFamily))
                .hasSize(2).allMatch(RefreshSession::isRevoked);
        assertThat(stored).filteredOn(session -> !session.getFamilyId().equals(replayedFamily))
                .hasSize(1).noneMatch(RefreshSession::isRevoked);
        assertThat(reuseAudits()).isEqualTo(reuseAuditsBefore + 1);

        TokenResponse stillValid = auth.refresh(otherFamily.refreshToken());
        assertThat(stillValid.refreshToken()).isNotBlank();
    }

    private long reuseAudits() {
        return auditEvents.findAll().stream()
                .filter(event -> "REFRESH_TOKEN_REUSE_DETECTED".equals(event.getAction())).count();
    }

    private static void assertUnauthorized(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.error()).isEqualTo(ApiErrorCode.AUTHENTICATION_REQUIRED));
    }
}
