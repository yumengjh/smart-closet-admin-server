package com.yumg.starter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.response")
public record ResponseIdentityProperties(String poweredBy, String server) {
    public static final String DEFAULT_POWERED_BY = "ASP.NET";
    public static final String DEFAULT_SERVER = "Microsoft-IIS/10.0";

    public ResponseIdentityProperties {
        poweredBy = normalize(
                poweredBy, DEFAULT_POWERED_BY, "app.response.powered-by");
        server = normalize(server, DEFAULT_SERVER, "app.response.server");
    }

    private static String normalize(String candidate, String defaultValue, String propertyName) {
        String value = candidate == null || candidate.isBlank()
                ? defaultValue
                : candidate.trim();
        if (value.length() > 128) {
            throw new IllegalArgumentException(
                    propertyName + " must not exceed 128 characters");
        }
        boolean containsInvalidCharacter = value.chars()
                .anyMatch(character -> character < 0x20 || character == 0x7f);
        if (containsInvalidCharacter) {
            throw new IllegalArgumentException(
                    propertyName + " must not contain control characters");
        }
        return value;
    }
}
