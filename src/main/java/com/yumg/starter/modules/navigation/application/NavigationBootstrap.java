package com.yumg.starter.modules.navigation.application;

import com.yumg.starter.entities.NavigationMenu;
import com.yumg.starter.entities.NavigationMenuType;
import com.yumg.starter.modules.navigation.infrastructure.NavigationMenuRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class NavigationBootstrap implements ApplicationRunner {

    private final NavigationMenuRepository menus;

    public NavigationBootstrap(NavigationMenuRepository menus) {
        this.menus = menus;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedPage(null, "home", "首页", "/welcome", "welcome", "ep/home-filled", 0, null);

        NavigationMenu system = seedDirectory("system", "系统管理", "/system", "ri/settings-3-line", 100);
        seedPage(system, "system-overview", "运行概览", "/system/overview", "welcome",
                "ri/computer-line", 5, null);
        seedPage(system, "user-management", "用户管理", "/system/users", "user-management", "ri/user-settings-line",
                10, "system:user:read");
        seedPage(system, "rbac-management", "角色与权限", "/system/rbac", "rbac-management", "ri/shield-keyhole-line",
                20, "system:role:read");
        seedPage(system, "menu-management", "菜单管理", "/system/menu", "menu-management", "ri/menu-line", 30,
                "system:menu:read");
        seedPage(system, "runtime-config", "运行配置", "/system/config", "runtime-config", "ri/tools-line", 40,
                "system:config:read");
        seedPage(system, "ip-rule-management", "IP 访问规则", "/system/ip-rules", "ip-rule-management",
                "ri/global-line", 50, "system:config:read");
        seedPage(system, "audit-log", "审计日志", "/system/audit", "audit-log", "ri/file-list-3-line", 60,
                "system:audit:read");
        seedPage(system, "gc-management", "数据清理中心", "/system/gc", "gc-management", "ri/delete-bin-line", 70,
                "system:gc:read");
        seedPage(system, "sql-console", "SQL 执行台", "/system/sql-console", "sql-console", "ri/terminal-box-line", 80,
                "system:sql:execute");

        NavigationMenu content = seedDirectory("content", "内容管理", "/content", "ri/article-line", 200);
        seedPage(content, "announcement-management", "公告管理", "/content/announcements",
                "announcement-management", "ri/notification-3-line", 10, "example:announcement:read");

        NavigationMenu account = seedDirectory("account", "个人中心", "/account", "ri/user-3-line", 300);
        seedPage(account, "account-security", "账户安全", "/account/security", "account-security",
                "ri/lock-password-line", 10, null);
    }

    private NavigationMenu seedDirectory(String code, String title, String path, String icon,
            int sortOrder) {
        NavigationMenu menu = menus.findByCode(code).orElseGet(() -> menus.save(new NavigationMenu(null,
                code, title, path, null, icon, sortOrder, NavigationMenuType.DIRECTORY, null,
                true, true, false, true)));
        menu.upgradeLegacyIcon(icon);
        return menu;
    }

    private void seedPage(NavigationMenu parent, String code, String title, String path,
            String componentKey, String icon, int sortOrder, String requiredPermission) {
        NavigationMenu menu = menus.findByCode(code).orElseGet(() -> menus.save(new NavigationMenu(
                parent == null ? null : parent.getId(), code, title, path, componentKey, icon,
                sortOrder, NavigationMenuType.PAGE, requiredPermission, true, true, false, true)));
        menu.moveTo(parent == null ? null : parent.getId());
        menu.upgradeLegacyIcon(icon);
    }
}
