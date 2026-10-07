package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.entities.NavigationMenu;
import com.yumg.starter.entities.NavigationMenuType;
import com.yumg.starter.modules.navigation.infrastructure.NavigationMenuRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetNavigationBootstrap implements ApplicationRunner {
    private final NavigationMenuRepository menus;
    public ClosetNavigationBootstrap(NavigationMenuRepository menus) { this.menus = menus; }
    @Override @Transactional public void run(ApplicationArguments args) {
        NavigationMenu directory = menus.findByCode("closet").orElseGet(() -> menus.save(new NavigationMenu(
                null, "closet", "鱼搭业务", "/closet", null, "Connection", 150,
                NavigationMenuType.DIRECTORY, null, true, true, false, true)));
        seed(directory, "closet-insights", "衣柜洞察", "/closet/insights", "closet-insights", 10, "closet:insights:read");
        seed(directory, "closet-users", "业务用户", "/closet/users", "closet-users", 20, "closet:user:read");
    }
    private void seed(NavigationMenu parent, String code, String title, String path, String component,
                      int order, String permission) {
        NavigationMenu menu = menus.findByCode(code).orElseGet(() -> menus.save(new NavigationMenu(
                parent.getId(), code, title, path, component, "User", order, NavigationMenuType.PAGE,
                permission, true, true, false, true)));
        menu.moveTo(parent.getId());
    }
}
