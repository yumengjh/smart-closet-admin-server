package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.entities.Permission;
import com.yumg.starter.entities.Role;
import com.yumg.starter.modules.rbac.infrastructure.PermissionRepository;
import com.yumg.starter.modules.rbac.infrastructure.RoleRepository;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetPermissionBootstrap implements ApplicationRunner {
    private static final List<PermissionSeed> SEEDS = List.of(
            new PermissionSeed("closet:user:read", "查看鱼搭业务用户、会话、便携链接与额度"),
            new PermissionSeed("closet:user:write", "创建鱼搭用户、管理状态角色会话与便携链接"),
            new PermissionSeed("closet:user:reset-password", "重置鱼搭业务用户密码"),
            new PermissionSeed("closet:quota:write", "调整鱼搭业务用户额度"),
            new PermissionSeed("closet:insights:read", "查看鱼搭衣柜洞察"));
    private final PermissionRepository permissions;
    private final RoleRepository roles;
    public ClosetPermissionBootstrap(PermissionRepository permissions, RoleRepository roles) {
        this.permissions = permissions; this.roles = roles;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        Role superAdmin = roles.findByCode("SUPER_ADMIN").orElse(null);
        if (superAdmin == null) return;
        for (PermissionSeed seed : SEEDS) {
            Permission permission = permissions.findByCode(seed.code()).orElseGet(
                    () -> permissions.save(new Permission(seed.code(), seed.description())));
            superAdmin.grant(permission);
        }
        roles.save(superAdmin);
    }
    private record PermissionSeed(String code, String description) {}
}
