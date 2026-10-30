package com.yumg.starter.modules.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.RefreshSession;
import com.yumg.starter.modules.auth.application.TokenService;
import com.yumg.starter.modules.auth.infrastructure.RefreshSessionRepository;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceTest {

    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TokenService tokens;
    @Mock private RefreshSessionRepository sessions;

    @Test
    void collapsesRefreshTokenRotationsIntoOneLoginSession() {
        Instant firstLogin = Instant.parse("2030-07-15T08:00:00Z");
        RefreshSession rotated = session("browser", firstLogin);
        rotated.consume(firstLogin.plusSeconds(60));
        RefreshSession current = session("browser", firstLogin.plusSeconds(60));
        RefreshSession otherDevice = session("phone", firstLogin.plusSeconds(120));
        RefreshSession signedOutDevice = session("old-browser", firstLogin.plusSeconds(180));
        signedOutDevice.revoke(firstLogin.plusSeconds(240));
        when(sessions.findAllByUserIdOrderByIssuedAtDesc("user-id"))
                .thenReturn(List.of(signedOutDevice, otherDevice, current, rotated));

        var result = service().sessions("user-id");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(response -> response.status())
                .containsExactly("ACTIVE", "ACTIVE");
        assertThat(result).extracting(response -> response.firstIssuedAt())
                .containsExactly(firstLogin.plusSeconds(120).toEpochMilli(), firstLogin.toEpochMilli());
    }

    private UserProfileService service() {
        return new UserProfileService(users, passwordEncoder, tokens, sessions);
    }

    private RefreshSession session(String familyId, Instant issuedAt) {
        return new RefreshSession("user-id", familyId, familyId + issuedAt, issuedAt,
                issuedAt.plusSeconds(60 * 60), true, 0);
    }
}
