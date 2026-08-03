package com.yumg.starter.modules.runtimeconfig.application;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RuntimeComponentScheduler {
    private final RuntimeSettingService settings;
    private final RuntimeComponentRegistry components;
    private final AtomicLong lastRun = new AtomicLong(System.currentTimeMillis());

    public RuntimeComponentScheduler(RuntimeSettingService settings,
                                     RuntimeComponentRegistry components) {
        this.settings = settings;
        this.components = components;
    }

    @Scheduled(fixedDelay = 10000)
    public void refreshWhenDue() {
        if (!settings.enabled("runtime.dependency-health.enabled")) return;
        long now = Instant.now().toEpochMilli();
        long interval = settings.integer("runtime.dependency-health.interval-seconds") * 1000L;
        long previous = lastRun.get();
        if (now - previous >= interval && lastRun.compareAndSet(previous, now)) {
            components.refreshAll();
        }
    }
}
