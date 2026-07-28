package com.yumg.starter.modules.rbac.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.Permission;
import com.yumg.starter.entities.Role;
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

        new RbacService(roles, permissions, users, tokens, audit).deletePermission(permission.getCode());

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

        var result = new RbacService(roles, permissions, users, tokens, audit).listRoles();

        assertThat(result).singleElement().satisfies(item ->
                assertThat(item.permissions()).containsExactly("system:config:read"));
        verify(roles).findAllWithPermissions();
    }

}
