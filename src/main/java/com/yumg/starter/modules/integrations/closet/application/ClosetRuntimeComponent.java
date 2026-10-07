package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.runtimeconfig.application.ComponentCheckResult;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCheckKind;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponent;
import java.util.List;
import java.util.Map;

public class ClosetRuntimeComponent implements RuntimeComponent {
    private final ClosetManagementClient client;
    public ClosetRuntimeComponent(ClosetManagementClient client) { this.client = client; }
    @Override public String code() { return "closet-service"; }
    @Override public String displayName() { return "鱼搭管理服务"; }
    @Override public RuntimeCheckKind kind() { return RuntimeCheckKind.SERVICE_DEPENDENCY; }
    @Override public ComponentCheckResult check() {
        try {
            client.get("/users", Map.of("size", List.of("1")));
            return ComponentCheckResult.up("鱼搭管理接口可用");
        } catch (RuntimeException exception) {
            return ComponentCheckResult.error("鱼搭管理接口不可用");
        }
    }
}
