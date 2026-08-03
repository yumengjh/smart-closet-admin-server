package com.yumg.starter.modules.runtimeconfig.application;

import com.yumg.starter.common.api.ApiException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RuntimeCacheRegistry {
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
        return providers.values().stream().map(RuntimeCacheProvider::forceRefresh).toList();
    }

    public RuntimeCacheStatus refresh(String code) {
        RuntimeCacheProvider provider = providers.get(code);
        if (provider == null) throw ApiException.notFound();
        return provider.forceRefresh();
    }

    public void refreshChanged() {
        providers.values().forEach(RuntimeCacheProvider::refreshIfChanged);
    }
}
