package com.yumg.starter.modules.runtimeconfig.api;

import com.yumg.starter.modules.runtimeconfig.application.RuntimeCacheRegistry;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCacheStatus;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system/runtime-caches")
public class RuntimeCacheController {
    private final RuntimeCacheRegistry caches;

    public RuntimeCacheController(RuntimeCacheRegistry caches) {
        this.caches = caches;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('system:config:read')")
    public List<RuntimeCacheStatus> list() { return caches.list(); }

    @PostMapping("/refresh")
    @PreAuthorize("hasAuthority('system:config:write')")
    public List<RuntimeCacheStatus> refreshAll() { return caches.refreshAll(); }

    @PostMapping("/{code}/refresh")
    @PreAuthorize("hasAuthority('system:config:write')")
    public RuntimeCacheStatus refresh(@PathVariable String code) { return caches.refresh(code); }
}
