package com.yumg.starter.modules.integrations.closet.application;

import com.yumg.starter.common.api.ApiException;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetManagementClient;
import com.yumg.starter.modules.integrations.closet.infrastructure.ClosetRequestSigner;
import com.yumg.starter.modules.security.application.AuditService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@ConditionalOnProperty(prefix = "app.integrations.closet", name = "enabled", havingValue = "true")
public class ClosetModelsService {
    private static final String BASE = "/models";
    private final ClosetManagementClient client;
    private final AuditService audit;

    public ClosetModelsService(ClosetManagementClient client, AuditService audit) {
        this.client = client;
        this.audit = audit;
    }

    public JsonNode overview() { return client.get(BASE, Map.of()); }
    public JsonNode usage(Map<String, List<String>> query) {
        var upstream = new LinkedHashMap<String, List<String>>();
        for (String key : List.of("from", "to", "capability", "providerId", "model")) {
            if (query.containsKey(key)) upstream.put(key, query.get(key));
        }
        return client.get(BASE + "/usage", upstream);
    }
    public JsonNode createProvider(JsonNode body) { return audited("PROVIDER_CREATE", "providers", () -> client.post(BASE + "/providers", body)); }
    public JsonNode updateProvider(String id, JsonNode body) { return audited("PROVIDER_UPDATE", id, () -> client.patch(path("providers", id), body)); }
    public void deleteProvider(String id) { audited("PROVIDER_DELETE", id, () -> { client.delete(path("providers", id)); return null; }); }
    public JsonNode probe(String id) { return audited("PROVIDER_PROBE", id, () -> client.post(path("providers", id) + "/probe", null)); }
    public JsonNode createEntry(JsonNode body) { return audited("ENTRY_CREATE", "entries", () -> client.post(BASE + "/entries", body)); }
    public JsonNode updateEntry(String id, JsonNode body) { return audited("ENTRY_UPDATE", id, () -> client.patch(path("entries", id), body)); }
    public JsonNode setReferences(String id, JsonNode body) { return audited("ENTRY_REFERENCES_UPDATE", id, () -> client.put(path("entries", id) + "/references", body)); }
    public void deleteEntry(String id) { audited("ENTRY_DELETE", id, () -> { client.delete(path("entries", id)); return null; }); }
    public JsonNode resume(String id) { return audited("ENTRY_RESUME", id, () -> client.post(path("entries", id) + "/resume", null)); }
    public JsonNode test(String id) { return audited("ENTRY_TEST", id, () -> client.post(path("entries", id) + "/test", null)); }
    public JsonNode route(String capability, JsonNode body) { return audited("ROUTING_UPDATE", capability, () -> client.put(routePath(capability), body)); }
    public JsonNode resetRoute(String capability) { return audited("ROUTING_RESET", capability, () -> client.deleteWithResponse(routePath(capability))); }

    private String path(String type, String id) {
        if (id == null || id.isEmpty() || id.length() > 300 || id.equals(".") || id.equals("..")) throw ApiException.notFound();
        return BASE + "/" + type + "/" + ClosetRequestSigner.encodeComponent(id);
    }

    private String routePath(String capability) {
        if (!java.util.Set.of("VISION", "IMAGE", "CHAT", "PORTRAIT").contains(capability)) throw ApiException.invalidParameter();
        return BASE + "/routing/" + capability;
    }

    private JsonNode audited(String action, String target, Supplier<JsonNode> call) {
        // Never include bodies, upstream messages, or arbitrary invalid IDs in the audit trail.
        String safeTarget = target != null && target.matches("[A-Za-z0-9_.:~-]{1,220}") ? target : "invalid";
        try {
            JsonNode result = call.get();
            String resultId = ("PROVIDER_CREATE".equals(action) || "ENTRY_CREATE".equals(action)) && result != null
                    ? result.path("id").asText("") : "";
            String auditTarget = resultId.matches("[A-Za-z0-9_.:~-]{1,220}") ? resultId : safeTarget;
            audit.event("CLOSET_MODELS_" + action, "ClosetModels", auditTarget, "SUCCESS", "{}");
            return result;
        } catch (ApiException e) {
            audit.event("CLOSET_MODELS_" + action, "ClosetModels", safeTarget, "FAILURE", "{}");
            throw e;
        } catch (RuntimeException e) {
            audit.event("CLOSET_MODELS_" + action, "ClosetModels", safeTarget, "FAILURE", "{}");
            throw ApiException.closetServiceUnavailable();
        }
    }
}
