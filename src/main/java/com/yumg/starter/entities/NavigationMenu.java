package com.yumg.starter.entities;

import com.yumg.starter.common.entity.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "navigation_menus")
public class NavigationMenu extends AuditedEntity {

    @Column(name = "parent_id", length = 36)
    private String parentId;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(name = "route_path", nullable = false, unique = true, length = 200)
    private String routePath;

    @Column(name = "component_key", length = 100)
    private String componentKey;

    @Column(length = 100)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "menu_type", nullable = false, length = 20)
    private NavigationMenuType menuType;

    @Column(name = "required_permission", length = 160)
    private String requiredPermission;

    @Column(nullable = false)
    private boolean visible;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "keep_alive", nullable = false)
    private boolean keepAlive;

    @Column(name = "system_managed", nullable = false)
    private boolean systemManaged;

    protected NavigationMenu() {
    }

    public NavigationMenu(String parentId, String code, String title, String routePath,
            String componentKey, String icon, int sortOrder, NavigationMenuType menuType,
            String requiredPermission, boolean visible, boolean enabled, boolean keepAlive,
            boolean systemManaged) {
        this.parentId = parentId;
        this.code = code;
        this.title = title;
        this.routePath = routePath;
        this.componentKey = componentKey;
        this.icon = icon;
        this.sortOrder = sortOrder;
        this.menuType = menuType;
        this.requiredPermission = requiredPermission;
        this.visible = visible;
        this.enabled = enabled;
        this.keepAlive = keepAlive;
        this.systemManaged = systemManaged;
    }

    public String getParentId() { return parentId; }
    public String getCode() { return code; }
    public String getTitle() { return title; }
    public String getRoutePath() { return routePath; }
    public String getComponentKey() { return componentKey; }
    public String getIcon() { return icon; }
    public int getSortOrder() { return sortOrder; }
    public NavigationMenuType getMenuType() { return menuType; }
    public String getRequiredPermission() { return requiredPermission; }
    public boolean isVisible() { return visible; }
    public boolean isEnabled() { return enabled; }
    public boolean isKeepAlive() { return keepAlive; }
    public boolean isSystemManaged() { return systemManaged; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public void moveTo(String parentId) {
        this.parentId = parentId;
    }

    /**
     * Bare Element Plus names such as "Setting" never rendered in the sidebar. Upgrade only those
     * to an icon key the frontend registers; keys an administrator already chose stay untouched.
     */
    public void upgradeLegacyIcon(String icon) {
        if (this.icon != null && !this.icon.contains("/") && !this.icon.contains(":")) {
            this.icon = icon;
        }
    }

    public void update(String parentId, String title, String routePath, String componentKey,
            String icon, int sortOrder, NavigationMenuType menuType, String requiredPermission,
            boolean visible, boolean enabled, boolean keepAlive) {
        this.parentId = parentId;
        this.title = title;
        this.routePath = routePath;
        this.componentKey = componentKey;
        this.icon = icon;
        this.sortOrder = sortOrder;
        this.menuType = menuType;
        this.requiredPermission = requiredPermission;
        this.visible = visible;
        this.enabled = enabled;
        this.keepAlive = keepAlive;
    }
}
