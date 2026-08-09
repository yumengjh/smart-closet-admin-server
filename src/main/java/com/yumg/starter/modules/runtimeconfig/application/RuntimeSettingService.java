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
import org.springframework.core.env.Environment;
import java.util.Set;

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
            Map.entry("security.control-plane.rate-limit.capacity", new Definition("INTEGER", "600", 10, 10000, "管理控制面请求上限", "运行时配置、缓存与 IP 规则恢复接口使用的独立每 IP 请求上限。")),
            Map.entry("security.control-plane.rate-limit.window-seconds", new Definition("INTEGER", "60", 1, 3600, "管理控制面限流窗口", "管理恢复接口独立限流窗口，单位：秒。")),
            Map.entry("security.ip-auto-ban.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用自动临时封禁", "同一客户端持续触发限流后自动创建有期限的 IP 拒绝规则。")),
            Map.entry("security.ip-auto-ban.rejection-threshold", new Definition("INTEGER", "10", 2, 1000, "自动封禁拒绝阈值", "在观察窗口内累计达到该 429 拒绝次数后触发临时封禁。")),
            Map.entry("security.ip-auto-ban.window-seconds", new Definition("INTEGER", "300", 10, 86400, "自动封禁观察窗口", "累计限流拒绝次数的时间窗口，单位：秒。")),
            Map.entry("security.ip-auto-ban.duration-seconds", new Definition("INTEGER", "900", 60, 604800, "自动封禁时长", "自动生成 IP 拒绝规则的有效时长，单位：秒。")),
            Map.entry("security.brute-force.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用登录失败保护", "连续登录失败达到阈值后暂时锁定该账号。")),
            Map.entry("security.brute-force.failure-threshold", new Definition("INTEGER", "5", 3, 20, "失败次数阈值", "在统计窗口内达到该次数后开始锁定账号。")),
            Map.entry("security.brute-force.window-seconds", new Definition("INTEGER", "900", 60, 86400, "失败统计窗口", "统计登录失败次数的时间范围，单位：秒。")),
            Map.entry("security.brute-force.lock-seconds", new Definition("INTEGER", "900", 60, 86400, "账号锁定时长", "登录失败保护触发后账号不可登录的时长，单位：秒。")),
            Map.entry("security.request-log.enabled", new Definition("BOOLEAN", "true", 0, 0, "记录请求日志", "记录 API 请求的基础访问日志。")),
            Map.entry("security.audit.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用审计日志", "记录安全与管理操作的审计事件。")),
            Map.entry("security.cors.allowed-origins", new Definition("STRING", "", 0, 0, "允许的跨域来源", "允许访问 API 的来源地址，多个来源请用逗号分隔。")),
            Map.entry("security.cors.allowed-methods", new Definition("STRING", "GET,POST,PUT,PATCH,DELETE,OPTIONS", 0, 0, "允许的跨域方法", "跨域请求允许使用的 HTTP 方法，多个方法请用逗号分隔。")),
            Map.entry("security.endpoint.disabled-patterns", new Definition("STRING", "", 0, 0, "禁用的端点路径", "临时禁止访问的 API 路径模式，多个模式请用逗号分隔。")),
            Map.entry("identity.registration.enabled", new Definition("BOOLEAN", "true", 0, 0, "开放自助注册", "控制访客是否可以自行注册新账号。")),
            Map.entry("runtime.config-refresh.enabled", new Definition("BOOLEAN", "true", 0, 0, "自动刷新配置缓存", "定期比较数据库 revision，并只重载发生变化的配置缓存。")),
            Map.entry("runtime.config-refresh.interval-seconds", new Definition("INTEGER", "60", 10, 3600, "配置缓存刷新间隔", "自动检查配置缓存数据库 revision 的间隔，单位：秒。")),
            Map.entry("runtime.health-check.enabled", new Definition("BOOLEAN", "true", 0, 0, "自动执行运行检查", "定期检查服务依赖和本地资源的运行状态。")),
            Map.entry("runtime.health-check.interval-seconds", new Definition("INTEGER", "60", 10, 3600, "运行检查间隔", "自动刷新运行状态检查的间隔，单位：秒。")),
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
    private final Environment environment;
    private long localRevision;

    public RuntimeSettingService(RuntimeSettingRepository settings) { this(settings, null, "", null, null); }
    @Autowired
    public RuntimeSettingService(RuntimeSettingRepository settings, AuditService audit,
                                 @Value("${app.security.cors-allowed-origins:}")
                                 String initialCorsAllowedOrigins,
                                 RuntimeCacheRevisionService revisions,
                                 Environment environment) {
        this.settings = settings;
        this.audit = audit;
        this.initialCorsAllowedOrigins = initialCorsAllowedOrigins;
        this.revisions = revisions;
        this.environment = environment;
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
        return settings.findAll().stream().filter(setting -> DEFINITIONS.containsKey(setting.getKey()))
                .sorted(java.util.Comparator.comparingInt((RuntimeSetting setting) -> groupOrder(group(setting.getKey())))
                        .thenComparing(RuntimeSetting::getKey))
                .map(setting -> {
            Definition definition = DEFINITIONS.get(setting.getKey());
            return RuntimeSettingResponse.from(setting,
                    definition.displayName(), definition.description(), group(setting.getKey()),
                    "INTEGER".equals(definition.type()) ? definition.min() : null,
                    "INTEGER".equals(definition.type()) ? definition.max() : null,
                    dangerous(setting.getKey()));
        }).toList();
    }

    @Transactional
    public RuntimeSettingResponse update(String key, String value) {
        Definition definition = DEFINITIONS.get(key);
        if (definition == null) throw ApiException.notFound();
        if (!valid(key, definition, value)) throw ApiException.invalidParameter();
        RuntimeSetting setting = settings.findByKey(key).orElseThrow(ApiException::notFound);
        boolean auditWasEnabled = audit == null || enabled("security.audit.enabled");
        setting.changeValue(value);
        long revision = incrementRevision();
        afterCommit(() -> replaceSnapshot(Map.of(key, value), revision));
        if (audit != null && (auditWasEnabled || "security.audit.enabled".equals(key))) audit.runtimeSettingChanged(key, value);
        return RuntimeSettingResponse.from(setting, definition.displayName(), definition.description(),
                group(key), "INTEGER".equals(definition.type()) ? definition.min() : null,
                "INTEGER".equals(definition.type()) ? definition.max() : null, dangerous(key));
    }

    @Transactional
    public void updateAll(Map<String, String> updates) {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        updates.forEach((key, value) -> {
            Definition definition = DEFINITIONS.get(key);
            if (definition == null) throw ApiException.notFound();
            if (!valid(key, definition, value)) throw ApiException.invalidParameter();
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
                if (!valid(setting.getKey(), definition, setting.getValue())) {
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

    private boolean valid(String key, Definition definition, String value) {
        if ("STRING".equals(definition.type())) return validString(key, value);
        if ("BOOLEAN".equals(definition.type())) return "true".equals(value) || "false".equals(value);
        try { int number = Integer.parseInt(value); return number >= definition.min() && number <= definition.max(); }
        catch (NumberFormatException ignored) { return false; }
    }
    private boolean validString(String key, String value) {
        if (value == null || value.length() > 1000) return false;
        if ("security.cors.allowed-methods".equals(key)) {
            Set<String> supported = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");
            return csv(value).stream().allMatch(supported::contains);
        }
        if ("security.cors.allowed-origins".equals(key)) {
            List<String> origins = csv(value);
            if (environment != null && environment.matchesProfiles("postgres")
                    && origins.contains("*")) return false;
            return origins.stream().allMatch(origin -> "*".equals(origin)
                    || origin.startsWith("http://") || origin.startsWith("https://"));
        }
        if ("security.endpoint.disabled-patterns".equals(key)
                || "security.endpoint.rate-limit.patterns".equals(key)) {
            return csv(value).stream().allMatch(pattern -> pattern.startsWith("/"));
        }
        return true;
    }
    private List<String> csv(String value) {
        return java.util.Arrays.stream(value.split(",")).map(String::trim)
                .filter(item -> !item.isEmpty()).toList();
    }
    private String group(String key) {
        if (key.startsWith("runtime.") || key.startsWith("avatar.")) return "runtime";
        if (key.startsWith("maintenance.")) return "maintenance";
        if (key.startsWith("identity.")) return "identity";
        if (key.startsWith("security.cors.")) return "cors";
        if (key.startsWith("security.endpoint.")) return "endpointPolicy";
        if (key.startsWith("security.control-plane.")) return "controlPlane";
        if (key.startsWith("security.ip-auto-ban.")) return "ipProtection";
        if (key.startsWith("security.rate-limit.")) return "rateLimit";
        return "security";
    }
    private int groupOrder(String group) {
        return switch (group) {
            case "runtime" -> 0; case "security" -> 10; case "rateLimit" -> 20;
            case "ipProtection" -> 30; case "controlPlane" -> 40; case "endpointPolicy" -> 50;
            case "cors" -> 60; case "identity" -> 70; case "maintenance" -> 80; default -> 100;
        };
    }
    private boolean dangerous(String key) {
        return key.equals("security.audit.enabled") || key.startsWith("security.cors.")
                || key.equals("security.endpoint.disabled-patterns")
                || key.equals("maintenance.gc.enabled");
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
