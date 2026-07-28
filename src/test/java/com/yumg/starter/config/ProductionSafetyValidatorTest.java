package com.yumg.starter.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.ApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyValidatorTest {
    @Test
    void rejectsInsecureRefreshCookiesInThePostgresProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("postgres");
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        when(settings.string("security.cors.allowed-origins"))
                .thenReturn("https://admin.example.com");

        ProductionSafetyValidator validator =
                new ProductionSafetyValidator(environment, settings, false);

        assertThatThrownBy(() -> validator.run(Mockito.mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-cookie-secure");
    }

    @Test
    void rejectsWildcardCredentialedCorsInThePostgresProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("postgres");
        RuntimeSettingService settings = Mockito.mock(RuntimeSettingService.class);
        when(settings.string("security.cors.allowed-origins")).thenReturn("*");

        ProductionSafetyValidator validator =
                new ProductionSafetyValidator(environment, settings, true);

        assertThatThrownBy(() -> validator.run(Mockito.mock(ApplicationArguments.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit CORS origins");
    }
}
