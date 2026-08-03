package com.yumg.starter.modules.users.application;

import com.yumg.starter.modules.runtimeconfig.application.ComponentCheckResult;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeComponent;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeCheckKind;
import com.yumg.starter.modules.runtimeconfig.application.RuntimeSettingService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DiceBearAvatarGenerator implements RuntimeComponent {
    public static final String CODE = "AVATAR_GENERATOR";
    private final RestClient client;
    private final int maxResponseBytes;

    @Autowired
    public DiceBearAvatarGenerator(
            @Value("${app.avatar.generator.base-url:${APP_AVATAR_GENERATOR_BASE_URL:http://localhost:3100}}") URI baseUrl,
            @Value("${app.avatar.generator.connect-timeout:500ms}") Duration connectTimeout,
            @Value("${app.avatar.generator.read-timeout:1500ms}") Duration readTimeout,
            @Value("${app.avatar.generator.max-response-bytes:262144}") int maxResponseBytes) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl.toString())
                .requestFactory(requestFactory).build();
        this.maxResponseBytes = maxResponseBytes;
    }

    public byte[] generate(String seed, int size) {
        if (seed == null || seed.isBlank() || seed.length() > 128) {
            throw new IllegalArgumentException("invalid avatar seed");
        }
        if (size < 32 || size > 512) throw new IllegalArgumentException("invalid avatar size");
        byte[] svg = client.get().uri(builder -> builder.path("/10.x/lorelei/svg")
                .queryParam("seed", seed).queryParam("size", size).build()).exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("DiceBear returned " + response.getStatusCode().value());
                    }
                    MediaType contentType = response.getHeaders().getContentType();
                    if (contentType == null || !MediaType.valueOf("image/svg+xml").isCompatibleWith(contentType)) {
                        throw new IllegalStateException("DiceBear returned an invalid content type");
                    }
                    try {
                        byte[] content = response.getBody().readNBytes(maxResponseBytes + 1);
                        if (content.length > maxResponseBytes) {
                            throw new IllegalStateException("DiceBear response is too large");
                        }
                        return content;
                    } catch (IOException exception) {
                        throw new IllegalStateException("Unable to read DiceBear response", exception);
                    }
                });
        validateSvg(svg);
        return svg;
    }

    private void validateSvg(byte[] svg) {
        String text = new String(svg, StandardCharsets.UTF_8).strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("<svg")
                || lower.contains("<script")
                || lower.matches("(?s).*\\son[a-z]+\\s*=.*")
                || lower.contains("javascript:")
                || lower.matches("(?s).*\\b(?:href|src)\\s*=\\s*[\"'](?!#)[^\"']*.*")
                || lower.matches("(?s).*url\\s*\\(\\s*[\"']?(?:https?:|//).*")
                || lower.contains("<foreignobject")) {
            throw new IllegalStateException("DiceBear returned unsafe SVG content");
        }
    }

    @Override public String code() { return CODE; }
    @Override public String displayName() { return "DiceBear 头像生成器"; }
    @Override public RuntimeCheckKind kind() { return RuntimeCheckKind.SERVICE_DEPENDENCY; }
    @Override public ComponentCheckResult check() {
        generate("springboot-start-health-check", 64);
        return ComponentCheckResult.up("Lorelei 接口响应和 SVG 安全校验正常");
    }
    @Override public boolean enabled(RuntimeSettingService settings) {
        return settings.enabled("avatar.generator.enabled");
    }
}
