package com.yumg.starter.modules.rbac.application;

import com.yumg.starter.entities.Permission;
import com.yumg.starter.entities.Role;
import com.yumg.starter.modules.rbac.infrastructure.PermissionRepository;
import com.yumg.starter.modules.rbac.infrastructure.RoleRepository;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RbacBootstrap implements ApplicationRunner {
    private static final List<PermissionSeed> PERMISSIONS = List.of(
            new PermissionSeed("system:user:read", "查看用户列表与用户资料"),
            new PermissionSeed("system:user:write", "修改用户资料与账号状态"),
            new PermissionSeed("system:role:read", "查看角色与权限目录"),
            new PermissionSeed("system:role:write", "管理角色、权限与角色授权"),
            new PermissionSeed("system:role:assign", "为用户分配或移除角色"),
            new PermissionSeed("system:menu:read", "查看后台菜单配置"),
            new PermissionSeed("system:menu:write", "新建、编辑或删除后台菜单"),
            new PermissionSeed("system:config:read", "查看运行时系统配置"),
            new PermissionSeed("system:config:write", "修改运行时系统配置"),
            new PermissionSeed("system:audit:read", "查看系统审计日志"),
            new PermissionSeed("system:gc:read", "查看数据清理中心的资源、策略和执行记录"),
            new PermissionSeed("system:gc:write", "管理数据清理策略并执行清理任务"),
            new PermissionSeed("system:sql:execute", "在本地开发环境执行 SQL 调试语句"),
            new PermissionSeed("example:announcement:read", "查看公告管理内容"),
            new PermissionSeed("example:announcement:write", "创建、编辑与发布公告"),
            new PermissionSeed("example:announcement:delete", "删除公告"));
    private final PermissionRepository permissions; private final RoleRepository roles;
    public RbacBootstrap(PermissionRepository permissions, RoleRepository roles) { this.permissions = permissions; this.roles = roles; }
    @Override @Transactional public void run(ApplicationArguments args) {
        List<Permission> all = PERMISSIONS.stream().map(seed -> permissions.findByCode(seed.code())
                .map(permission -> refreshLegacyDescription(permission, seed))
                .orElseGet(() -> permissions.save(new Permission(seed.code(), seed.description())))).toList();
        Role superAdmin = roles.findByCode("SUPER_ADMIN").orElseGet(() -> roles.save(new Role("SUPER_ADMIN", "Super Administrator")));
        all.forEach(superAdmin::grant);
        roles.save(superAdmin);
        roles.findByCode("ADMIN").orElseGet(() -> roles.save(new Role("ADMIN", "Administrator")));
        roles.findByCode("USER").orElseGet(() -> roles.save(new Role("USER", "User")));
    }

    private Permission refreshLegacyDescription(Permission permission, PermissionSeed seed) {
        String description = permission.getDescription();
        if (description == null || description.isBlank() || description.equals(permission.getCode())) {
            permission.changeDescription(seed.description());
        }
        return permission;
    }

    private record PermissionSeed(String code, String description) {}
}
