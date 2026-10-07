package com.yumg.starter.modules.integrations.closet.infrastructure;

import java.net.URI;
import java.time.Duration;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.integrations.closet")
public class ClosetIntegrationProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:8080";
    private String keyId = "primary";
    private String secret = "";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(10);

    @PostConstruct
    public void validate() {
        if (!enabled) return;
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("鱼搭集成已启用，但 APP_CLOSET_BASE_URL 不是有效 URL。", exception);
        }
        if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalStateException("鱼搭集成已启用时，APP_CLOSET_BASE_URL 必须是 http 或 https URL。");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("APP_CLOSET_BASE_URL 不得包含用户凭据、query 或 fragment。");
        }
        if (keyId == null || keyId.isBlank()) throw new IllegalStateException("鱼搭集成已启用时，APP_CLOSET_KEY_ID 不能为空。");
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("鱼搭集成已启用时，APP_CLOSET_SECRET 至少需要 32 个 UTF-8 字节。");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalStateException("鱼搭集成的连接与读取超时必须大于 0。");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
