package com.yumg.starter.modules.runtimeconfig.api;

import com.yumg.starter.modules.runtimeconfig.api.dto.RuntimeComponentResponse;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponentRegistry;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/dependencies")
public class RuntimeDependencyController {
    private final RuntimeComponentRegistry dependencies;

    public RuntimeDependencyController(RuntimeComponentRegistry dependencies) {
        this.dependencies = dependencies;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:config:read')")
    public List<RuntimeComponentResponse> list() {
        return dependencies.list();
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasAuthority('system:config:write')")
    public List<RuntimeComponentResponse> refreshAll() {
        return dependencies.refreshAll();
    }

    @PostMapping("/{code}/refresh")
    @PreAuthorize("hasAuthority('system:config:write')")
    public RuntimeComponentResponse refresh(@PathVariable String code) {
        return dependencies.refresh(code);
    }
}
