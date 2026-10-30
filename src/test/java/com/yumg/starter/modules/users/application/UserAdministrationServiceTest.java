package com.yumg.starter.modules.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.Role;
import com.yumg.starter.entities.User;
import com.yumg.starter.entities.UserStatus;
import com.yumg.starter.modules.auth.application.TokenService;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserAdministrationServiceTest {
    @Mock private UserRepository users;
    @Mock private TokenService tokens;
    @Mock private AuditService audit;

    @Test
    void combinesTrimmedQueryAndStatusAndBulkLoadsRoles() {
        var pageable = PageRequest.of(1, 20);
        User pageUser = user("user-1", "alice");
        User loadedUser = user("user-1", "alice");
        loadedUser.grant(new Role("OPERATOR", "Operator"));
        var page = new PageImpl<>(List.of(pageUser), pageable, 25);
        when(users.search("alice", UserStatus.ACTIVE, pageable)).thenReturn(page);
        when(users.findAllWithRolesByIdIn(List.of("user-1"))).thenReturn(List.of(loadedUser));

        var response = service().list("  alice  ", UserStatus.ACTIVE, 1, 20);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("user-1");
            assertThat(item.roles()).containsExactly("OPERATOR");
        });
        assertThat(response.totalElements()).isEqualTo(page.getTotalElements());
        verify(users).search("alice", UserStatus.ACTIVE, pageable);
        verify(users).findAllWithRolesByIdIn(List.of("user-1"));
    }

    @Test
    void emptyPageDoesNotIssueRoleQuery() {
        var pageable = PageRequest.of(0, 20);
        when(users.search(null, null, pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        var response = service().list(" ", null, 0, 20);

        assertThat(response.items()).isEmpty();
        verify(users, never()).findAllWithRolesByIdIn(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disablingTheLastActiveSuperAdminIsRejectedWhenOthersAreDisabled() {
        Role superAdmin = new Role("SUPER_ADMIN", "Super Administrator");
        User target = user("admin-1", "admin-1");
        target.grant(superAdmin);
        User disabled = user("admin-2", "admin-2");
        disabled.grant(superAdmin);
        disabled.disable();
        when(users.findWithRolesById("admin-1")).thenReturn(java.util.Optional.of(target));
        when(users.lockAllByRoleCode("SUPER_ADMIN")).thenReturn(List.of(target, disabled));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().changeStatus("admin-1", UserStatus.DISABLED))
                .isInstanceOf(com.yumg.starter.common.api.ApiException.class);
        assertThat(target.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(tokens, never()).revokeAllForUser("admin-1");
    }

    private UserAdministrationService service() {
        return new UserAdministrationService(users, tokens, audit, new com.yumg.starter.modules.rbac.application.SuperAdminGuard(users));
    }

    private User user(String id, String username) {
        User user = new User(username, username, "hash");
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
