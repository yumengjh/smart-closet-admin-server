package com.yumg.starter.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class StartupSummaryLogger {
    private static final Logger log = LoggerFactory.getLogger(StartupSummaryLogger.class);
    private static final int CONTENT_WIDTH = 64;
    private static final String HORIZONTAL = "─".repeat(CONTENT_WIDTH + 2);

    private final Environment environment;
    private final BuildProperties buildProperties;
    private final ResponseIdentityProperties responseIdentity;

    public StartupSummaryLogger(Environment environment,
                                Optional<BuildProperties> buildProperties,
                                ResponseIdentityProperties responseIdentity) {
        this.environment = environment;
        this.buildProperties = buildProperties.orElse(null);
        this.responseIdentity = responseIdentity;
    }

    @EventListener
    void logReady(ApplicationReadyEvent event) {
        int port = event.getApplicationContext() instanceof WebServerApplicationContext context
                ? context.getWebServer().getPort()
                : environment.getProperty("server.port", Integer.class, 8080);
        String[] activeProfiles = environment.getActiveProfiles();
        String profiles = String.join(", ", activeProfiles.length == 0
                ? environment.getDefaultProfiles()
                : activeProfiles);
        String applicationName = environment.getProperty(
                "spring.application.name", "spring-boot-starter-template");
        String version = buildProperties == null ? "development" : buildProperties.getVersion();
        String database = environment.matchesProfiles("postgres") ? "PostgreSQL" : "SQLite";
        boolean openApiEnabled = environment.getProperty(
                "springdoc.api-docs.enabled", Boolean.class, false);

        log.info("\n{}", renderSummary(applicationName, version, profiles, port, database,
                openApiEnabled, responseIdentity.poweredBy(), responseIdentity.server(),
                event.getTimeTaken()));
    }

    static String renderSummary(String applicationName, String version, String profiles, int port,
                                String database, boolean openApiEnabled, String poweredBy,
                                String server, Duration timeTaken) {
        String baseUrl = "http://localhost:" + port;
        String openApi = openApiEnabled ? baseUrl + "/v3/api-docs" : "disabled";
        Duration duration = timeTaken == null ? Duration.ZERO : timeTaken;
        String elapsed = String.format(Locale.ROOT, "%.3fs", duration.toMillis() / 1000.0);

        return String.join(System.lineSeparator(),
                "┌" + HORIZONTAL + "┐",
                line("✓ " + applicationName + " is ready"),
                "├" + HORIZONTAL + "┤",
                line("Version     : " + value(version)),
                line("Profiles    : " + value(profiles)),
                line("HTTP        : " + baseUrl),
                line("Health      : " + baseUrl + "/actuator/health"),
                line("OpenAPI     : " + openApi),
                line("Database    : " + value(database)),
                line("Powered-By  : " + value(poweredBy)),
                line("Server      : " + value(server)),
                line("Started in  : " + elapsed),
                "└" + HORIZONTAL + "┘");
    }

    private static String line(String content) {
        String sanitized = sanitize(content);
        String fitted = sanitized.length() <= CONTENT_WIDTH
                ? sanitized
                : sanitized.substring(0, CONTENT_WIDTH - 1) + "…";
        return "│ " + fitted + " ".repeat(CONTENT_WIDTH - fitted.length()) + " │";
    }

    private static String sanitize(String value) {
        StringBuilder sanitized = new StringBuilder(value.length());
        value.chars().forEach(character -> sanitized.append(
                Character.isISOControl(character) ? ' ' : (char) character));
        return sanitized.toString();
    }

    private static String value(String candidate) {
        return candidate == null || candidate.isBlank() ? "unknown" : candidate;
    }
}
