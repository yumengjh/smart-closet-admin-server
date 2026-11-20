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

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetPresetService {
    public static final String PRESETS = "/presets";
    public static final String COVERS = "/onboarding/style-covers";
    private final ClosetManagementClient client;
    private final AuditService audit;
    public ClosetPresetService(ClosetManagementClient client, AuditService audit) {
        this.client = client;
        this.audit = audit;
    }

    public JsonNode list(String path, int page, int size, Map<String,String> filters) {
        if (page < 0 || page >= Integer.MAX_VALUE || size < 1 || size > 100) throw ApiException.invalidParameter();
        var query = new LinkedHashMap<String,List<String>>();
        query.put("page", List.of(Integer.toString(page + 1)));
        query.put("size", List.of(Integer.toString(size)));
        filters.forEach((name,value) -> { if (value != null && !value.isBlank()) query.put(name, List.of(value)); });
        return client.get(path, query);
    }

    public JsonNode read(String path) { return client.get(path, Map.of()); }
    public byte[] image(String path) { return client.getBytes(path, Map.of()); }

    public JsonNode post(String path, Object body, String event, String target, String detail) {
        return write(event, target, detail, () -> client.post(path, body));
    }

    public JsonNode patch(String path, Object body, String target) {
        return write("PRESET_EDIT", target, null, () -> client.patch(path, body));
    }

    public void delete(String path, String event, String target) {
        write(event, target, null, () -> { client.delete(path); return null; });
    }

    public JsonNode upload(String path, String key, byte[] bytes, MediaType type) {
        return write("ONBOARDING_COVER_UPLOAD", key, null, () -> client.putRaw(path, bytes, type));
    }

    private <T> T write(String event, String target, String detail, Supplier<T> operation) {
        try {
            T result = operation.get();
            audit.event("CLOSET_" + event, "ClosetPreset", target, "SUCCESS", detail);
            return result;
        } catch (ApiException e) {
            audit.event("CLOSET_" + event, "ClosetPreset", target, "FAILURE", e.error().name());
            throw e;
        }
    }
}
