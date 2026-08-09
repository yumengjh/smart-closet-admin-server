package com.yumg.starter.modules.runtimeconfig.application;

import com.yumg.starter.common.api.ApiException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RuntimeCacheRegistry {
    private static final Logger log = LoggerFactory.getLogger(RuntimeCacheRegistry.class);
    private final Map<String, RuntimeCacheProvider> providers;

    public RuntimeCacheRegistry(List<RuntimeCacheProvider> providers) {
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(RuntimeCacheProvider::order)
                        .thenComparing(RuntimeCacheProvider::code))
                .collect(Collectors.toMap(RuntimeCacheProvider::code, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
    }

    public List<RuntimeCacheStatus> list() {
        return providers.values().stream().map(RuntimeCacheProvider::status).toList();
    }

    public List<RuntimeCacheStatus> refreshAll() {
        return providers.values().stream().map(this::refreshSafely).toList();
    }

    public RuntimeCacheStatus refresh(String code) {
        RuntimeCacheProvider provider = providers.get(code);
        if (provider == null) throw ApiException.notFound();
        return provider.forceRefresh();
    }

    public void refreshChanged() {
        providers.values().forEach(provider -> {
            try { provider.refreshIfChanged(); }
            catch (RuntimeException exception) {
                log.warn("Runtime cache reconciliation failed for {}", provider.code(), exception);
            }
        });
    }

    private RuntimeCacheStatus refreshSafely(RuntimeCacheProvider provider) {
        try { return provider.forceRefresh(); }
        catch (RuntimeException exception) {
            log.warn("Runtime cache refresh failed for {}", provider.code(), exception);
            return provider.status();
        }
    }
}
