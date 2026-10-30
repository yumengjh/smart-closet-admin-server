package com.yumg.starter.modules.navigation.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.NavigationMenu;
import com.yumg.starter.entities.NavigationMenuType;
import com.yumg.starter.modules.navigation.api.dto.NavigationMenuRequest;
import com.yumg.starter.modules.navigation.api.dto.NavigationMenuResponse;
import com.yumg.starter.modules.navigation.api.dto.NavigationRouteResponse;
import com.yumg.starter.modules.navigation.infrastructure.NavigationMenuRepository;
import com.yumg.starter.modules.rbac.infrastructure.PermissionRepository;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NavigationService {
    private static final Logger log = LoggerFactory.getLogger(NavigationService.class);

    private static final Set<String> COMPONENT_KEYS = Set.of("welcome", "permission-page",
            "menu-management", "user-management", "rbac-management", "runtime-config",
            "ip-rule-management", "audit-log", "announcement-management",
            "account-security", "gc-management", "sql-console",
            "closet-users", "closet-insights", "closet-dashboard", "closet-ops", "closet-exports", "closet-templates", "closet-style-terms");

    private final NavigationMenuRepository menus;
    private final PermissionRepository permissions;
    private final AuditService audit;

    public NavigationService(NavigationMenuRepository menus, PermissionRepository permissions,
            AuditService audit) {
        this.menus = menus;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<NavigationMenuResponse> list() {
        return menuTree(menus.findAllByOrderBySortOrderAscCodeAsc());
    }

    @Transactional(readOnly = true)
    public List<NavigationRouteResponse> routes(Set<String> grantedPermissions) {
        List<NavigationMenu> all = menus.findAllByOrderBySortOrderAscCodeAsc();
        Map<String, NavigationMenu> byId = all.stream()
                .collect(Collectors.toMap(NavigationMenu::getId, Function.identity(), (left, right) -> left));
        List<NavigationMenu> permitted = all.stream()
                .filter(menu -> permitted(menu, grantedPermissions, byId))
                .toList();
        return routeTree(permitted);
    }

    @Transactional
    public NavigationMenuResponse create(NavigationMenuRequest request) {
        menus.lockAllForStructureChange();
        if (menus.findByCode(request.code()).isPresent()) {
            throw ApiException.conflict();
        }
        validate(request, null);
        NavigationMenu menu = menus.save(new NavigationMenu(normalize(request.parentId()), request.code(),
                request.title().trim(), request.routePath(), normalize(request.componentKey()),
                normalize(request.icon()), request.sortOrder(), request.menuType(),
                normalize(request.requiredPermission()), request.visible(), request.enabled(),
                request.keepAlive(), false));
        audit.event("NAVIGATION_MENU_CREATED", "NavigationMenu", menu.getCode());
        return NavigationMenuResponse.from(menu, List.of());
    }

    @Transactional
    public NavigationMenuResponse update(String id, NavigationMenuRequest request) {
        // Lock before reading the parent chain; the per-row @Version cannot protect a multi-row invariant.
        menus.lockAllForStructureChange();
        NavigationMenu menu = menu(id);
        if (!menu.getCode().equals(request.code()) || menu.isSystemManaged()) {
            throw ApiException.conflict();
        }
        validate(request, id);
        menu.update(normalize(request.parentId()), request.title().trim(), request.routePath(),
                normalize(request.componentKey()), normalize(request.icon()), request.sortOrder(),
                request.menuType(), normalize(request.requiredPermission()), request.visible(),
                request.enabled(), request.keepAlive());
        audit.event("NAVIGATION_MENU_UPDATED", "NavigationMenu", menu.getCode());
        return NavigationMenuResponse.from(menu, List.of());
    }

    @Transactional
    public NavigationMenuResponse setEnabled(String id, boolean enabled) {
        NavigationMenu menu = menu(id);
        menu.setEnabled(enabled);
        audit.event("NAVIGATION_MENU_STATUS_UPDATED", "NavigationMenu", menu.getCode());
        return NavigationMenuResponse.from(menu, List.of());
    }

    @Transactional
    public void delete(String id) {
        menus.lockAllForStructureChange();
        NavigationMenu menu = menu(id);
        if (menu.isSystemManaged() || menus.existsByParentId(id)) {
            throw ApiException.conflict();
        }
        menus.delete(menu);
        audit.event("NAVIGATION_MENU_DELETED", "NavigationMenu", menu.getCode());
    }

    private void validate(NavigationMenuRequest request, String currentId) {
        String parentId = normalize(request.parentId());
        if (parentId != null) {
            NavigationMenu parent = menus.findById(parentId).orElseThrow(ApiException::notFound);
            if (parent.getMenuType() != NavigationMenuType.DIRECTORY
                    || reachesCurrentMenu(parent, currentId)) {
                throw ApiException.conflict();
            }
        }
        if (request.menuType() == NavigationMenuType.PAGE
                && !COMPONENT_KEYS.contains(normalize(request.componentKey()))) {
            throw ApiException.conflict();
        }
        if (request.menuType() == NavigationMenuType.DIRECTORY
                && normalize(request.componentKey()) != null) {
            throw ApiException.conflict();
        }
        String permission = normalize(request.requiredPermission());
        if (permission != null && permissions.findByCode(permission).isEmpty()) {
            throw ApiException.notFound();
        }
        if (menus.findAllByOrderBySortOrderAscCodeAsc().stream()
                .anyMatch(menu -> menu.getRoutePath().equals(request.routePath())
                        && !Objects.equals(menu.getId(), currentId))) {
            throw ApiException.conflict();
        }
    }

    private boolean permitted(NavigationMenu menu, Set<String> grantedPermissions,
            Map<String, NavigationMenu> byId) {
        // Iterative walk with a visited set: corrupted data with a parent cycle must not overflow the stack
        // or hang; menus on such a cycle are simply not routed, everything else stays available.
        Set<String> seen = new HashSet<>();
        NavigationMenu current = menu;
        while (true) {
            if (!seen.add(current.getId())) {
                log.warn("navigation menu parent cycle detected at menu={}", menu.getCode());
                return false;
            }
            if (!current.isVisible() || !current.isEnabled()) {
                return false;
            }
            if (current.getRequiredPermission() != null
                    && !grantedPermissions.contains(current.getRequiredPermission())) {
                return false;
            }
            String parentId = current.getParentId();
            if (parentId == null) {
                return true;
            }
            current = byId.get(parentId);
            if (current == null) {
                return false;
            }
        }
    }

    private boolean reachesCurrentMenu(NavigationMenu candidateParent, String currentId) {
        Set<String> seen = new HashSet<>();
        NavigationMenu current = candidateParent;
        while (current != null) {
            if (Objects.equals(currentId, current.getId())) {
                return true;
            }
            if (!seen.add(current.getId())) {
                // The existing chain is already cyclic: refuse to attach anything to it.
                return true;
            }
            String parentId = current.getParentId();
            current = parentId == null ? null : menus.findById(parentId).orElse(null);
        }
        return false;
    }

    private List<NavigationMenuResponse> menuTree(List<NavigationMenu> source) {
        Map<String, List<NavigationMenu>> children = children(source);
        return roots(source).stream().map(menu -> menuResponse(menu, children)).toList();
    }

    private NavigationMenuResponse menuResponse(NavigationMenu menu,
            Map<String, List<NavigationMenu>> children) {
        return NavigationMenuResponse.from(menu, children.getOrDefault(menu.getId(), List.of()).stream()
                .map(child -> menuResponse(child, children)).toList());
    }

    private List<NavigationRouteResponse> routeTree(List<NavigationMenu> source) {
        Map<String, List<NavigationMenu>> children = children(source);
        return roots(source).stream().map(menu -> routeResponse(menu, children)).toList();
    }

    private NavigationRouteResponse routeResponse(NavigationMenu menu,
            Map<String, List<NavigationMenu>> children) {
        return new NavigationRouteResponse(menu.getCode(), menu.getRoutePath(),
                menu.getComponentKey(), menu.getTitle(), menu.getIcon(), menu.getSortOrder(),
                menu.isKeepAlive(), menu.getRequiredPermission(),
                children.getOrDefault(menu.getId(), List.of()).stream()
                        .map(child -> routeResponse(child, children)).toList());
    }

    private Map<String, List<NavigationMenu>> children(Collection<NavigationMenu> source) {
        return source.stream().filter(menu -> menu.getParentId() != null)
                .collect(Collectors.groupingBy(NavigationMenu::getParentId));
    }

    private List<NavigationMenu> roots(List<NavigationMenu> source) {
        Set<String> ids = source.stream().map(NavigationMenu::getId).collect(Collectors.toSet());
        return source.stream().filter(menu -> menu.getParentId() == null || !ids.contains(menu.getParentId()))
                .toList();
    }

    private NavigationMenu menu(String id) {
        return menus.findById(id).orElseThrow(ApiException::notFound);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
