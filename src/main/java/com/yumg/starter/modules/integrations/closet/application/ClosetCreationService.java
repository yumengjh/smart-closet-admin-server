package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetCreationService {
    private final ClosetManagementClient client;
    private final AuditService audit;
    public ClosetCreationService(ClosetManagementClient client, AuditService audit) { this.client = client; this.audit = audit; }
    public JsonNode read(String path, Map<String, List<String>> query) { return client.get("/creations" + path, query); }
    public JsonNode write(String action, String method, String path, JsonNode body) {
        try {
            JsonNode result = method.equals("POST") ? client.post("/creations" + path, body) : client.put("/creations" + path, body);
            audit.event("CLOSET_CREATION_" + action, "ClosetCreation", path, "SUCCESS", null);
            return result;
        } catch (ApiException exception) {
            audit.event("CLOSET_CREATION_" + action, "ClosetCreation", path, "FAILURE", exception.error().name());
            throw exception;
        }
    }

    public JsonNode upload(String action, String path, byte[] bytes, org.springframework.http.MediaType type) {
        return audited(action, path, () -> client.postRaw("/creations" + path, bytes, type));
    }
    public JsonNode delete(String action, String path) {
        return audited(action, path, () -> client.deleteWithResponse("/creations" + path));
    }
    public byte[] image(String path) { return client.getBytes("/creations" + path, Map.of()); }
    private <T> T audited(String action, String path, java.util.function.Supplier<T> operation) {
        try {
            T result = operation.get();
            audit.event("CLOSET_CREATION_" + action, "ClosetCreation", path, "SUCCESS", null);
            return result;
        } catch (ApiException exception) {
            audit.event("CLOSET_CREATION_" + action, "ClosetCreation", path, "FAILURE", exception.error().name());
            throw exception;
        }
    }
}
