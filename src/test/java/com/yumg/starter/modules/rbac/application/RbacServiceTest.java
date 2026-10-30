package com.yumg.starter.modules.rbac.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.Permission;
import com.yumg.starter.entities.Role;
import com.yumg.starter.entities.User;
import com.yumg.starter.common.api.ApiErrorCode;
import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.auth.application.TokenService;
import com.yumg.starter.modules.auth.infrastructure.UserRepository;
import com.yumg.starter.modules.rbac.infrastructure.PermissionRepository;
import com.yumg.starter.modules.rbac.infrastructure.RoleRepository;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RbacServiceTest {
    @Mock private RoleRepository roles;
    @Mock private PermissionRepository permissions;
    @Mock private UserRepository users;
    @Mock private TokenService tokens;
    @Mock private AuditService audit;

    @Test
    void deletingPermissionOnlyInvalidatesMembersOfRolesThatActuallyHoldIt() {
        Permission permission = new Permission("verify:runtime:read", "verification");
        Role unaffected = new Role("UNRELATED", "Unrelated");
        Role affected = new Role("AFFECTED", "Affected");
        affected.grant(permission);
        when(permissions.findByCode(permission.getCode())).thenReturn(Optional.of(permission));
        when(roles.findAllWithPermissions()).thenReturn(List.of(unaffected, affected));
        when(users.findDistinctByRoles_Code("AFFECTED")).thenReturn(List.of());

        service().deletePermission(permission.getCode());

        verify(users, never()).findDistinctByRoles_Code("UNRELATED");
        verify(users).findDistinctByRoles_Code("AFFECTED");
        verify(permissions).delete(permission);
    }

    @Test
    void listingRolesUsesPermissionsFetchPlan() {
        Permission permission = new Permission("system:config:read", "Read settings");
        Role role = new Role("OPERATOR", "Operator");
        role.grant(permission);
        when(roles.findAllWithPermissions()).thenReturn(List.of(role));

        var result = service().listRoles();

        assertThat(result).singleElement().satisfies(item ->
                assertThat(item.permissions()).containsExactly("system:config:read"));
        verify(roles).findAllWithPermissions();
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3})
    void superAdminRoleCannotBeDeletedWhateverItsMemberCount(int members) {
        Role superAdmin = new Role("SUPER_ADMIN", "Super Administrator");
        List<User> admins = java.util.stream.IntStream.range(0, members)
                .mapToObj(index -> member("admin-" + index, superAdmin)).toList();
        when(roles.findByCode("SUPER_ADMIN")).thenReturn(Optional.of(superAdmin));

        assertThatThrownBy(() -> service().deleteRole("SUPER_ADMIN"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.error()).isEqualTo(ApiErrorCode.SYSTEM_ROLE_PROTECTED));

        verify(roles, never()).delete(org.mockito.ArgumentMatchers.any());
        verify(users, never()).findDistinctByRoles_Code("SUPER_ADMIN");
        verify(tokens, never()).revokeAllForUser(org.mockito.ArgumentMatchers.anyString());
        assertThat(admins).allMatch(User::isSuperAdmin)
                .allMatch(admin -> admin.getTokenVersion() == 0);
    }

    @Test
    void defaultUserRoleCannotBeDeleted() {
        when(roles.findByCode("USER")).thenReturn(Optional.of(new Role("USER", "User")));

        assertThatThrownBy(() -> service().deleteRole("USER")).isInstanceOf(ApiException.class);
        verify(roles, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void customRoleIsStillDeletedAndItsMembersInvalidated() {
        Role custom = new Role("OPERATOR", "Operator");
        User member = member("operator-1", custom);
        when(roles.findByCode("OPERATOR")).thenReturn(Optional.of(custom));
        when(users.findDistinctByRoles_Code("OPERATOR")).thenReturn(List.of(member));

        service().deleteRole("OPERATOR");

        verify(roles).delete(custom);
        verify(tokens).revokeAllForUser("operator-1");
        assertThat(member.getRoles()).doesNotContain(custom);
    }

    @Test
    void removingTheLastActiveSuperAdminIsRejectedEvenWhenADisabledOneRemains() {
        Role superAdmin = new Role("SUPER_ADMIN", "Super Administrator");
        User active = member("admin-active", superAdmin);
        User disabled = member("admin-disabled", superAdmin);
        disabled.disable();
        when(users.findById("admin-active")).thenReturn(Optional.of(active));
        when(users.lockAllByRoleCode("SUPER_ADMIN")).thenReturn(List.of(active, disabled));

        assertThatThrownBy(() -> service().removeRole("admin-active", "SUPER_ADMIN"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.error()).isEqualTo(ApiErrorCode.LAST_SUPER_ADMIN_PROTECTED));
        assertThat(active.isSuperAdmin()).isTrue();
    }

    @Test
    void removingASuperAdminIsAllowedWhileAnotherActiveOneRemains() {
        Role superAdmin = new Role("SUPER_ADMIN", "Super Administrator");
        User first = member("admin-1", superAdmin);
        User second = member("admin-2", superAdmin);
        when(users.findById("admin-1")).thenReturn(Optional.of(first));
        when(users.lockAllByRoleCode("SUPER_ADMIN")).thenReturn(List.of(first, second));
        when(roles.findByCode("SUPER_ADMIN")).thenReturn(Optional.of(superAdmin));

        service().removeRole("admin-1", "SUPER_ADMIN");

        assertThat(first.isSuperAdmin()).isFalse();
        verify(tokens).revokeAllForUser("admin-1");
    }

    private RbacService service() {
        return new RbacService(roles, permissions, users, tokens, audit, new SuperAdminGuard(users));
    }

    private static User member(String id, Role role) {
        User user = new User(id, id, "hash");
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", id);
        user.grant(role);
        return user;
    }
}
