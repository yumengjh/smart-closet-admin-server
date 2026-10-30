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
                null, "closet", "鱼搭业务", "/closet", null, "ri/t-shirt-line", 150,
                NavigationMenuType.DIRECTORY, null, true, true, false, true)));
        directory.upgradeLegacyIcon("ri/t-shirt-line");
        // The business screen is the home page now; drop the duplicate entry seeded earlier.
        menus.findByCode("closet-dashboard").ifPresent(menus::delete);
        seed(directory, "closet-insights", "衣柜洞察", "/closet/insights", "closet-insights", "ri/bar-chart-box-line", 10, "closet:insights:read");
        seed(directory, "closet-registration", "注册与邀请", "/closet/registration", "closet-registration", "ri/coupon-3-line", 25, "closet:registration:read");
        seed(directory, "closet-account-events", "账号审计", "/closet/account-events", "closet-account-events", "ri/file-list-3-line", 27, "closet:user:read");
        seed(directory, "closet-ops", "运行监控", "/closet/ops", "closet-ops", "ri/pulse-line", 30, "closet:ops:read");
        seed(directory, "closet-exports", "数据导出", "/closet/exports", "closet-exports", "ri/download-2-line", 40, "closet:exports:read");
        seed(directory, "closet-templates", "模板审核", "/closet/templates", "closet-templates", "ri/checkbox-multiple-line", 50, "closet:templates:read");
        seed(directory, "closet-style-terms", "风格词审核", "/closet/style-terms", "closet-style-terms", "ri/price-tag-3-line", 55, "closet:styleterms:read");
        seed(directory, "closet-users", "业务用户", "/closet/users", "closet-users", "ri/group-line", 20, "closet:user:read");
    }
    private void seed(NavigationMenu parent, String code, String title, String path, String component,
                      String icon, int order, String permission) {
        NavigationMenu menu = menus.findByCode(code).orElseGet(() -> menus.save(new NavigationMenu(
                parent.getId(), code, title, path, component, icon, order, NavigationMenuType.PAGE,
                permission, true, true, false, true)));
        menu.moveTo(parent.getId());
        menu.upgradeLegacyIcon(icon);
    }
}
