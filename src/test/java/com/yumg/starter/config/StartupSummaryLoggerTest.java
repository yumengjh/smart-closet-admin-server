package com.yumg.starter.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class StartupSummaryLoggerTest {

    @Test
    void rendersAReadableUnicodeSummaryWithoutSensitiveConfiguration() {
        String summary = StartupSummaryLogger.renderSummary(
                "spring-boot-starter-template",
                "1.2.3",
                "local",
                8848,
                "SQLite",
                true,
                "ASP.NET",
                "Microsoft-IIS/10.0",
                Duration.ofMillis(1234));

        assertThat(summary)
                .startsWith("┌")
                .contains("✓ spring-boot-starter-template is ready")
                .contains("Version     : 1.2.3")
                .contains("Profiles    : local")
                .contains("HTTP        : http://localhost:8848")
                .contains("Health      : http://localhost:8848/actuator/health")
                .contains("OpenAPI     : http://localhost:8848/v3/api-docs")
                .contains("Database    : SQLite")
                .contains("Powered-By  : ASP.NET")
                .contains("Server      : Microsoft-IIS/10.0")
                .contains("Started in  : 1.234s")
                .endsWith("┘")
                .doesNotContain("password", "private-key", "secret");
    }

    @Test
    void marksOpenApiAsDisabledForDeploymentProfiles() {
        String summary = StartupSummaryLogger.renderSummary(
                "starter", "0.0.1", "postgres", 8080, "PostgreSQL",
                false, "ASP.NET", "Microsoft-IIS/10.0", Duration.ZERO);

        assertThat(summary).contains("OpenAPI     : disabled");
    }
}
