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
        seed(directory, "closet-feedback", "用户反馈", "/closet/feedback", "closet-feedback", "ri/feedback-line", 22, "closet:feedback:read");
        seed(directory, "closet-registration", "注册与邀请", "/closet/registration", "closet-registration", "ri/coupon-3-line", 25, "closet:registration:read");
        seed(directory, "closet-account-events", "账号审计", "/closet/account-events", "closet-account-events", "ri/file-list-3-line", 27, "closet:user:read");
        seed(directory, "closet-security-audit", "安全审计", "/closet/security-audit", "closet-security-audit", "ri/shield-check-line", 28, "closet:security:read");
        seed(directory, "closet-ops", "运行监控", "/closet/ops", "closet-ops", "ri/pulse-line", 30, "closet:ops:read");
        seed(directory, "closet-models", "模型调配", "/closet/models", "closet-models", "ri/settings-3-line", 31, "closet:models:read");
        seed(directory, "closet-exports", "数据导出", "/closet/exports", "closet-exports", "ri/download-2-line", 40, "closet:exports:read");
        seed(directory, "closet-templates", "模板审核", "/closet/templates", "closet-templates", "ri/checkbox-multiple-line", 50, "closet:templates:read");
        seed(directory, "closet-samples", "整套样本", "/closet/samples", "closet-samples", "ri/image-edit-line", 52, "closet:samples:read");
        seed(directory, "closet-style-terms", "风格词审核", "/closet/style-terms", "closet-style-terms", "ri/price-tag-3-line", 55, "closet:styleterms:read");
        seed(directory, "closet-tryon", "实时试穿", "/closet/tryon", "closet-tryon", "ri/vidicon-line", 35, "closet:tryon:read");
        NavigationMenu creation = menus.findByCode("closet-creation").orElseGet(() -> menus.save(new NavigationMenu(
                null, "closet-creation", "穿搭创作", "/creation", null, "ri/image-line", 155,
                NavigationMenuType.DIRECTORY, "closet:creation:read", true, true, false, true)));
        // Guide and model capabilities stay routable pages but leave the menu (the editor links to them).
        seed(creation, "closet-creation-guide", "使用指南", "/creation/guide", "closet-creation-guide", "ri/book-open-line", 0, "closet:creation:read").setVisible(false);
        NavigationMenu effects = seed(creation, "closet-creation-templates", "创作效果", "/creation/templates", "closet-creation-templates", "ri/image-line", 0, "closet:creation:read");
        if ("模板".equals(effects.getTitle())) effects.rename("创作效果");
        if (effects.getSortOrder() == 10) effects.reorder(0);
        seed(creation, "closet-creation-capabilities", "模型能力", "/creation/capabilities", "closet-creation-capabilities", "ri/settings-3-line", 20, "closet:creation:read").setVisible(false);
        seed(creation, "closet-creation-usage", "用量", "/creation/usage", "closet-creation-usage", "ri/bar-chart-line", 30, "closet:creation:read");
        seed(directory, "closet-creation-showcases", "官方素材", "/creation/showcases", "closet-creation-showcases", "ri/gallery-line", 57, "closet:showcase:read");
        seed(directory, "closet-presets", "预设衣物", "/closet/presets", "closet-presets", "ri/t-shirt-2-line", 58, "closet:presets:read");
        seed(directory, "closet-onboarding", "引导封面", "/closet/onboarding", "closet-onboarding", "ri/image-add-line", 59, "closet:onboarding:read");
        NavigationMenu tryContent = menus.findByCode("closet-try-content").orElseGet(() -> menus.save(new NavigationMenu(
                null, "closet-try-content", "试穿内容", "/try-content", null, "ri/vidicon-line", 157,
                NavigationMenuType.DIRECTORY, null, true, true, false, true)));
        seed(tryContent, "closet-looks", "精选整套", "/closet/looks", "closet-looks", "ri/gallery-line", 10, "closet:looks:read");
        seed(tryContent, "closet-poses", "动作管理", "/closet/poses", "closet-poses", "ri/user-3-line", 20, "closet:poses:read");
        NavigationMenu billing = menus.findByCode("closet-billing").orElseGet(() -> menus.save(new NavigationMenu(
                null, "closet-billing", "收费管理", "/billing", null, "ri/money-cny-circle-line", 160,
                NavigationMenuType.DIRECTORY, "closet:billing:read", true, true, false, true)));
        seed(billing, "closet-billing-settings", "总开关与能力开关", "/billing/settings", "closet-billing-settings", "ri/money-cny-circle-line", 10, "closet:billing:read");
        seed(billing, "closet-billing-tiers", "档位与成本", "/billing/tiers", "closet-billing-tiers", "ri/money-cny-circle-line", 20, "closet:billing:read");
        seed(billing, "closet-billing-products", "商品与订单", "/billing/products", "closet-billing-products", "ri/money-cny-circle-line", 30, "closet:billing:read");
        seed(billing, "closet-billing-redemptions", "兑换码批次", "/billing/redemptions", "closet-billing-redemptions", "ri/money-cny-circle-line", 40, "closet:billing:read");
        seed(billing, "closet-billing-margin", "利润报表", "/billing/margin", "closet-billing-margin", "ri/money-cny-circle-line", 50, "closet:billing:read");
        seed(directory, "closet-users", "业务用户", "/closet/users", "closet-users", "ri/group-line", 20, "closet:user:read");
    }
    private NavigationMenu seed(NavigationMenu parent, String code, String title, String path, String component,
                      String icon, int order, String permission) {
        NavigationMenu menu = menus.findByCode(code).orElseGet(() -> menus.save(new NavigationMenu(
                parent.getId(), code, title, path, component, icon, order, NavigationMenuType.PAGE,
                permission, true, true, false, true)));
        menu.moveTo(parent.getId());
        menu.upgradeLegacyIcon(icon);
        return menu;
    }
}
