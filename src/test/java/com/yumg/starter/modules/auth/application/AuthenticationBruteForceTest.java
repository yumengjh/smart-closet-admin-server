package com.yumg.starter.modules.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.Role;
import com.yumg.starter.entities.User;
import com.yumg.starter.entities.UserStatus;
import com.yumg.starter.modules.auth.api.dto.LoginRequest;
import com.yumg.starter.modules.auth.api.dto.TokenResponse;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import com.yumg.starter.modules.security.application.AuditService;
import com.yumg.starter.modules.security.application.BruteForceService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** Account-level brute-force protection must also bind super administrators (#162). */
class AuthenticationBruteForceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final TokenService tokens = mock(TokenService.class);
    private final AuditService audit = mock(AuditService.class);
    private BruteForceService bruteForce;
    private AuthenticationService service;

    @BeforeEach
    void setUp() {
        RuntimeSettingService settings = mock(RuntimeSettingService.class);
        when(settings.enabled("security.brute-force.enabled")).thenReturn(true);
        when(settings.integer("security.brute-force.failure-threshold")).thenReturn(5);
        when(settings.integer("security.brute-force.window-seconds")).thenReturn(900);
        when(settings.integer("security.brute-force.lock-seconds")).thenReturn(900);
        bruteForce = new BruteForceService(settings);
        service = new AuthenticationService(users, encoder, tokens, bruteForce, audit);
        when(encoder.matches("wrong-password", "hash")).thenReturn(false);
        when(encoder.matches("right-password", "hash")).thenReturn(true);
        when(tokens.issue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new TokenResponse("access", "refresh", "Bearer", 900, false));
    }

    @Test
    void superAdminStopsAcceptingPasswordGuessesAfterTheThresholdWithoutBeingLocked() {
        User admin = user("root", true);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThatThrownBy(() -> service.login(new LoginRequest("root", "wrong-password", false)))
                    .isInstanceOf(ApiException.class);
        }
        verify(encoder, times(5)).matches("wrong-password", "hash");
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(tokens, never()).revokeAllForUser(anyString());

        // Even the correct password is refused during the backoff.
        assertThatThrownBy(() -> service.login(new LoginRequest("root", "right-password", false)))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.error()).isEqualTo(ApiErrorCode.ACCOUNT_LOCKED));
        verify(encoder, never()).matches("right-password", "hash");

        // Administrator recovery (status reset to ACTIVE) clears the backoff.
        bruteForce.clear("root");
        assertThat(service.login(new LoginRequest("root", "right-password", false)).accessToken())
                .isEqualTo("access");
    }

    @Test
    void regularAccountIsStillLockedAtTheThreshold() {
        User regular = user("alice", false);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong-password", false)))
                    .isInstanceOf(ApiException.class);
        }
        verify(encoder, times(5)).matches("wrong-password", "hash");
        assertThat(regular.getStatus()).isEqualTo(UserStatus.LOCKED);
        verify(tokens).revokeAllForUser("alice");
    }

    private User user(String username, boolean superAdmin) {
        User user = new User(username, username, "hash");
        ReflectionTestUtils.setField(user, "id", username);
        if (superAdmin) user.grant(new Role("SUPER_ADMIN", "Super Administrator"));
        when(users.findByUsername(username)).thenReturn(Optional.of(user));
        return user;
    }
}
