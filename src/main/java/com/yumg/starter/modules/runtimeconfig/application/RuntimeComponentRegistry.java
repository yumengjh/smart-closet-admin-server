package com.yumg.starter.modules.runtimeconfig.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.RuntimeComponentState;
import com.yumg.starter.entities.RuntimeComponentStatus;
import com.yumg.starter.modules.runtimeconfig.api.dto.RuntimeComponentResponse;
import com.yumg.starter.modules.runtimeconfig.infrastructure.RuntimeComponentStatusRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RuntimeComponentRegistry {
    private final RuntimeComponentStatusRepository statuses;
    private final RuntimeSettingService settings;
    private final Map<String, RuntimeComponent> components;
    private volatile Map<String, RuntimeComponentResponse> snapshot = Map.of();
    private volatile Instant refreshedAt;

    public RuntimeComponentRegistry(RuntimeComponentStatusRepository statuses,
                                    RuntimeSettingService settings,
                                    List<RuntimeComponent> components) {
        this.statuses = statuses;
        this.settings = settings;
        this.components = components.stream().collect(Collectors.toMap(RuntimeComponent::code,
                Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    @PostConstruct
    void initialize() {
        Map<String, RuntimeComponentResponse> initial = new LinkedHashMap<>();
        components.values().forEach(component -> {
            RuntimeComponentStatus status = statuses.findByComponentCode(component.code())
                    .orElseGet(() -> statuses.save(new RuntimeComponentStatus(component.code())));
            initial.put(component.code(), RuntimeComponentResponse.from(status, component.displayName()));
        });
        snapshot = Map.copyOf(initial);
    }

    public List<RuntimeComponentResponse> list() {
        return snapshot.values().stream().sorted(Comparator.comparing(RuntimeComponentResponse::code)).toList();
    }

    public RuntimeComponentResponse refresh(String code) {
        RuntimeComponent component = components.get(code);
        if (component == null) throw ApiException.notFound();
        Instant checkedAt = Instant.now();
        long started = System.nanoTime();
        RuntimeComponentStatus status = statuses.findByComponentCode(code)
                .orElseGet(() -> new RuntimeComponentStatus(code));
        if (!component.enabled(settings)) {
            status.disabled(checkedAt);
        } else {
            ComponentCheckResult result;
            try {
                result = component.check();
            } catch (RuntimeException exception) {
                result = ComponentCheckResult.error(exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage());
            }
            long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
            if (result.up()) status.up(checkedAt, latency, sanitize(result.message()));
            else status.error(checkedAt, latency, sanitize(result.message()));
        }
        RuntimeComponentStatus saved = statuses.save(status);
        RuntimeComponentResponse response = RuntimeComponentResponse.from(saved, component.displayName());
        publishAfterCommit(response);
        return response;
    }

    public List<RuntimeComponentResponse> refreshAll() {
        List<RuntimeComponentResponse> refreshed = components.keySet().stream().map(this::refresh).toList();
        refreshedAt = Instant.now();
        return refreshed;
    }

    public void reportFailure(String code, String message) {
        RuntimeComponent component = components.get(code);
        if (component == null) return;
        RuntimeComponentStatus status = statuses.findByComponentCode(code)
                .orElseGet(() -> new RuntimeComponentStatus(code));
        status.error(Instant.now(), 0, sanitize(message));
        RuntimeComponentResponse response = RuntimeComponentResponse.from(statuses.save(status),
                component.displayName());
        publishAfterCommit(response);
    }

    public boolean isUp(String code) {
        RuntimeComponentResponse current = snapshot.get(code);
        return current != null && RuntimeComponentState.UP.name().equals(current.status());
    }

    public Instant refreshedAt() { return refreshedAt; }

    private String sanitize(String message) {
        if (message == null || message.isBlank()) return "no details";
        String value = message.replaceAll("[\\p{Cntrl}]", " ");
        return value.length() <= 500 ? value : value.substring(0, 499) + "…";
    }

    private void publishAfterCommit(RuntimeComponentResponse response) {
        Runnable publish = () -> {
            Map<String, RuntimeComponentResponse> replacement = new LinkedHashMap<>(snapshot);
            replacement.put(response.code(), response);
            snapshot = Map.copyOf(replacement);
            refreshedAt = Instant.now();
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { publish.run(); }
        });
    }
}
