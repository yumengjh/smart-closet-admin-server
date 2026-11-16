package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetShowcaseService {
    private static final String ROOT = "/creations/showcases";
    private final ClosetManagementClient client;
    private final AuditService audit;
    public ClosetShowcaseService(ClosetManagementClient client, AuditService audit) {
        this.client = client; this.audit = audit;
    }
    public JsonNode list(int page, int size) {
        return audited("READ", "list", () -> client.get(ROOT,
                Map.of("page", List.of(Integer.toString(page + 1)), "size", List.of(Integer.toString(size)))));
    }
    public JsonNode upload(byte[] bytes, MediaType type) {
        return audited("UPLOAD", "upload", () -> client.postRaw(ROOT + "/uploads", bytes, type));
    }
    public JsonNode write(String action, String path, Object body, boolean put) {
        return audited(action, path.isEmpty() ? "new" : path,
                () -> put ? client.put(ROOT + path, body) : client.post(ROOT + path, body));
    }
    public byte[] image(String id) {
        return audited("PREVIEW", id, () -> client.getBytes(ROOT + "/" + id + "/image", Map.of()));
    }
    private <T> T audited(String action, String target, Supplier<T> operation) {
        try {
            T result = operation.get();
            audit.event("CLOSET_SHOWCASE_" + action, "ClosetShowcase", target, "SUCCESS", null);
            return result;
        } catch (ApiException exception) {
            audit.event("CLOSET_SHOWCASE_" + action, "ClosetShowcase", target, "FAILURE", exception.error().name());
            throw exception;
        }
    }
}
