package com.yumg.starter.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import com.yumg.starter.modules.runtimeconfig.infrastructure.RuntimeSettingRepository;
import java.lang.reflect.Proxy;

class SecurityConfigurationCorsTest {

    @Test
    void allowsPreflightForEveryMutatingApiMethodUsedByTheAdminConsole() {
        RuntimeSettingRepository repository = (RuntimeSettingRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {RuntimeSettingRepository.class},
                (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
        RuntimeSettingService settings = new RuntimeSettingService(repository) {
            @Override public String string(String key) {
                return "security.cors.allowed-origins".equals(key)
                        ? "https://admin.example.com"
                        : "GET,POST,PUT,PATCH,DELETE,OPTIONS";
            }
        };
        var source = new SecurityConfiguration().corsConfigurationSource(settings);
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/admin/users/user-id/status");

        var configuration = source.getCorsConfiguration(request);

        assertThat(configuration.getAllowedMethods())
                .containsExactlyInAnyOrderElementsOf(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        assertThat(configuration.getAllowedOriginPatterns())
                .containsExactly("https://admin.example.com");
        assertThat(configuration.getAllowCredentials()).isTrue();
    }
}
