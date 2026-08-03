package com.yumg.starter.modules.runtimeconfig.api;

import com.yumg.starter.modules.runtimeconfig.api.dto.RuntimeComponentResponse;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponentRegistry;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compatibility alias for clients that still use the original dependency-check routes.
 * New clients should use {@code /api/v1/system/runtime-checks}.
 */
@Deprecated(forRemoval = false)
@RestController
@RequestMapping("/api/v1/system/dependencies")
public class RuntimeDependencyController {
    private final RuntimeComponentRegistry checks;

    public RuntimeDependencyController(RuntimeComponentRegistry checks) {
        this.checks = checks;
    }

    @GetMapping
    @Operation(summary = "列出运行时检查（兼容接口）", deprecated = true)
    @PreAuthorize("hasAuthority('system:config:read')")
    public List<RuntimeComponentResponse> list() { return checks.list(); }

    @PostMapping("/refresh")
    @Operation(summary = "刷新全部运行时检查（兼容接口）", deprecated = true)
    @PreAuthorize("hasAuthority('system:config:write')")
    public List<RuntimeComponentResponse> refreshAll() { return checks.refreshAll(); }

    @PostMapping("/{code}/refresh")
    @Operation(summary = "刷新指定运行时检查（兼容接口）", deprecated = true)
    @PreAuthorize("hasAuthority('system:config:write')")
    public RuntimeComponentResponse refresh(@PathVariable String code) { return checks.refresh(code); }
}
