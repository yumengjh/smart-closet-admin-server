package com.yumg.starter.modules.runtimeconfig.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.RuntimeSetting;
import com.yumg.starter.modules.runtimeconfig.api.dto.RuntimeSettingResponse;
import com.yumg.starter.modules.runtimeconfig.infrastructure.RuntimeSettingRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Autowired;
import com.yumg.starter.modules.security.application.AuditService;

@Service
public class RuntimeSettingService implements RuntimeCacheProvider {
    public static final String CACHE_CODE = "RUNTIME_SETTINGS";
    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(
            Map.entry("security.rate-limit.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用全局限流", "限制单个客户端在时间窗口内的总请求数。")),
            Map.entry("security.rate-limit.capacity", new Definition("INTEGER", "120", 1, 10000, "全局请求上限", "每个客户端在全局限流窗口内最多可发起的请求次数。")),
            Map.entry("security.rate-limit.window-seconds", new Definition("INTEGER", "60", 1, 3600, "全局限流窗口", "全局限流计数的时间窗口，单位：秒。")),
            Map.entry("security.endpoint.rate-limit.patterns", new Definition("STRING", "", 0, 0, "端点限流路径", "需要使用独立限流策略的路径模式，多个模式请用逗号分隔。")),
            Map.entry("security.endpoint.rate-limit.capacity", new Definition("INTEGER", "20", 1, 10000, "端点请求上限", "每个客户端在端点限流窗口内最多可发起的请求次数。")),
            Map.entry("security.endpoint.rate-limit.window-seconds", new Definition("INTEGER", "60", 1, 3600, "端点限流窗口", "端点限流计数的时间窗口，单位：秒。")),
            Map.entry("security.brute-force.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用登录失败保护", "连续登录失败达到阈值后暂时锁定该账号。")),
            Map.entry("security.brute-force.failure-threshold", new Definition("INTEGER", "5", 3, 20, "失败次数阈值", "在统计窗口内达到该次数后开始锁定账号。")),
            Map.entry("security.brute-force.window-seconds", new Definition("INTEGER", "900", 60, 86400, "失败统计窗口", "统计登录失败次数的时间范围，单位：秒。")),
            Map.entry("security.brute-force.lock-seconds", new Definition("INTEGER", "900", 60, 86400, "账号锁定时长", "登录失败保护触发后账号不可登录的时长，单位：秒。")),
            Map.entry("security.request-log.enabled", new Definition("BOOLEAN", "true", 0, 0, "记录请求日志", "记录 API 请求的基础访问日志。")),
            Map.entry("security.audit.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用审计日志", "记录安全与管理操作的审计事件。")),
            Map.entry("security.cors.allowed-origins", new Definition("STRING", "", 0, 0, "允许的跨域来源", "允许访问 API 的来源地址，多个来源请用逗号分隔。")),
            Map.entry("security.cors.allowed-methods", new Definition("STRING", "GET,POST,PUT,PATCH,DELETE,OPTIONS", 0, 0, "允许的跨域方法", "跨域请求允许使用的 HTTP 方法，多个方法请用逗号分隔。")),
            Map.entry("security.endpoint.disabled-patterns", new Definition("STRING", "", 0, 0, "禁用的端点路径", "临时禁止访问的 API 路径模式，多个模式请用逗号分隔。")),
            Map.entry("security.ip.allow-list", new Definition("STRING", "", 0, 0, "IP 白名单", "仅允许这些 IP 或 CIDR 网段访问；留空表示不限制。")),
            Map.entry("security.ip.deny-list", new Definition("STRING", "", 0, 0, "IP 黑名单", "拒绝这些 IP 或 CIDR 网段访问，多个规则请用逗号分隔。")),
            Map.entry("identity.registration.enabled", new Definition("BOOLEAN", "true", 0, 0, "开放自助注册", "控制访客是否可以自行注册新账号。")),
            Map.entry("runtime.config-refresh.enabled", new Definition("BOOLEAN", "true", 0, 0, "自动刷新配置缓存", "定期比较数据库 revision，并只重载发生变化的配置缓存。")),
            Map.entry("runtime.config-refresh.interval-seconds", new Definition("INTEGER", "60", 10, 3600, "配置缓存刷新间隔", "自动检查配置缓存数据库 revision 的间隔，单位：秒。")),
            Map.entry("runtime.dependency-health.enabled", new Definition("BOOLEAN", "true", 0, 0, "自动探测服务依赖", "定期检查头像生成服务和本地存储等后端依赖。")),
            Map.entry("runtime.dependency-health.interval-seconds", new Definition("INTEGER", "60", 10, 3600, "依赖探测间隔", "自动刷新服务依赖健康状态的间隔，单位：秒。")),
            Map.entry("avatar.generator.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用系统头像生成", "允许调用自托管 DiceBear 生成新的系统头像。")),
            Map.entry("maintenance.gc.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用数据清理中心", "允许自动和手动执行已登记的数据清理任务。")),
            Map.entry("maintenance.gc.schedule.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用定时数据清理", "关闭后不再自动执行清理任务，但仍可在清理中心手动执行。")),
            Map.entry("maintenance.gc.interval-minutes", new Definition("INTEGER", "60", 1, 1440, "GC 自动执行间隔", "自动数据清理任务的最小执行间隔，单位：分钟。")),
            Map.entry("maintenance.gc.max-run-seconds", new Definition("INTEGER", "120", 10, 3600, "GC 最大执行时长", "单次数据清理任务持有执行锁的最长时间，单位：秒。")));
    private final RuntimeSettingRepository settings;
    private volatile Map<String, String> snapshot = Map.of();
    private volatile long snapshotRevision;
    private volatile long databaseRevision;
    private volatile Instant loadedAt;
    private volatile Instant lastCheckedAt;
    private volatile Instant lastReloadedAt;
    private volatile Instant lastFailedAt;
    private volatile String lastFailureMessage;
    private final AuditService audit;
    private final RuntimeCacheRevisionService revisions;
    private final String initialCorsAllowedOrigins;
    private long localRevision;

    public RuntimeSettingService(RuntimeSettingRepository settings) { this(settings, null, "", null); }
    @Autowired
    public RuntimeSettingService(RuntimeSettingRepository settings, AuditService audit,
                                 @Value("${app.security.cors-allowed-origins:}")
                                 String initialCorsAllowedOrigins,
                                 RuntimeCacheRevisionService revisions) {
        this.settings = settings;
        this.audit = audit;
        this.initialCorsAllowedOrigins = initialCorsAllowedOrigins;
        this.revisions = revisions;
    }

    @PostConstruct
    @Transactional
    void initialize() {
        Map<String, String> initialized = new LinkedHashMap<>();
        DEFINITIONS.forEach((key, definition) -> settings.findByKey(key).ifPresentOrElse(setting ->
                initialized.put(key, setting.getValue()), () -> {
                    String initialValue = "security.cors.allowed-origins".equals(key)
                            ? initialCorsAllowedOrigins : definition.defaultValue();
                    RuntimeSetting created = settings.save(new RuntimeSetting(key, definition.type(), initialValue));
                    initialized.put(key, created.getValue());
                }));
        long revision = ensureRevision();
        publish(immutable(initialized), revision);
    }

    @Transactional(readOnly = true)
    public List<RuntimeSettingResponse> list() {
        return settings.findAll().stream().map(setting -> {
            Definition definition = DEFINITIONS.get(setting.getKey());
            return RuntimeSettingResponse.from(setting,
                    definition == null ? setting.getKey() : definition.displayName(),
                    definition == null ? setting.getKey() : definition.description());
        }).toList();
    }

    @Transactional
    public RuntimeSettingResponse update(String key, String value) {
        Definition definition = DEFINITIONS.get(key);
        if (definition == null || !valid(definition, value)) throw ApiException.notFound();
        RuntimeSetting setting = settings.findByKey(key).orElseThrow(ApiException::notFound);
        boolean auditWasEnabled = audit == null || enabled("security.audit.enabled");
        setting.changeValue(value);
        long revision = incrementRevision();
        afterCommit(() -> replaceSnapshot(Map.of(key, value), revision));
        if (audit != null && (auditWasEnabled || "security.audit.enabled".equals(key))) audit.runtimeSettingChanged(key, value);
        return RuntimeSettingResponse.from(setting, definition.displayName(), definition.description());
    }

    @Transactional
    public void updateAll(Map<String, String> updates) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        updates.forEach((key, value) -> {
            Definition definition = DEFINITIONS.get(key);
            if (definition == null || !valid(definition, value)) throw ApiException.notFound();
            definitions.put(key, definition);
        });
        Map<String, RuntimeSetting> changed = new LinkedHashMap<>();
        definitions.forEach((key, definition) -> {
            RuntimeSetting setting = settings.findByKey(key).orElseThrow(ApiException::notFound);
            setting.changeValue(updates.get(key));
            changed.put(key, setting);
        });
        boolean auditWasEnabled = audit == null || enabled("security.audit.enabled");
        long revision = incrementRevision();
        afterCommit(() -> replaceSnapshot(updates, revision));
        if (audit != null) {
            changed.forEach((key, setting) -> {
                if (auditWasEnabled || "security.audit.enabled".equals(key)) {
                    audit.runtimeSettingChanged(key, setting.getValue());
                }
            });
        }
    }

    @Transactional(readOnly = true)
    public Map<String, String> reload() {
        forceRefresh();
        return snapshot;
    }

    @Override
    @Transactional(readOnly = true)
    public synchronized RuntimeCacheStatus forceRefresh() {
        Instant checkedAt = Instant.now();
        lastCheckedAt = checkedAt;
        try {
            Map<String, String> replacement = loadValues();
            long revision = currentRevision();
            publish(replacement, revision);
            return status();
        } catch (RuntimeException exception) {
            lastFailedAt = checkedAt;
            lastFailureMessage = failureMessage(exception);
            throw exception;
        }
    }

    private Map<String, String> loadValues() {
        Map<String, String> reloaded = new LinkedHashMap<>();
        DEFINITIONS.forEach((key, definition) -> reloaded.put(key,
                "security.cors.allowed-origins".equals(key)
                        ? initialCorsAllowedOrigins : definition.defaultValue()));
        settings.findAll().forEach(setting -> {
            Definition definition = DEFINITIONS.get(setting.getKey());
            if (definition != null) {
                if (!valid(definition, setting.getValue())) {
                    throw new IllegalStateException("Invalid runtime setting: " + setting.getKey());
                }
                reloaded.put(setting.getKey(), setting.getValue());
            }
        });
        return immutable(reloaded);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean refreshIfChanged() {
        lastCheckedAt = Instant.now();
        long current = currentRevision();
        databaseRevision = current;
        if (current == snapshotRevision) return false;
        forceRefresh();
        return true;
    }

    @Override public String code() { return CACHE_CODE; }
    @Override public String displayName() { return "运行时设置"; }
    @Override public int order() { return 0; }
    @Override public RuntimeCacheStatus status() {
        return new RuntimeCacheStatus(code(), displayName(), snapshotRevision, databaseRevision,
                snapshotRevision == databaseRevision, loadedAt, lastCheckedAt, lastReloadedAt,
                lastFailedAt, lastFailureMessage, snapshot.size());
    }

    public boolean enabled(String key) { return Boolean.parseBoolean(required(key)); }
    public int integer(String key) { return Integer.parseInt(required(key)); }
    public String string(String key) { return required(key); }

    private boolean valid(Definition definition, String value) {
        if ("STRING".equals(definition.type())) return value != null && value.length() <= 1000;
        if ("BOOLEAN".equals(definition.type())) return "true".equals(value) || "false".equals(value);
        try { int number = Integer.parseInt(value); return number >= definition.min() && number <= definition.max(); }
        catch (NumberFormatException ignored) { return false; }
    }
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    private String required(String key) {
        String value = snapshot.get(key);
        if (value == null) throw new IllegalStateException("Runtime setting is not initialized: " + key);
        return value;
    }
    private synchronized void replaceSnapshot(Map<String, String> updates, long revision) {
        if (revision < snapshotRevision) return;
        Map<String, String> replacement = new LinkedHashMap<>(snapshot);
        replacement.putAll(updates);
        publish(immutable(replacement), revision);
    }
    private void publish(Map<String, String> values, long revision) {
        Instant now = Instant.now();
        snapshot = values;
        snapshotRevision = revision;
        databaseRevision = revision;
        loadedAt = loadedAt == null ? now : loadedAt;
        lastReloadedAt = now;
        lastFailureMessage = null;
    }
    private long ensureRevision() {
        return revisions == null ? localRevision : revisions.ensure(CACHE_CODE);
    }
    private long currentRevision() {
        return revisions == null ? localRevision : revisions.current(CACHE_CODE);
    }
    private long incrementRevision() {
        return revisions == null ? ++localRevision : revisions.increment(CACHE_CODE);
    }
    private String failureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
    private Map<String, String> immutable(Map<String, String> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    private record Definition(String type, String defaultValue, int min, int max,
                              String displayName, String description) {}
}
