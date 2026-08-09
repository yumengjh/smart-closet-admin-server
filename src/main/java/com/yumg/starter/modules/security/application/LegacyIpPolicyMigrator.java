package com.yumg.starter.modules.security.application;

import com.yumg.starter.entities.IpAccessRule;
import com.yumg.starter.entities.RuntimeSetting;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCacheRevisionService;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import com.yumg.starter.modules.runtimeconfig.infrastructure.RuntimeSettingRepository;
import com.yumg.starter.modules.security.infrastructure.IpAccessRuleRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Order(20)
public class LegacyIpPolicyMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyIpPolicyMigrator.class);
    private static final String ALLOW_KEY = "security.ip.allow-list";
    private static final String DENY_KEY = "security.ip.deny-list";
    private final RuntimeSettingRepository settingsRepository;
    private final IpAccessRuleRepository ruleRepository;
    private final RuntimeCacheRevisionService revisions;
    private final RuntimeSettingService settings;
    private final IpAccessRuleService rules;
    private final TransactionTemplate transactions;

    public LegacyIpPolicyMigrator(RuntimeSettingRepository settingsRepository,
                                  IpAccessRuleRepository ruleRepository,
                                  RuntimeCacheRevisionService revisions,
                                  RuntimeSettingService settings, IpAccessRuleService rules,
                                  TransactionTemplate transactions) {
        this.settingsRepository = settingsRepository;
        this.ruleRepository = ruleRepository;
        this.revisions = revisions;
        this.settings = settings;
        this.rules = rules;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        Boolean changed = transactions.execute(status -> migrate());
        if (!Boolean.TRUE.equals(changed)) return;
        settings.forceRefresh();
        rules.forceRefresh();
        log.info("Migrated legacy runtime IP lists into structured IP access rules");
    }

    private boolean migrate() {
        List<RuntimeSetting> legacy = settingsRepository.findAll().stream()
                .filter(setting -> ALLOW_KEY.equals(setting.getKey()) || DENY_KEY.equals(setting.getKey()))
                .toList();
        if (legacy.isEmpty()) return false;
        List<IpAccessRule> existing = new ArrayList<>(ruleRepository.findAllByScope("API"));
        for (RuntimeSetting setting : legacy) {
            IpAccessRule.Type type = ALLOW_KEY.equals(setting.getKey())
                    ? IpAccessRule.Type.ALLOW : IpAccessRule.Type.DENY;
            Arrays.stream(setting.getValue().split(",")).map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(IpNetworkMatcher::normalize)
                    .forEach(normalized -> normalized.ifPresentOrElse(network -> {
                        boolean duplicate = existing.stream().anyMatch(rule -> rule.getType() == type
                                && rule.getNetwork().equals(network) && rule.activeAt(java.time.Instant.now()));
                        if (!duplicate) {
                            IpAccessRule saved = ruleRepository.save(new IpAccessRule(type,
                                    IpAccessRule.Source.MIGRATED, network, "API", null,
                                    "Migrated from " + setting.getKey()));
                            existing.add(saved);
                        }
                    }, () -> log.warn("Ignoring invalid legacy IP network in {}", setting.getKey())));
        }
        settingsRepository.deleteAll(legacy);
        revisions.increment(RuntimeSettingService.CACHE_CODE);
        revisions.increment(IpAccessRuleService.CACHE_CODE);
        return true;
    }
}
