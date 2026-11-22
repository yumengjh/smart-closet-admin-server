package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Proxies the live try-on content managed by Fish Closet: curated looks with their categories, and pose presets
 * with the sensitive-check switches. Image bytes only pass through memory; audits record action, target and
 * result, never request bodies, image bytes or upstream responses.
 */
@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetTryContentService {
    public static final String LOOKS = "/looks";
    public static final String CATEGORIES = "/looks/categories";
    public static final String POSES = "/poses";
    /** Same original-image ceiling as Fish Closet (20 MiB). */
    public static final int IMAGE_LIMIT = 20 * 1024 * 1024;
    public static final String LOOK = "ClosetLook";
    public static final String POSE = "ClosetPose";
    private final ClosetManagementClient client;
    private final AuditService audit;

    public ClosetTryContentService(ClosetManagementClient client, AuditService audit) {
        this.client = client;
        this.audit = audit;
    }

    /** Gateway pages are zero-based; the source pages are one-based. */
    public JsonNode page(String path, int page, int size, Map<String, String> filters) {
        if (page < 0 || page >= 1_000_000 || size < 1 || size > 100) throw ApiException.invalidParameter();
        var query = new LinkedHashMap<String, String>();
        query.put("page", Integer.toString(page + 1));
        query.put("size", Integer.toString(size));
        query.putAll(filters);
        return read(path, query);
    }

    public JsonNode read(String path, Map<String, String> filters) {
        var query = new LinkedHashMap<String, List<String>>();
        filters.forEach((name, value) -> { if (value != null && !value.isBlank()) query.put(name, List.of(value)); });
        return client.get(path, query);
    }

    public byte[] image(String path) { return client.getBytes(path, Map.of()); }

    /** A side-effect-free POST (text check preview); not audited. */
    public JsonNode query(String path, Object body) { return client.post(path, body); }

    public JsonNode post(String resource, String event, String target, String detail, String path, Object body) {
        return write(resource, event, target, detail, () -> client.post(path, body));
    }

    public JsonNode patch(String resource, String event, String target, String path, Object body) {
        return write(resource, event, target, null, () -> client.patch(path, body));
    }

    public JsonNode put(String resource, String event, String target, String detail, String path, Object body) {
        return write(resource, event, target, detail, () -> client.put(path, body));
    }

    public void delete(String resource, String event, String target, String path) {
        write(resource, event, target, null, () -> { client.delete(path); return null; });
    }

    public JsonNode putImage(String resource, String event, String target, String path, byte[] bytes, MediaType type) {
        return write(resource, event, target, null, () -> client.putRaw(path, bytes, type));
    }

    public JsonNode postImage(String resource, String event, String target, String path, byte[] bytes, MediaType type) {
        return write(resource, event, target, null, () -> client.postRaw(path, bytes, type));
    }

    private <T> T write(String resource, String event, String target, String detail, Supplier<T> operation) {
        try {
            T result = operation.get();
            audit.event("CLOSET_" + event, resource, target, "SUCCESS", detail);
            return result;
        } catch (ApiException e) {
            audit.event("CLOSET_" + event, resource, target, "FAILURE", e.error().name());
            throw e;
        }
    }
}
