package com.yumg.starter.modules.runtimeconfig.application;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RuntimeCacheScheduler {
    private final RuntimeSettingService settings;
    private final RuntimeCacheRegistry caches;
    private final AtomicLong lastRun = new AtomicLong(System.currentTimeMillis());

    public RuntimeCacheScheduler(RuntimeSettingService settings, RuntimeCacheRegistry caches) {
        this.settings = settings;
        this.caches = caches;
    }

    @Scheduled(fixedDelay = 10000)
    public void refreshWhenDue() {
        if (!settings.enabled("runtime.config-refresh.enabled")) {
            settings.refreshIfChanged();
            return;
        }
        long now = Instant.now().toEpochMilli();
        long interval = settings.integer("runtime.config-refresh.interval-seconds") * 1000L;
        long previous = lastRun.get();
        if (now - previous >= interval && lastRun.compareAndSet(previous, now)) {
            caches.refreshChanged();
        }
    }
}
