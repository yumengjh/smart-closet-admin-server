package com.yumg.starter.modules.integrations.closet.infrastructure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class ClosetRequestSigner {
    private static final char[] NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toCharArray();
    private final SecureRandom random;
    public ClosetRequestSigner() { this(new SecureRandom()); }
    ClosetRequestSigner(SecureRandom random) { this.random = random; }

    public String canonical(String method, String rawPath, Map<String, ? extends List<String>> query,
                            long timestamp, String nonce, String operator, String requestId, byte[] body) {
        String bodyHash;
        try { bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        return String.join("\n", "CLOSET-HMAC-SHA256", method.toUpperCase(java.util.Locale.ROOT), rawPath,
                canonicalQuery(query), Long.toString(timestamp), nonce, nullToEmpty(operator), nullToEmpty(requestId), bodyHash);
    }

    public String sign(String canonical, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Unable to sign closet request", exception); }
    }

    public HttpHeaders headers(URI uri, String method, Map<String, ? extends List<String>> query,
                              byte[] body, String keyId, String secret, String operator, String requestId) {
        long timestamp = System.currentTimeMillis();
        String nonce = nonce();
        String canonical = canonical(method, uri.getRawPath(), query, timestamp, nonce, operator, requestId, body);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Closet-Key-Id", keyId);
        headers.set("X-Closet-Timestamp", Long.toString(timestamp));
        headers.set("X-Closet-Nonce", nonce);
        headers.set("X-Closet-Signature", sign(canonical, secret));
        if (operator != null && !operator.isBlank()) headers.set("X-Operator", operator);
        if (requestId != null && !requestId.isBlank()) headers.set("X-Request-Id", requestId);
        return headers;
    }

    public static String canonicalQuery(Map<String, ? extends List<String>> query) {
        if (query == null || query.isEmpty()) return "";
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        query.forEach((name, values) -> (values == null || values.isEmpty() ? List.of("") : values)
                .forEach(value -> pairs.add(Map.entry(encodeComponent(name), encodeComponent(value == null ? "" : value)))));
        // Sort the encoded forms, exactly as the closet verifier does.
        return pairs.stream().sorted(Comparator.comparing((Map.Entry<String, String> pair) -> pair.getKey())
                        .thenComparing(Map.Entry::getValue))
                .map(pair -> pair.getKey() + "=" + pair.getValue())
                .collect(java.util.stream.Collectors.joining("&"));
    }

    public static String encodeComponent(String value) {
        StringBuilder out = new StringBuilder();
        for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
            int b = valueByte & 0xff;
            if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == '-' || b == '.' || b == '_' || b == '~') out.append((char) b);
            else out.append('%').append("0123456789ABCDEF".charAt(b >>> 4)).append("0123456789ABCDEF".charAt(b & 15));
        }
        return out.toString();
    }
    private String nonce() {
        char[] chars = new char[32];
        for (int i = 0; i < chars.length; i++) chars[i] = NONCE_CHARS[random.nextInt(NONCE_CHARS.length)];
        return new String(chars);
    }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
