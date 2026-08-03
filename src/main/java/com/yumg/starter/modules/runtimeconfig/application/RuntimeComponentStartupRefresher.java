package com.yumg.starter.modules.runtimeconfig.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class RuntimeComponentStartupRefresher {
    private final RuntimeComponentRegistry components;
    private final boolean enabled;

    public RuntimeComponentStartupRefresher(RuntimeComponentRegistry components,
            @Value("${app.runtime.component-startup-probe.enabled:true}") boolean enabled) {
        this.components = components;
        this.enabled = enabled;
    }

    @EventListener
    @Order(0)
    public void refresh(ApplicationReadyEvent event) {
        if (enabled) components.refreshAll();
    }
}
