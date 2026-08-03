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
@RequestMapping("/api/v1/system/runtime-checks")
public class RuntimeCheckController {
    private final RuntimeComponentRegistry checks;

    public RuntimeCheckController(RuntimeComponentRegistry checks) {
        this.checks = checks;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:config:read')")
    public List<RuntimeComponentResponse> list() { return checks.list(); }

    @PostMapping("/refresh")
    @PreAuthorize("hasAuthority('system:config:write')")
    public List<RuntimeComponentResponse> refreshAll() { return checks.refreshAll(); }

    @PostMapping("/{code}/refresh")
    @PreAuthorize("hasAuthority('system:config:write')")
    public RuntimeComponentResponse refresh(@PathVariable String code) { return checks.refresh(code); }
}
