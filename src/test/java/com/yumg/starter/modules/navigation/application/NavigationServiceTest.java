package com.yumg.starter.modules.navigation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.NavigationMenu;
import com.yumg.starter.entities.NavigationMenuType;
import com.yumg.starter.modules.navigation.api.dto.NavigationMenuRequest;
import com.yumg.starter.modules.navigation.infrastructure.NavigationMenuRepository;
import com.yumg.starter.modules.rbac.infrastructure.PermissionRepository;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NavigationServiceTest {

    @Mock private NavigationMenuRepository menus;
    @Mock private PermissionRepository permissions;
    @Mock private AuditService audit;

    @Test
    void routesOnlyContainEnabledVisibleMenusAllowedByPermissions() {
        NavigationMenu allowed = page("allowed", "/allowed", "welcome", "system:menu:read", false);
        NavigationMenu denied = page("denied", "/denied", "welcome", "system:menu:write", false);
        NavigationMenu disabled = new NavigationMenu(null, "disabled", "Disabled", "/disabled",
                "welcome", null, 3, NavigationMenuType.PAGE, null, true, false, false, false);
        when(menus.findAllByOrderBySortOrderAscCodeAsc()).thenReturn(List.of(allowed, denied, disabled));

        var routes = service().routes(Set.of("system:menu:read"));

        assertThat(routes).extracting(route -> route.code()).containsExactly("allowed");
    }

    @Test
    void routesExcludeChildrenWhenTheirParentMenuIsPaused() {
        NavigationMenu parent = new NavigationMenu(null, "system", "System", "/system", null,
                null, 0, NavigationMenuType.DIRECTORY, null, true, false, false, true);
        ReflectionTestUtils.setField(parent, "id", "system-id");
        NavigationMenu child = page("user-management", "/system/users", "user-management",
                "system:user:read", true);
        child.moveTo(parent.getId());
        when(menus.findAllByOrderBySortOrderAscCodeAsc()).thenReturn(List.of(parent, child));

        var routes = service().routes(Set.of("system:user:read"));

        assertThat(routes).isEmpty();
    }

    @Test
    void updatesTheStatusOfAnIntegratedMenuWithoutAllowingStructuralEdits() {
        NavigationMenu builtin = page("home", "/welcome", "welcome", null, true);
        when(menus.findById("builtin")).thenReturn(Optional.of(builtin));

        var response = service().setEnabled("builtin", false);

        assertThat(response.enabled()).isFalse();
        verify(audit).event("NAVIGATION_MENU_STATUS_UPDATED", "NavigationMenu", "home");
    }

    @Test
    void rejectsUnsupportedComponentKey() {
        NavigationMenuRequest request = new NavigationMenuRequest(null, "unsupported", "Unsupported",
                "/unsupported", "outside-world", null, 0, NavigationMenuType.PAGE, null, true, true, false);
        when(menus.findByCode("unsupported")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(request)).isInstanceOf(ApiException.class);
    }

    @Test
    void acceptsSupportedAdminPageComponentKey() {
        NavigationMenuRequest request = new NavigationMenuRequest(null, "user-management", "用户管理",
                "/system/users", "user-management", "User", 20, NavigationMenuType.PAGE,
                "system:user:read", true, true, false);
        when(menus.findByCode(request.code())).thenReturn(Optional.empty());
        when(permissions.findByCode("system:user:read")).thenReturn(Optional.of(
                new com.yumg.starter.entities.Permission("system:user:read", "Read users")));
        when(menus.save(any(NavigationMenu.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().create(request);

        assertThat(response.code()).isEqualTo("user-management");
        verify(menus).save(any(NavigationMenu.class));
    }

    @Test
    void rejectsMissingParent() {
        NavigationMenuRequest request = new NavigationMenuRequest("missing-parent", "child", "Child",
                "/child", "welcome", null, 0, NavigationMenuType.PAGE, null, true, true, false);
        when(menus.findByCode("child")).thenReturn(Optional.empty());
        when(menus.findById("missing-parent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(request)).isInstanceOf(ApiException.class);
    }

    @Test
    void preventsDeletingSystemManagedMenu() {
        NavigationMenu builtin = page("home", "/welcome", "welcome", null, true);
        when(menus.findById("builtin")).thenReturn(Optional.of(builtin));

        assertThatThrownBy(() -> service().delete("builtin")).isInstanceOf(ApiException.class);
    }

    @Test
    void createsValidatedMenuAndWritesAuditEvent() {
        NavigationMenuRequest request = new NavigationMenuRequest(null, "menu-manager", "Menu manager",
                "/system/menu", "menu-management", "Menu", 10, NavigationMenuType.PAGE,
                "system:menu:read", true, true, true);
        when(menus.findByCode(request.code())).thenReturn(Optional.empty());
        when(permissions.findByCode("system:menu:read")).thenReturn(Optional.of(
                new com.yumg.starter.entities.Permission("system:menu:read", "Read menus")));
        when(menus.save(any(NavigationMenu.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().create(request);

        assertThat(response.code()).isEqualTo("menu-manager");
        verify(menus).save(any(NavigationMenu.class));
        verify(audit).event("NAVIGATION_MENU_CREATED", "NavigationMenu", "menu-manager");
    }

    @Test
    void routesSkipMenusOnAParentCycleInsteadOfOverflowing() {
        NavigationMenu a = directory("a", "/a", "a-id");
        NavigationMenu b = directory("b", "/b", "b-id");
        a.moveTo("b-id");
        b.moveTo("a-id");
        NavigationMenu healthy = page("allowed", "/allowed", "welcome", null, false);
        when(menus.findAllByOrderBySortOrderAscCodeAsc()).thenReturn(List.of(a, b, healthy));

        var routes = service().routes(Set.of());

        assertThat(routes).extracting(route -> route.code()).containsExactly("allowed");
    }

    @Test
    void movingUnderAnAlreadyCyclicChainIsRejectedAndTerminates() {
        NavigationMenu a = directory("a", "/a", "a-id");
        NavigationMenu b = directory("b", "/b", "b-id");
        a.moveTo("b-id");
        b.moveTo("a-id");
        NavigationMenu target = directory("c", "/c", "c-id");
        when(menus.findById("c-id")).thenReturn(Optional.of(target));
        when(menus.findById("a-id")).thenReturn(Optional.of(a));
        when(menus.findById("b-id")).thenReturn(Optional.of(b));
        NavigationMenuRequest request = new NavigationMenuRequest("a-id", "c", "c", "/c", null, null, 0,
                NavigationMenuType.DIRECTORY, null, true, true, false);

        assertThatThrownBy(() -> service().update("c-id", request)).isInstanceOf(ApiException.class);
        assertThat(target.getParentId()).isNull();
    }

    @Test
    void structuralChangesTakeTheTreeLockBeforeReadingTheParentChain() {
        NavigationMenu a = directory("a", "/a", "a-id");
        NavigationMenu b = directory("b", "/b", "b-id");
        b.moveTo("a-id");
        when(menus.findById("a-id")).thenReturn(Optional.of(a));
        when(menus.findById("b-id")).thenReturn(Optional.of(b));
        NavigationMenuRequest moveAUnderB = new NavigationMenuRequest("b-id", "a", "a", "/a", null, null, 0,
                NavigationMenuType.DIRECTORY, null, true, true, false);

        assertThatThrownBy(() -> service().update("a-id", moveAUnderB)).isInstanceOf(ApiException.class);

        var order = org.mockito.Mockito.inOrder(menus);
        order.verify(menus).lockAllForStructureChange();
        order.verify(menus).findById("a-id");
    }

    private NavigationMenu directory(String code, String path, String id) {
        NavigationMenu menu = new NavigationMenu(null, code, code, path, null, null, 0,
                NavigationMenuType.DIRECTORY, null, true, true, false, false);
        ReflectionTestUtils.setField(menu, "id", id);
        return menu;
    }

    private NavigationService service() {
        return new NavigationService(menus, permissions, audit);
    }

    private NavigationMenu page(String code, String path, String componentKey, String permission,
            boolean systemManaged) {
        return new NavigationMenu(null, code, code, path, componentKey, null, 0,
                NavigationMenuType.PAGE, permission, true, true, false, systemManaged);
    }
}
