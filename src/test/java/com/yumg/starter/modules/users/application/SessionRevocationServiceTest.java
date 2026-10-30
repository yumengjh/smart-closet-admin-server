package com.yumg.starter.modules.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.User;
import com.yumg.starter.modules.auth.application.TokenService;
import com.yumg.starter.modules.auth.infrastructure.RefreshSessionRepository;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class SessionRevocationServiceTest {
    @Mock private UserRepository users;
    @Mock private TokenService tokens;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RefreshSessionRepository sessions;
    @Mock private AuditService audit;

    @Test
    void selfRevocationAlsoInvalidatesExistingAccessTokens() {
        User user = user();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        new UserProfileService(users, passwordEncoder, tokens, sessions).revokeAllSessions(user.getId());

        assertThat(user.getTokenVersion()).isEqualTo(1);
        verify(tokens).revokeAllForUser(user.getId());
    }

    @Test
    void administratorRevocationAlsoInvalidatesExistingAccessTokens() {
        User user = user();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        new UserAdministrationService(users, tokens, audit, new com.yumg.starter.modules.rbac.application.SuperAdminGuard(users)).revokeSessions(user.getId());

        assertThat(user.getTokenVersion()).isEqualTo(1);
        verify(tokens).revokeAllForUser(user.getId());
    }

    private User user() {
        User user = new User("tester", "Tester", "hash");
        user.changeDisplayName("Tester");
        return user;
    }
}
