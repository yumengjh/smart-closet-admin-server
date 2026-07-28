package com.yumg.starter.config;

import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSafetyValidator implements ApplicationRunner {
    private final Environment environment;
    private final RuntimeSettingService settings;
    private final boolean secureRefreshCookie;

    public ProductionSafetyValidator(Environment environment, RuntimeSettingService settings,
                                     @Value("${app.auth.refresh-cookie-secure:false}")
                                     boolean secureRefreshCookie) {
        this.environment = environment;
        this.settings = settings;
        this.secureRefreshCookie = secureRefreshCookie;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!environment.matchesProfiles("postgres")) {
            return;
        }
        if (!secureRefreshCookie) {
            throw new IllegalStateException(
                    "PostgreSQL deployments require app.auth.refresh-cookie-secure=true");
        }
        boolean allowsEveryOrigin = Arrays.stream(
                        settings.string("security.cors.allowed-origins").split(","))
                .map(String::trim)
                .anyMatch("*"::equals);
        if (allowsEveryOrigin) {
            throw new IllegalStateException(
                    "PostgreSQL deployments must configure explicit CORS origins");
        }
    }
}
