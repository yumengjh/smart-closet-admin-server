package com.yumg.starter.modules.runtimeconfig.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.entities.RuntimeSetting;
import com.yumg.starter.modules.runtimeconfig.api.dto.RuntimeSettingResponse;
import com.yumg.starter.modules.runtimeconfig.infrastructure.RuntimeSettingRepository;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Autowired;
import com.yumg.starter.modules.security.application.AuditService;

@Service
public class RuntimeSettingService {
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
            Map.entry("maintenance.gc.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用数据清理中心", "允许自动和手动执行已登记的数据清理任务。")),
            Map.entry("maintenance.gc.schedule.enabled", new Definition("BOOLEAN", "true", 0, 0, "启用定时数据清理", "关闭后不再自动执行清理任务，但仍可在清理中心手动执行。")),
            Map.entry("maintenance.gc.interval-minutes", new Definition("INTEGER", "60", 1, 1440, "GC 自动执行间隔", "自动数据清理任务的最小执行间隔，单位：分钟。")),
            Map.entry("maintenance.gc.max-run-seconds", new Definition("INTEGER", "120", 10, 3600, "GC 最大执行时长", "单次数据清理任务持有执行锁的最长时间，单位：秒。")));
    private final RuntimeSettingRepository settings;
    private final ConcurrentHashMap<String, String> snapshot = new ConcurrentHashMap<>();
    private final AuditService audit;
    private final String initialCorsAllowedOrigins;

    public RuntimeSettingService(RuntimeSettingRepository settings) { this(settings, null, ""); }
    @Autowired
    public RuntimeSettingService(RuntimeSettingRepository settings, AuditService audit,
                                 @Value("${app.security.cors-allowed-origins:}")
                                 String initialCorsAllowedOrigins) {
        this.settings = settings;
        this.audit = audit;
        this.initialCorsAllowedOrigins = initialCorsAllowedOrigins;
    }

    @PostConstruct
    @Transactional
    void initialize() {
        DEFINITIONS.forEach((key, definition) -> settings.findByKey(key).ifPresentOrElse(setting ->
                snapshot.put(key, setting.getValue()), () -> {
                    String initialValue = "security.cors.allowed-origins".equals(key)
                            ? initialCorsAllowedOrigins : definition.defaultValue();
                    RuntimeSetting created = settings.save(new RuntimeSetting(key, definition.type(), initialValue));
                    snapshot.put(key, created.getValue());
                }));
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
        boolean auditWasEnabled = enabled("security.audit.enabled");
        setting.changeValue(value);
        afterCommit(() -> snapshot.put(key, value));
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
        boolean auditWasEnabled = enabled("security.audit.enabled");
        afterCommit(() -> snapshot.putAll(updates));
        if (audit != null) {
            changed.forEach((key, setting) -> {
                if (auditWasEnabled || "security.audit.enabled".equals(key)) {
                    audit.runtimeSettingChanged(key, setting.getValue());
                }
            });
        }
    }

    public boolean enabled(String key) { return Boolean.parseBoolean(snapshot.get(key)); }
    public int integer(String key) { return Integer.parseInt(snapshot.get(key)); }
    public String string(String key) { return snapshot.get(key); }

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
    private record Definition(String type, String defaultValue, int min, int max,
                              String displayName, String description) {}
}
