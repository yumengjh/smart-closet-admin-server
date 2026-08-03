package com.yumg.starter.modules.runtimeconfig.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yumg.starter.entities.RuntimeComponentStatus;
import com.yumg.starter.modules.runtimeconfig.infrastructure.RuntimeComponentStatusRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeComponentRegistryTest {
    private final RuntimeComponentStatusRepository statuses = mock(RuntimeComponentStatusRepository.class);
    private final RuntimeSettingService settings = mock(RuntimeSettingService.class);
    private final Map<String, RuntimeComponentStatus> stored = new HashMap<>();
    private ToggleComponent component;
    private RuntimeComponentRegistry registry;

    @BeforeEach
    void setUp() {
        component = new ToggleComponent();
        when(statuses.findByComponentCode(any())).thenAnswer(invocation ->
                Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(statuses.save(any())).thenAnswer(invocation -> {
            RuntimeComponentStatus status = invocation.getArgument(0);
            stored.put(status.getComponentCode(), status);
            return status;
        });
        registry = new RuntimeComponentRegistry(statuses, settings, List.of(component));
        registry.initialize();
    }

    @Test
    void initializesAndPublishesSuccessfulAndFailedChecks() {
        assertThat(registry.list()).singleElement().satisfies(status ->
                assertThat(status.status()).isEqualTo("UNKNOWN"));

        component.result = ComponentCheckResult.up("ready");
        assertThat(registry.refresh(component.code()).status()).isEqualTo("UP");
        assertThat(registry.isUp(component.code())).isTrue();

        component.result = ComponentCheckResult.error("down\nnow");
        assertThat(registry.refresh(component.code()).message()).isEqualTo("down now");
        assertThat(registry.isUp(component.code())).isFalse();
        assertThat(registry.refreshedAt()).isNotNull();
    }

    @Test
    void supportsDisabledThrownAndReportedFailures() {
        component.enabled = false;
        assertThat(registry.refresh(component.code()).status()).isEqualTo("DISABLED");

        component.enabled = true;
        component.throwFailure = true;
        assertThat(registry.refresh(component.code()).status()).isEqualTo("ERROR");

        registry.reportFailure(component.code(), "manual failure");
        assertThat(registry.list().getFirst().message()).isEqualTo("manual failure");
        registry.reportFailure("unknown", "ignored");

        assertThatThrownBy(() -> registry.refresh("unknown")).hasMessage("Resource not found");
    }

    @Test
    void refreshAllOnlyChecksDependenciesAndDoesNotReloadConfiguration() {
        component.result = ComponentCheckResult.up("ok");
        assertThat(registry.refreshAll()).singleElement().satisfies(status ->
                assertThat(status.status()).isEqualTo("UP"));
        verify(settings, never()).reload();
    }

    private static final class ToggleComponent implements RuntimeComponent {
        private ComponentCheckResult result = ComponentCheckResult.up("ok");
        private boolean enabled = true;
        private boolean throwFailure;

        @Override public String code() { return "test-component"; }
        @Override public String displayName() { return "Test Component"; }
        @Override public ComponentCheckResult check() {
            if (throwFailure) throw new IllegalStateException();
            return result;
        }
        @Override public boolean enabled(RuntimeSettingService settings) { return enabled; }
    }
}
