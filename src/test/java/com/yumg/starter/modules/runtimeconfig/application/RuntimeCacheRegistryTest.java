package com.yumg.starter.modules.runtimeconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeCacheRegistryTest {
    @Test
    void refreshesAllCachesInStableOrderAndSupportsSingleCacheRefresh() {
        FakeProvider settings = new FakeProvider("RUNTIME_SETTINGS", 0);
        FakeProvider ipRules = new FakeProvider("IP_ACCESS_RULES", 100);
        RuntimeCacheRegistry registry = new RuntimeCacheRegistry(List.of(ipRules, settings));

        assertThat(registry.refreshAll()).extracting(RuntimeCacheStatus::code)
                .containsExactly("RUNTIME_SETTINGS", "IP_ACCESS_RULES");
        assertThat(settings.forcedRefreshes).isEqualTo(1);
        assertThat(ipRules.forcedRefreshes).isEqualTo(1);

        assertThat(registry.refresh("IP_ACCESS_RULES").code()).isEqualTo("IP_ACCESS_RULES");
        assertThat(ipRules.forcedRefreshes).isEqualTo(2);
        assertThatThrownBy(() -> registry.refresh("UNKNOWN")).hasMessage("Resource not found");
    }

    @Test
    void automaticReconciliationChecksRevisionWithoutForcingEveryProviderReload() {
        FakeProvider provider = new FakeProvider("RUNTIME_SETTINGS", 0);
        RuntimeCacheRegistry registry = new RuntimeCacheRegistry(List.of(provider));

        registry.refreshChanged();

        assertThat(provider.changedRefreshes).isEqualTo(1);
        assertThat(provider.forcedRefreshes).isZero();
    }

    private static final class FakeProvider implements RuntimeCacheProvider {
        private final String code;
        private final int order;
        private int forcedRefreshes;
        private int changedRefreshes;

        private FakeProvider(String code, int order) {
            this.code = code;
            this.order = order;
        }

        @Override public String code() { return code; }
        @Override public String displayName() { return code; }
        @Override public int order() { return order; }
        @Override public RuntimeCacheStatus status() { return state(); }
        @Override public RuntimeCacheStatus forceRefresh() { forcedRefreshes++; return state(); }
        @Override public boolean refreshIfChanged() { changedRefreshes++; return false; }

        private RuntimeCacheStatus state() {
            return new RuntimeCacheStatus(code, code, 1, 1, true, Instant.EPOCH,
                    Instant.EPOCH, Instant.EPOCH, null, null, 1);
        }
    }
}
